import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

import '../codes.dart';
import '../settings.dart';
import '../signaling/lan.dart';
import '../signaling/transport.dart';
import 'native_bridge.dart';

enum MeetingRole { host, guest }

enum MeetingMode { lan, internet }

enum MeetingPhase { preparing, waiting, connecting, connected, reconnecting, ended }

class ChatMessage {
  ChatMessage({required this.mine, required this.name, required this.text, DateTime? at})
      : at = at ?? DateTime.now();
  final bool mine;
  final String name;
  final String text;
  final DateTime at;
}

class LinkStats {
  String path = '';
  String localType = '';
  String remoteType = '';
  String protocol = '';
  int? rttMs;
  double inKbps = 0;
  double outKbps = 0;
}

class UserFacingError implements Exception {
  UserFacingError(this.message);
  final String message;
  @override
  String toString() => message;
}

/// Điều khiển một cuộc họp 2 người: media cục bộ, RTCPeerConnection, kênh báo hiệu,
/// kênh dữ liệu điều khiển (trạng thái mic/camera/màn hình, chat, rời phòng).
///
/// Bố cục m-line cố định do chủ phòng tạo: audio, video (camera), video (màn hình),
/// data channel. Chia sẻ màn hình chỉ thay track (replaceTrack), không cần đàm phán lại.
class MeetingController extends ChangeNotifier {
  MeetingController({
    required this.role,
    required this.mode,
    required this.settings,
    this.lanInviteToJoin,
    this.offerText,
    this.guestSource,
    this.connector,
    this.enableNativeExtras = true,
  });

  final MeetingRole role;
  final MeetingMode mode;
  final AppSettings settings;

  /// Khách (LAN): lời mời đã quét/nhập.
  final LanInvite? lanInviteToJoin;

  /// Khách (Internet): mã mời của chủ phòng.
  final String? offerText;

  /// Kiểm thử: nguồn khách thay cho máy chủ LAN.
  final Stream<SignalingTransport>? guestSource;

  /// Kiểm thử: hàm kết nối thay cho LanClient.
  final Future<SignalingTransport> Function()? connector;

  /// Tắt các tính năng native (dịch vụ nền, wakelock, âm thanh) khi kiểm thử.
  final bool enableNativeExtras;

  // ---------------- Trạng thái công khai ----------------
  MeetingPhase phase = MeetingPhase.preparing;
  String? endReason;
  bool endedWithError = false;
  String? hint;

  bool hasMic = false;
  bool hasCam = false;
  bool micOn = true;
  bool camOn = true;
  bool screenOn = false;
  bool screenLive = false;
  bool speakerOn = true;
  bool frontCamera = true;

  bool remotePresent = false;
  String remoteName = '';
  bool remoteMic = false;
  bool remoteCam = false;
  bool remoteScreen = false;

  bool otherCallActive = false;

  final List<ChatMessage> chat = [];
  int unreadChat = 0;
  bool _chatOpen = false;

  DateTime? connectedAt;
  final LinkStats stats = LinkStats();

  LanInvite? lanInvite;
  String? offerCode;
  String? answerCode;

  final localRenderer = RTCVideoRenderer();
  final remoteCamRenderer = RTCVideoRenderer();
  final remoteScreenRenderer = RTCVideoRenderer();

  final _notices = StreamController<String>.broadcast();

  /// Thông báo ngắn cho giao diện (snackbar).
  Stream<String> get notices => _notices.stream;

  String get localName => settings.effectiveName;
  String get remoteDisplayName => remoteName.trim().isEmpty ? 'Người tham gia' : remoteName.trim();
  bool get isEnded => phase == MeetingPhase.ended;

  // ---------------- Nội bộ ----------------
  RTCPeerConnection? _pc;
  int _pcGen = 0;
  RTCDataChannel? _dc;
  MediaStream? _micStream;
  MediaStream? _camStream;
  MediaStream? _screenStream;
  RTCRtpTransceiver? _audioTx;
  RTCRtpTransceiver? _camTx;
  RTCRtpTransceiver? _screenTx;
  String? _audioMid;
  String? _camMid;
  String? _screenMid;
  MediaStream? _remoteCamStream;
  MediaStream? _remoteScreenStream;
  bool _remoteCamVideoSeen = false;

  bool _remoteDescSet = false;
  bool _tracksAttached = false;
  final _pendingRemoteCandidates = <RTCIceCandidate>[];
  final _pendingLocalCandidates = <RTCIceCandidate>[];
  bool _canSendCandidates = false;
  bool _gatheringComplete = false;
  Completer<void>? _gatheringWaiter;

  SignalingTransport? _sig;
  String _sigPeerId = '';
  StreamSubscription? _sigSub;
  Future<void> _sigQueue = Future.value();
  LanHostServer? _server;
  StreamSubscription? _guestSub;

  String _sessionId = '';
  bool _everConnected = false;
  bool _screenBusy = false;
  bool _renderersReady = false;
  bool _disposed = false;
  bool _renderersDisposed = false;
  Future<void>? _cleanupDone;
  bool _backgrounded = false;

  Timer? _statsTimer;
  Timer? _recoveryTimer;
  Timer? _giveUpTimer;
  Timer? _callMonitor;
  StreamSubscription? _nativeSub;
  int _lastBytesIn = 0;
  int _lastBytesOut = 0;
  DateTime? _lastStatsAt;

  MediaStreamTrack? get _micTrack {
    final t = _micStream?.getAudioTracks();
    return (t == null || t.isEmpty) ? null : t.first;
  }

  MediaStreamTrack? get _camTrack {
    final t = _camStream?.getVideoTracks();
    return (t == null || t.isEmpty) ? null : t.first;
  }

  MediaStreamTrack? get _screenTrack {
    final t = _screenStream?.getVideoTracks();
    return (t == null || t.isEmpty) ? null : t.first;
  }

  // =====================================================================
  // Khởi động
  // =====================================================================

  Future<void> start() async {
    try {
      await localRenderer.initialize();
      await remoteCamRenderer.initialize();
      await remoteScreenRenderer.initialize();
      _renderersReady = true;

      if (enableNativeExtras) {
        otherCallActive = await NativeBridge.instance.isOtherCallActive(beforeOurCall: true);
        await _configureAudio();
        await NativeBridge.instance.requestPermissions();
      }
      if (isEnded) return;
      await _openLocalMedia();
      if (isEnded) return;
      if (enableNativeExtras) {
        await NativeBridge.instance.startCallService();
        try {
          await WakelockPlus.enable();
        } catch (_) {}
        _nativeSub = NativeBridge.instance.events.listen(_onNativeEvent);
        _callMonitor = Timer.periodic(const Duration(seconds: 3), (_) => _checkOtherCall());
        if (otherCallActive) {
          _notice('Đang có cuộc gọi khác: P2P Meet chạy song song, không chiếm âm thanh của cuộc gọi đó.');
        }
      }
      if (isEnded) return;

      if (role == MeetingRole.host) {
        await _createPeerConnection();
        if (mode == MeetingMode.lan) {
          await _startLanHosting();
        } else {
          await _createInternetOffer();
        }
      } else {
        if (mode == MeetingMode.lan) {
          await _joinLan();
        } else {
          await _acceptInternetOffer();
        }
      }
    } catch (e, st) {
      debugPrint('MeetingController.start lỗi: $e\n$st');
      await _end(_friendly(e), error: true);
    }
  }

  Future<void> _configureAudio() async {
    try {
      if (isAndroid) {
        await NativeBridge.instance.setAudioManagement(!otherCallActive);
        await Helper.setAndroidAudioConfiguration(AndroidAudioConfiguration(
          manageAudioFocus: true,
          androidAudioMode: AndroidAudioMode.inCommunication,
          // Chỉ xin quyền âm thanh tạm thời và cho phép ứng dụng khác "giảm âm"
          // thay vì bị dừng hẳn -> không cắt ngang cuộc gọi khác.
          androidAudioFocusMode: AndroidAudioFocusMode.gainTransientMayDuck,
          androidAudioStreamType: AndroidAudioStreamType.voiceCall,
          androidAudioAttributesUsageType: AndroidAudioAttributesUsageType.voiceCommunication,
          androidAudioAttributesContentType: AndroidAudioAttributesContentType.speech,
        ));
      } else if (isIOS) {
        await Helper.setAppleAudioConfiguration(AppleAudioConfiguration(
          appleAudioCategory: AppleAudioCategory.playAndRecord,
          // mixWithOthers: không ngắt phiên âm thanh của ứng dụng/cuộc gọi khác.
          appleAudioCategoryOptions: const {
            AppleAudioCategoryOption.mixWithOthers,
            AppleAudioCategoryOption.allowBluetooth,
            AppleAudioCategoryOption.allowBluetoothA2DP,
            AppleAudioCategoryOption.defaultToSpeaker,
          },
          appleAudioMode: AppleAudioMode.videoChat,
        ));
      }
    } catch (e) {
      debugPrint('Cấu hình âm thanh lỗi: $e');
    }
  }

  Future<void> _openLocalMedia() async {
    try {
      _micStream = await navigator.mediaDevices.getUserMedia({'audio': true, 'video': false});
      hasMic = _micTrack != null;
    } catch (e) {
      debugPrint('Không mở được micro: $e');
      hasMic = false;
    }
    micOn = hasMic;
    if (isEnded) return;
    final q = settings.quality;
    try {
      _camStream = await navigator.mediaDevices.getUserMedia({
        'audio': false,
        'video': {
          'facingMode': 'user',
          'width': q.width,
          'height': q.height,
          'frameRate': q.fps,
        },
      });
      hasCam = _camTrack != null;
    } catch (e) {
      debugPrint('Không mở được camera: $e');
      hasCam = false;
    }
    camOn = hasCam;
    if (_camStream != null) _setSrc(localRenderer, _camStream);
    if (!hasMic && !hasCam) {
      _notice('Không truy cập được micro và camera. Bạn vẫn có thể xem/nghe và chia sẻ màn hình.');
    } else if (!hasMic) {
      _notice('Không truy cập được micro (chưa cấp quyền?).');
    } else if (!hasCam) {
      _notice('Không truy cập được camera (chưa cấp quyền?).');
    }
    if (isMobile && hasMic) {
      try {
        await Helper.setSpeakerphoneOn(true);
      } catch (_) {}
    }
    _notify();
  }

  // =====================================================================
  // RTCPeerConnection
  // =====================================================================

  Future<void> _createPeerConnection() async {
    final gen = ++_pcGen;
    final config = <String, dynamic>{
      // Chế độ LAN không dùng bất kỳ máy chủ nào; chế độ Internet chỉ dùng STUN
      // (và TURN riêng nếu người dùng tự cấu hình).
      'iceServers': mode == MeetingMode.internet ? settings.iceServers() : <Map<String, dynamic>>[],
      'sdpSemantics': 'unified-plan',
      'bundlePolicy': 'max-bundle',
      'rtcpMuxPolicy': 'require',
    };
    final pc = await createPeerConnection(config);
    _pc = pc;
    _remoteDescSet = false;
    _tracksAttached = false;
    _canSendCandidates = false;
    _gatheringComplete = false;
    _pendingLocalCandidates.clear();
    _pendingRemoteCandidates.clear();
    _remoteCamVideoSeen = false;

    pc.onIceCandidate = (c) {
      if (gen != _pcGen) return;
      _onLocalCandidate(c);
    };
    pc.onIceGatheringState = (s) {
      if (gen != _pcGen) return;
      if (s == RTCIceGatheringState.RTCIceGatheringStateComplete) _markGatheringComplete();
    };
    pc.onConnectionState = (s) {
      if (gen != _pcGen) return;
      _onPcState(s);
    };
    pc.onIceConnectionState = (s) {
      if (gen != _pcGen) return;
      _onIceState(s);
    };
    pc.onTrack = (e) {
      if (gen != _pcGen) return;
      _onRemoteTrack(e, gen);
    };
    pc.onDataChannel = (dc) {
      if (gen != _pcGen) return;
      _setupDataChannel(dc);
    };

    if (role == MeetingRole.host) {
      _audioTx = await _addTransceiver(
        pc,
        _micTrack,
        RTCRtpMediaType.RTCRtpMediaTypeAudio,
        RTCRtpTransceiverInit(
          direction: TransceiverDirection.SendRecv,
          streams: _micStream != null && _micTrack != null ? [_micStream!] : [],
        ),
      );
      _camTx = await _addTransceiver(
        pc,
        _camTrack,
        RTCRtpMediaType.RTCRtpMediaTypeVideo,
        RTCRtpTransceiverInit(
          direction: TransceiverDirection.SendRecv,
          streams: _camStream != null && _camTrack != null ? [_camStream!] : [],
        ),
      );
      _screenTx = await _addTransceiver(
        pc,
        _screenTrack,
        RTCRtpMediaType.RTCRtpMediaTypeVideo,
        RTCRtpTransceiverInit(direction: TransceiverDirection.SendRecv),
      );
      final dc = await pc.createDataChannel('ctl', RTCDataChannelInit()..ordered = true);
      _setupDataChannel(dc);
    }
  }

  static Future<RTCRtpTransceiver> _addTransceiver(
    RTCPeerConnection pc,
    MediaStreamTrack? track,
    RTCRtpMediaType kind,
    RTCRtpTransceiverInit init,
  ) =>
      track != null
          ? pc.addTransceiver(track: track, kind: kind, init: init)
          : pc.addTransceiver(kind: kind, init: init);

  /// Giới hạn băng thông gửi theo chất lượng đã chọn (camera) và cho màn hình.
  Future<void> _applyBitrates() async {
    Future<void> limit(RTCRtpTransceiver? tx, int kbps) async {
      if (tx == null) return;
      try {
        final params = tx.sender.parameters;
        final enc = params.encodings;
        if (enc == null || enc.isEmpty) return;
        for (final e in enc) {
          e.maxBitrate = kbps * 1000;
        }
        await tx.sender.setParameters(params);
      } catch (e) {
        debugPrint('Đặt bitrate lỗi: $e');
      }
    }

    await limit(_camTx, settings.quality.maxKbps);
    await limit(_screenTx, 2500);
    await limit(_audioTx, 64);
  }

  /// Ghi nhận mid của từng m-line theo thứ tự: audio, video(camera), video(màn hình).
  void _parseMids(String? sdp) {
    if (sdp == null) return;
    String? kind;
    final audio = <String>[];
    final video = <String>[];
    for (final raw in const LineSplitter().convert(sdp)) {
      final line = raw.trim();
      if (line.startsWith('m=')) {
        kind = line.substring(2).split(' ').first;
      } else if (line.startsWith('a=mid:')) {
        final mid = line.substring(6).trim();
        if (kind == 'audio') audio.add(mid);
        if (kind == 'video') video.add(mid);
      }
    }
    _audioMid = audio.isNotEmpty ? audio.first : null;
    _camMid = video.isNotEmpty ? video[0] : null;
    _screenMid = video.length > 1 ? video[1] : null;
  }

  /// Khách: gắn track cục bộ vào các transceiver được tạo từ offer của chủ phòng.
  Future<void> _attachAsAnswerer() async {
    final pc = _pc;
    if (pc == null) return;
    final txs = await pc.getTransceivers();
    for (final tx in txs) {
      final mid = tx.mid;
      if (mid == _audioMid && _audioMid != null) {
        _audioTx = tx;
        final mic = _micTrack;
        if (mic != null) await tx.sender.replaceTrack(mic);
        await tx.setDirection(TransceiverDirection.SendRecv);
      } else if (mid == _camMid && _camMid != null) {
        _camTx = tx;
        final cam = _camTrack;
        if (cam != null) await tx.sender.replaceTrack(cam);
        await tx.setDirection(TransceiverDirection.SendRecv);
      } else if (mid == _screenMid && _screenMid != null) {
        _screenTx = tx;
        final scr = _screenTrack;
        if (scr != null) await tx.sender.replaceTrack(scr);
        await tx.setDirection(TransceiverDirection.SendRecv);
      }
    }
    _tracksAttached = true;
  }

  void _onLocalCandidate(RTCIceCandidate c) {
    if (c.candidate == null || c.candidate!.isEmpty) {
      _markGatheringComplete();
      return;
    }
    // Chế độ Internet: ứng viên nằm sẵn trong mã (không trickle).
    if (mode != MeetingMode.lan) return;
    if (!_canSendCandidates) {
      _pendingLocalCandidates.add(c);
      return;
    }
    _sendCandidate(c);
  }

  void _sendCandidate(RTCIceCandidate c) {
    final sig = _sig;
    if (sig == null || sig.isClosed) return;
    sig.send({
      't': 'ice',
      'c': {'candidate': c.candidate, 'sdpMid': c.sdpMid, 'sdpMLineIndex': c.sdpMLineIndex},
    }).catchError((_) {});
  }

  void _flushLocalCandidates() {
    _canSendCandidates = true;
    final list = List<RTCIceCandidate>.from(_pendingLocalCandidates);
    _pendingLocalCandidates.clear();
    for (final c in list) {
      _sendCandidate(c);
    }
  }

  Future<void> _flushRemoteCandidates() async {
    final list = List<RTCIceCandidate>.from(_pendingRemoteCandidates);
    _pendingRemoteCandidates.clear();
    for (final c in list) {
      try {
        await _pc?.addCandidate(c);
      } catch (e) {
        debugPrint('addCandidate lỗi: $e');
      }
    }
  }

  void _markGatheringComplete() {
    _gatheringComplete = true;
    final w = _gatheringWaiter;
    if (w != null && !w.isCompleted) w.complete();
  }

  Future<void> _waitIceGathering({Duration timeout = const Duration(seconds: 6)}) async {
    if (_gatheringComplete) return;
    final w = _gatheringWaiter = Completer<void>();
    await w.future.timeout(timeout, onTimeout: () {});
  }

  Future<void> _resetPeerConnection() async {
    final old = _pc;
    final oldDc = _dc;
    _pc = null;
    _dc = null;
    _pcGen++;
    _stopStats();
    try {
      await oldDc?.close();
    } catch (_) {}
    try {
      await old?.close();
    } catch (_) {}
    try {
      await old?.dispose();
    } catch (_) {}
    await _clearRemoteMedia();
    _audioTx = _camTx = _screenTx = null;
    _audioMid = _camMid = _screenMid = null;
    if (!isEnded) await _createPeerConnection();
  }

  Future<void> _clearRemoteMedia() async {
    _setSrc(remoteCamRenderer, null);
    _setSrc(remoteScreenRenderer, null);
    final streams = [_remoteCamStream, _remoteScreenStream];
    _remoteCamStream = null;
    _remoteScreenStream = null;
    for (final s in streams) {
      if (s == null) continue;
      try {
        // Chỉ gỡ track khỏi stream bọc (track thuộc về receiver, không dừng ở đây).
        if (!kIsWeb) await s.dispose();
      } catch (_) {}
    }
    remotePresent = false;
    remoteMic = remoteCam = remoteScreen = false;
  }

  Future<void> _onRemoteTrack(RTCTrackEvent e, int gen) async {
    final track = e.track;
    final mid = e.transceiver?.mid;
    try {
      if (track.kind == 'audio') {
        final s = _remoteCamStream ??= await createLocalMediaStream('rcam_$gen');
        if (gen != _pcGen) return;
        await s.addTrack(track);
        if (gen != _pcGen) return;
        _setSrc(remoteCamRenderer, s);
        if (kIsWeb) remoteCamRenderer.muted = false;
        return;
      }
      bool isScreen;
      if (mid != null && mid.isNotEmpty && (mid == _screenMid || mid == _camMid)) {
        isScreen = mid == _screenMid;
      } else {
        isScreen = _remoteCamVideoSeen;
      }
      if (isScreen) {
        final s = _remoteScreenStream ??= await createLocalMediaStream('rscr_$gen');
        if (gen != _pcGen) return;
        await s.addTrack(track);
        if (gen != _pcGen) return;
        _setSrc(remoteScreenRenderer, s);
      } else {
        _remoteCamVideoSeen = true;
        final s = _remoteCamStream ??= await createLocalMediaStream('rcam_$gen');
        if (gen != _pcGen) return;
        await s.addTrack(track);
        if (gen != _pcGen) return;
        _setSrc(remoteCamRenderer, s);
        if (kIsWeb) remoteCamRenderer.muted = false;
      }
      _notify();
    } catch (err) {
      debugPrint('Gắn track từ xa lỗi: $err');
    }
  }

  // =====================================================================
  // Chế độ Wi-Fi / Hotspot (LAN)
  // =====================================================================

  Future<void> _startLanHosting() async {
    final source = guestSource;
    if (source != null) {
      lanInvite = LanInvite(hosts: const ['127.0.0.1'], port: 1, room: RoomCode.generate(), hostName: localName);
      _guestSub = source.listen(_onGuest);
      _setPhase(MeetingPhase.waiting);
      return;
    }
    final addrs = await localLanAddresses();
    if (addrs.isEmpty) {
      throw UserFacingError('Máy chưa kết nối Wi-Fi hoặc chưa bật Điểm truy cập cá nhân (Hotspot).\n'
          'Hãy kết nối 2 máy vào cùng một Wi-Fi, hoặc bật Hotspot trên một máy và cho máy kia kết nối vào.');
    }
    final room = RoomCode.generate();
    final server = _server = LanHostServer(roomCode: room);
    await server.start();
    lanInvite = LanInvite(hosts: addrs, port: server.port, room: room, hostName: localName);
    _guestSub = server.guests.listen(_onGuest);
    _setPhase(MeetingPhase.waiting);
  }

  Future<void> _onGuest(SignalingTransport t) async {
    if (isEnded) {
      await t.close();
      return;
    }
    // Cùng một máy vào lại (ví dụ app bị tắt rồi mở lại): thay phiên cũ.
    final sameDevice = t.peerId.isNotEmpty && t.peerId == _sigPeerId;
    final inSession = (_sig != null && !_sig!.isClosed) || remotePresent;
    final busy = !sameDevice &&
        inSession &&
        (phase == MeetingPhase.connecting || phase == MeetingPhase.connected || phase == MeetingPhase.reconnecting);
    if (busy) {
      try {
        await t.send({'t': 'busy'});
      } catch (_) {}
      await Future<void>.delayed(const Duration(milliseconds: 200));
      await t.close();
      return;
    }
    _recoveryTimer?.cancel();
    _giveUpTimer?.cancel();
    await _detachSignaling();
    _sig = t;
    _sigPeerId = t.peerId;
    if (t.peerName.isNotEmpty) remoteName = t.peerName;
    try {
      await t.send({'t': 'welcome', 'name': localName, 'id': settings.deviceId});
    } catch (_) {
      return;
    }
    _sigSub = t.messages.listen(_onSignal, onDone: () => _onSignalClosed(t));
    try {
      // Mỗi phiên khách mới dùng một PeerConnection mới (DTLS mới).
      if (_remoteDescSet || _pc == null) {
        _everConnected = false;
        connectedAt = null;
        await _resetPeerConnection();
        if (t.peerName.isNotEmpty) remoteName = t.peerName;
      }
      _setPhase(MeetingPhase.connecting);
      await _sendOffer();
    } catch (e) {
      debugPrint('Gửi offer lỗi: $e');
    }
  }

  Future<void> _sendOffer({bool iceRestart = false}) async {
    final pc = _pc;
    final sig = _sig;
    if (pc == null || sig == null || sig.isClosed) return;
    _canSendCandidates = false;
    if (iceRestart) {
      try {
        await pc.restartIce();
      } catch (_) {}
    }
    final offer = await pc.createOffer(iceRestart ? {'iceRestart': true} : <String, dynamic>{});
    _gatheringComplete = false;
    await pc.setLocalDescription(offer);
    _parseMids(offer.sdp);
    await sig.send({'t': 'offer', 'sdp': offer.sdp});
    _flushLocalCandidates();
  }

  Future<void> _joinLan() async {
    await _createPeerConnection();
    _setPhase(MeetingPhase.connecting);
    final SignalingTransport t;
    final c = connector;
    if (c != null) {
      t = await c();
    } else {
      final inv = lanInviteToJoin;
      if (inv == null) throw UserFacingError('Thiếu lời mời');
      t = await LanClient.connect(
        hosts: inv.hosts,
        port: inv.port,
        roomCode: inv.room,
        name: localName,
        guestId: settings.deviceId,
      );
    }
    if (isEnded) {
      await t.close();
      return;
    }
    _sig = t;
    _sigPeerId = t.peerId;
    if (t.peerName.isNotEmpty) remoteName = t.peerName;
    _sigSub = t.messages.listen(_onSignal, onDone: () => _onSignalClosed(t));
    _notify();
  }

  void _onSignal(Map<String, dynamic> m) {
    _sigQueue = _sigQueue.then((_) => _handleSignal(m)).catchError((Object e) {
      debugPrint('Xử lý báo hiệu lỗi: $e');
    });
  }

  Future<void> _handleSignal(Map<String, dynamic> m) async {
    if (isEnded) return;
    final pc = _pc;
    switch (m['t']) {
      case 'offer':
        if (role != MeetingRole.guest || pc == null) return;
        final sdp = m['sdp'] as String;
        _parseMids(sdp);
        _canSendCandidates = false;
        await pc.setRemoteDescription(RTCSessionDescription(sdp, 'offer'));
        _remoteDescSet = true;
        if (!_tracksAttached) await _attachAsAnswerer();
        final answer = await pc.createAnswer();
        await pc.setLocalDescription(answer);
        await _flushRemoteCandidates();
        await _sig?.send({'t': 'answer', 'sdp': answer.sdp});
        _flushLocalCandidates();
        if (phase == MeetingPhase.waiting || phase == MeetingPhase.preparing) _setPhase(MeetingPhase.connecting);
        break;
      case 'answer':
        if (role != MeetingRole.host || pc == null) return;
        await pc.setRemoteDescription(RTCSessionDescription(m['sdp'] as String, 'answer'));
        _remoteDescSet = true;
        await _flushRemoteCandidates();
        break;
      case 'ice':
        final c = m['c'];
        if (c is! Map) return;
        final cand = RTCIceCandidate(
          c['candidate']?.toString(),
          c['sdpMid']?.toString(),
          c['sdpMLineIndex'] is int ? c['sdpMLineIndex'] as int : int.tryParse('${c['sdpMLineIndex']}'),
        );
        if (_remoteDescSet && pc != null) {
          try {
            await pc.addCandidate(cand);
          } catch (e) {
            debugPrint('addCandidate lỗi: $e');
          }
        } else {
          _pendingRemoteCandidates.add(cand);
        }
        break;
      case 'bye':
        await _onRemoteBye();
        break;
    }
  }

  void _onSignalClosed(SignalingTransport t) {
    if (!identical(t, _sig) || isEnded) return;
    _sig = null;
    if (phase == MeetingPhase.connected) return; // P2P vẫn chạy, không cần báo hiệu nữa.
    if (role == MeetingRole.guest) {
      if (!_everConnected) {
        _end('Mất kết nối tới chủ phòng.', error: true);
      }
    } else if (phase == MeetingPhase.connecting && !_everConnected) {
      _backToWaiting('Người tham gia đã ngắt trước khi kết nối xong.');
    }
  }

  Future<void> _detachSignaling() async {
    final sub = _sigSub;
    final sig = _sig;
    _sigSub = null;
    _sig = null;
    await sub?.cancel();
    try {
      await sig?.close();
    } catch (_) {}
  }

  // =====================================================================
  // Chế độ Internet (trao đổi mã, không cần máy chủ báo hiệu)
  // =====================================================================

  Future<void> _createInternetOffer() async {
    final pc = _pc!;
    _sessionId = randomId(6);
    offerCode = null;
    _gatheringComplete = false;
    final offer = await pc.createOffer(<String, dynamic>{});
    await pc.setLocalDescription(offer);
    _parseMids(offer.sdp);
    await _waitIceGathering();
    final desc = await pc.getLocalDescription();
    final sdp = desc?.sdp ?? offer.sdp;
    offerCode = ConnectionCode.encode(CodeKind.offer, {'s': _sessionId, 'n': localName, 'sdp': sdp});
    _setPhase(MeetingPhase.waiting);
  }

  /// Chủ phòng (Internet): nhập mã trả lời của người tham gia.
  Future<void> applyAnswerCode(String text) async {
    if (role != MeetingRole.host || mode != MeetingMode.internet) return;
    final d = ConnectionCode.decode(text, expect: CodeKind.answer);
    if (d.payload['s'] != _sessionId) {
      throw CodeFormatException('Mã trả lời này không thuộc lời mời hiện tại. Hãy gửi lại mã mời mới nhất.');
    }
    if (_remoteDescSet) throw CodeFormatException('Đã nhập mã trả lời rồi.');
    final sdp = d.payload['sdp'];
    if (sdp is! String) throw CodeFormatException('Mã trả lời không hợp lệ.');
    remoteName = (d.payload['n'] ?? '').toString();
    await _pc!.setRemoteDescription(RTCSessionDescription(sdp, 'answer'));
    _remoteDescSet = true;
    if (phase == MeetingPhase.waiting) _setPhase(MeetingPhase.connecting);
    _armGiveUp(const Duration(seconds: 40),
        'Không thể kết nối trực tiếp tới máy kia.\nHai máy có thể đang ở sau NAT đối xứng (thường gặp với 4G). '
        'Hãy thử cùng Wi-Fi/Hotspot, hoặc thêm máy chủ TURN riêng trong Cài đặt.');
  }

  Future<void> _acceptInternetOffer() async {
    final d = ConnectionCode.decode(offerText ?? '', expect: CodeKind.offer);
    final sdp = d.payload['sdp'];
    if (sdp is! String) throw CodeFormatException('Mã mời không hợp lệ.');
    _sessionId = (d.payload['s'] ?? '').toString();
    remoteName = (d.payload['n'] ?? '').toString();
    await _createPeerConnection();
    final pc = _pc!;
    _parseMids(sdp);
    await pc.setRemoteDescription(RTCSessionDescription(sdp, 'offer'));
    _remoteDescSet = true;
    await _attachAsAnswerer();
    _gatheringComplete = false;
    final answer = await pc.createAnswer(<String, dynamic>{});
    await pc.setLocalDescription(answer);
    await _waitIceGathering();
    final desc = await pc.getLocalDescription();
    answerCode = ConnectionCode.encode(
        CodeKind.answer, {'s': _sessionId, 'n': localName, 'sdp': desc?.sdp ?? answer.sdp});
    if (phase == MeetingPhase.preparing) _setPhase(MeetingPhase.connecting);
  }

  // =====================================================================
  // Trạng thái kết nối
  // =====================================================================

  void _onPcState(RTCPeerConnectionState s) {
    switch (s) {
      case RTCPeerConnectionState.RTCPeerConnectionStateConnected:
        _onConnected();
        break;
      case RTCPeerConnectionState.RTCPeerConnectionStateDisconnected:
        _onDisconnected();
        break;
      case RTCPeerConnectionState.RTCPeerConnectionStateFailed:
        _onLinkFailed();
        break;
      default:
        break;
    }
  }

  /// Trạng thái ICE chỉ dùng để phát hiện mất kết nối. "Đã kết nối" chỉ được xác nhận
  /// bằng trạng thái PeerConnection (ICE + DTLS) hoặc khi kênh dữ liệu mở: ở chế độ
  /// Internet, ICE có thể báo connected sớm trước khi chủ phòng nhập mã trả lời.
  void _onIceState(RTCIceConnectionState s) {
    switch (s) {
      case RTCIceConnectionState.RTCIceConnectionStateDisconnected:
        _onDisconnected();
        break;
      case RTCIceConnectionState.RTCIceConnectionStateFailed:
        _onLinkFailed();
        break;
      default:
        break;
    }
  }

  void _onConnected() {
    if (isEnded) return;
    _recoveryTimer?.cancel();
    _giveUpTimer?.cancel();
    hint = null;
    if (phase != MeetingPhase.connected) {
      final wasReconnecting = phase == MeetingPhase.reconnecting;
      _everConnected = true;
      connectedAt ??= DateTime.now();
      _setPhase(MeetingPhase.connected);
      _startStats();
      _sendState();
      _applyBitrates();
      if (wasReconnecting) _notice('Đã kết nối lại');
    }
  }

  void _onDisconnected() {
    if (isEnded) return;
    if (phase == MeetingPhase.connected) {
      _setPhase(MeetingPhase.reconnecting);
      _recoveryTimer?.cancel();
      _recoveryTimer = Timer(const Duration(seconds: 5), _tryRecover);
      _armGiveUp(const Duration(seconds: 35), null);
    }
  }

  void _onLinkFailed() {
    if (isEnded) return;
    if (_everConnected) {
      if (phase == MeetingPhase.connected) _setPhase(MeetingPhase.reconnecting);
      _tryRecover();
      _armGiveUp(const Duration(seconds: 30), null);
      return;
    }
    if (mode == MeetingMode.internet) {
      if (role == MeetingRole.guest) {
        hint = 'Chưa kết nối được. Hãy chắc chắn chủ phòng đã nhập mã trả lời. '
            'Nếu vẫn không được, 2 máy có thể ở sau NAT đối xứng (4G): hãy dùng cùng Wi-Fi/Hotspot hoặc TURN riêng.';
        _notify();
      }
      return;
    }
    if (role == MeetingRole.host && _sig != null) {
      _tryRecover();
      _armGiveUp(const Duration(seconds: 20),
          'Không thể kết nối trực tiếp. Mạng Wi-Fi có thể đang chặn kết nối giữa các thiết bị (AP isolation).');
    } else {
      _armGiveUp(const Duration(seconds: 20),
          'Không thể kết nối trực tiếp. Mạng Wi-Fi có thể đang chặn kết nối giữa các thiết bị (AP isolation).');
    }
  }

  void _tryRecover() {
    if (isEnded || phase == MeetingPhase.connected) return;
    // Chỉ chủ phòng ở chế độ LAN có kênh báo hiệu để khởi động lại ICE.
    if (role == MeetingRole.host && mode == MeetingMode.lan && _sig != null && !_sig!.isClosed) {
      _sendOffer(iceRestart: true).catchError((Object e) => debugPrint('ICE restart lỗi: $e'));
    }
  }

  void _armGiveUp(Duration d, String? message) {
    _giveUpTimer?.cancel();
    _giveUpTimer = Timer(d, () {
      if (isEnded || phase == MeetingPhase.connected) return;
      final msg = message ?? 'Mất kết nối với $remoteDisplayName.';
      // Chủ phòng giữ phòng mở và chờ người khác (như Zoom); khách thì kết thúc.
      if (role == MeetingRole.host) {
        _backToWaiting(msg);
      } else {
        _end(msg, error: true);
      }
    });
  }

  Future<void> _backToWaiting(String notice) async {
    if (isEnded) return;
    _recoveryTimer?.cancel();
    _giveUpTimer?.cancel();
    _everConnected = false;
    connectedAt = null;
    await _detachSignaling();
    await _resetPeerConnection();
    remoteName = '';
    _notice(notice);
    if (isEnded) return;
    if (mode == MeetingMode.internet) {
      _setPhase(MeetingPhase.preparing);
      try {
        await _createInternetOffer();
      } catch (e) {
        await _end(_friendly(e), error: true);
      }
    } else {
      _setPhase(MeetingPhase.waiting);
    }
  }

  Future<void> _onRemoteBye() async {
    if (isEnded) return;
    final name = remoteDisplayName;
    if (role == MeetingRole.host) {
      await _backToWaiting('$name đã rời cuộc họp.');
    } else {
      await _end('$name đã kết thúc cuộc họp.');
    }
  }

  // =====================================================================
  // Kênh dữ liệu điều khiển
  // =====================================================================

  void _setupDataChannel(RTCDataChannel dc) {
    _dc = dc;
    dc.onDataChannelState = (s) {
      if (!identical(dc, _dc)) return;
      if (s == RTCDataChannelState.RTCDataChannelOpen) {
        _onConnected();
        _sendState();
      }
    };
    dc.onMessage = (msg) {
      if (!identical(dc, _dc) || msg.isBinary) return;
      _onControlMessage(msg.text);
    };
    if (dc.state == RTCDataChannelState.RTCDataChannelOpen) _sendState();
  }

  bool get _dcOpen => _dc?.state == RTCDataChannelState.RTCDataChannelOpen;

  void _sendControl(Map<String, dynamic> m) {
    final dc = _dc;
    if (dc == null || !_dcOpen) return;
    dc.send(RTCDataChannelMessage(jsonEncode(m))).catchError((_) {});
  }

  void _sendState() {
    _sendControl({
      't': 'state',
      'name': localName,
      'mic': hasMic && micOn,
      'cam': hasCam && camOn && !(isIOS && _backgrounded),
      'screen': screenOn && screenLive,
    });
  }

  void _onControlMessage(String text) {
    Map<String, dynamic> m;
    try {
      final d = jsonDecode(text);
      if (d is! Map<String, dynamic>) return;
      m = d;
    } catch (_) {
      return;
    }
    switch (m['t']) {
      case 'state':
        final wasPresent = remotePresent;
        final wasScreen = remoteScreen;
        remotePresent = true;
        final n = (m['name'] ?? '').toString();
        if (n.isNotEmpty) remoteName = n;
        remoteMic = m['mic'] == true;
        remoteCam = m['cam'] == true;
        remoteScreen = m['screen'] == true;
        if (!wasPresent) _notice('$remoteDisplayName đã tham gia');
        if (!wasScreen && remoteScreen) _notice('$remoteDisplayName đang chia sẻ màn hình');
        _notify();
        break;
      case 'chat':
        final t = (m['text'] ?? '').toString();
        if (t.isEmpty) return;
        chat.add(ChatMessage(mine: false, name: remoteDisplayName, text: t));
        if (!_chatOpen) {
          unreadChat++;
          _notice('$remoteDisplayName: $t');
        }
        _notify();
        break;
      case 'bye':
        _onRemoteBye();
        break;
    }
  }

  // =====================================================================
  // Thao tác người dùng
  // =====================================================================

  void toggleMic() {
    final t = _micTrack;
    if (t == null) {
      _notice('Chưa có quyền dùng micro');
      return;
    }
    micOn = !micOn;
    t.enabled = micOn;
    _sendState();
    _notify();
  }

  void toggleCamera() {
    final t = _camTrack;
    if (t == null) {
      _notice('Chưa có quyền dùng camera');
      return;
    }
    camOn = !camOn;
    t.enabled = camOn;
    _sendState();
    _notify();
  }

  Future<void> switchCamera() async {
    final t = _camTrack;
    if (t == null || kIsWeb) return;
    try {
      frontCamera = await Helper.switchCamera(t);
      _notify();
    } catch (e) {
      _notice('Không đổi được camera');
    }
  }

  bool _cameraSuspended = false;

  /// Tạm nhả camera (ví dụ để mở máy quét QR): điện thoại không cho 2 phiên camera cùng lúc.
  Future<void> suspendCamera() async {
    final s = _camStream;
    if (s == null || _cameraSuspended) return;
    _cameraSuspended = true;
    try {
      await _camTx?.sender.replaceTrack(null);
    } catch (_) {}
    _setSrc(localRenderer, null);
    _camStream = null;
    for (final t in s.getTracks()) {
      try {
        await t.stop();
      } catch (_) {}
    }
    try {
      await s.dispose();
    } catch (_) {}
    _notify();
  }

  /// Mở lại camera sau [suspendCamera] và gắn lại vào kết nối.
  Future<void> resumeCamera() async {
    if (!_cameraSuspended || isEnded) return;
    _cameraSuspended = false;
    final q = settings.quality;
    try {
      _camStream = await navigator.mediaDevices.getUserMedia({
        'audio': false,
        'video': {
          'facingMode': frontCamera ? 'user' : 'environment',
          'width': q.width,
          'height': q.height,
          'frameRate': q.fps,
        },
      });
      final t = _camTrack;
      if (t == null) throw UserFacingError('Không có camera');
      t.enabled = camOn;
      await _camTx?.sender.replaceTrack(t);
      _setSrc(localRenderer, _camStream);
    } catch (e) {
      debugPrint('Mở lại camera lỗi: $e');
      hasCam = false;
      camOn = false;
      _sendState();
      _notice('Không mở lại được camera');
    }
    _notify();
  }

  Future<void> toggleSpeaker() async {
    if (!isMobile) return;
    speakerOn = !speakerOn;
    try {
      await Helper.setSpeakerphoneOn(speakerOn);
    } catch (_) {}
    _notify();
  }

  Future<void> toggleScreenShare() => screenOn ? stopScreenShare() : startScreenShare();

  Future<void> startScreenShare() async {
    if (_screenBusy || screenOn || isEnded) return;
    _screenBusy = true;
    try {
      final MediaStream stream;
      if (isAndroid) {
        // Android 14+: phải xin quyền MediaProjection mới mỗi lần, rồi chạy dịch vụ
        // nền loại mediaProjection TRƯỚC khi bắt đầu chụp màn hình.
        final granted = await Helper.requestCapturePermission();
        if (!granted) {
          _notice('Bạn đã huỷ chia sẻ màn hình');
          return;
        }
        final ok = await NativeBridge.instance.startCallService(screen: true);
        if (!ok) throw UserFacingError('Không khởi động được dịch vụ chia sẻ màn hình');
        stream = await navigator.mediaDevices.getDisplayMedia({'video': true, 'audio': false});
      } else if (isIOS) {
        // Hiện bảng chọn "Phát màn hình" của hệ thống (Broadcast Upload Extension).
        stream = await navigator.mediaDevices.getDisplayMedia({
          'video': {'deviceId': 'broadcast'},
          'audio': false,
        });
      } else {
        stream = await navigator.mediaDevices.getDisplayMedia({'video': true, 'audio': false});
      }
      if (isEnded) {
        for (final t in stream.getTracks()) {
          await t.stop();
        }
        return;
      }
      _screenStream = stream;
      final track = _screenTrack;
      if (track == null) throw UserFacingError('Không lấy được hình màn hình');
      track.onEnded = () {
        if (screenOn) stopScreenShare();
      };
      await _screenTx?.sender.replaceTrack(track);
      screenOn = true;
      // iOS: chỉ báo "đang chia sẻ" khi người dùng thật sự bấm Bắt đầu phát.
      screenLive = !isIOS;
      if (isIOS) _notice('Chọn "P2P Meet" rồi bấm "Bắt đầu phát" để chia sẻ toàn màn hình');
      _sendState();
      _notify();
    } catch (e) {
      debugPrint('Chia sẻ màn hình lỗi: $e');
      _notice('Không thể chia sẻ màn hình: ${_friendly(e)}');
      if (isAndroid) await NativeBridge.instance.startCallService(screen: false);
      await _disposeScreenStream();
    } finally {
      _screenBusy = false;
    }
  }

  Future<void> stopScreenShare() async {
    if (!screenOn) return;
    screenOn = false;
    screenLive = false;
    _sendState();
    _notify();
    try {
      await _screenTx?.sender.replaceTrack(null);
    } catch (_) {}
    await _disposeScreenStream();
    if (isAndroid && !isEnded) await NativeBridge.instance.startCallService(screen: false);
  }

  Future<void> _disposeScreenStream() async {
    final s = _screenStream;
    _screenStream = null;
    if (s == null) return;
    for (final t in s.getTracks()) {
      try {
        await t.stop();
      } catch (_) {}
    }
    try {
      await s.dispose();
    } catch (_) {}
  }

  void _onNativeEvent(String event) {
    switch (event) {
      case 'broadcastStarted':
        if (screenOn && !screenLive) {
          screenLive = true;
          _sendState();
          _notify();
        }
        break;
      case 'broadcastStopped':
        if (screenOn) stopScreenShare();
        break;
    }
  }

  void sendChat(String text) {
    final t = text.trim();
    if (t.isEmpty) return;
    if (!_dcOpen) {
      _notice('Chưa kết nối, chưa gửi được tin nhắn');
      return;
    }
    _sendControl({'t': 'chat', 'text': t});
    chat.add(ChatMessage(mine: true, name: localName, text: t));
    _notify();
  }

  set chatOpen(bool open) {
    _chatOpen = open;
    if (open && unreadChat != 0) {
      unreadChat = 0;
      _notify();
    }
  }

  Future<void> hangUp() async {
    if (isEnded) return;
    _sendControl({'t': 'bye'});
    try {
      await _sig?.send({'t': 'bye'});
    } catch (_) {}
    await Future<void>.delayed(const Duration(milliseconds: 150));
    await _end(null);
  }

  // =====================================================================
  // Thống kê & giám sát cuộc gọi khác
  // =====================================================================

  void _startStats() {
    _statsTimer?.cancel();
    _lastStatsAt = null;
    _statsTimer = Timer.periodic(const Duration(seconds: 2), (_) => _pollStats());
    _pollStats();
  }

  void _stopStats() {
    _statsTimer?.cancel();
    _statsTimer = null;
  }

  static num? _num(dynamic v) => v is num ? v : num.tryParse('$v');

  Future<void> _pollStats() async {
    final pc = _pc;
    if (pc == null || isEnded) return;
    try {
      final reports = await pc.getStats();
      final byId = {for (final r in reports) r.id: r};
      StatsReport? pair;
      for (final r in reports) {
        if (r.type == 'transport') {
          final id = r.values['selectedCandidatePairId'];
          if (id != null && byId[id] != null) pair = byId[id];
        }
      }
      if (pair == null) {
        for (final r in reports) {
          if (r.type != 'candidate-pair') continue;
          final v = r.values;
          final nominated = v['nominated'] == true || v['nominated'] == 'true' || v['selected'] == true;
          if (nominated && v['state'] == 'succeeded') {
            pair = r;
            break;
          }
        }
      }
      if (pair == null) return;
      final local = byId[pair.values['localCandidateId']];
      final remote = byId[pair.values['remoteCandidateId']];
      stats.localType = (local?.values['candidateType'] ?? '').toString();
      stats.remoteType = (remote?.values['candidateType'] ?? '').toString();
      stats.protocol = (local?.values['protocol'] ?? '').toString();
      final rtt = _num(pair.values['currentRoundTripTime']);
      stats.rttMs = rtt == null ? null : (rtt * 1000).round();
      final inBytes = _num(pair.values['bytesReceived'])?.toInt() ?? 0;
      final outBytes = _num(pair.values['bytesSent'])?.toInt() ?? 0;
      final now = DateTime.now();
      final last = _lastStatsAt;
      if (last != null) {
        final dt = now.difference(last).inMilliseconds / 1000.0;
        if (dt > 0) {
          stats.inKbps = ((inBytes - _lastBytesIn) * 8 / 1000 / dt).clamp(0, 1e6).toDouble();
          stats.outKbps = ((outBytes - _lastBytesOut) * 8 / 1000 / dt).clamp(0, 1e6).toDouble();
        }
      }
      _lastStatsAt = now;
      _lastBytesIn = inBytes;
      _lastBytesOut = outBytes;
      if (stats.localType == 'relay' || stats.remoteType == 'relay') {
        stats.path = 'Qua máy chủ TURN riêng';
      } else if (stats.localType == 'host' && stats.remoteType == 'host') {
        stats.path = 'Trực tiếp trong mạng nội bộ';
      } else {
        stats.path = 'P2P trực tiếp qua Internet';
      }
      _notify();
    } catch (e) {
      debugPrint('getStats lỗi: $e');
    }
  }

  Future<void> _checkOtherCall() async {
    if (isEnded) return;
    final active = await NativeBridge.instance.isOtherCallActive();
    if (active == otherCallActive) return;
    otherCallActive = active;
    if (!active && isAndroid) {
      // Cuộc gọi kia đã xong: lấy lại chế độ âm thanh hội thoại cho cuộc họp.
      await NativeBridge.instance.setAudioManagement(true);
    }
    _notice(active
        ? 'Có cuộc gọi khác đang diễn ra. Cuộc họp vẫn giữ kết nối; hệ thống có thể tạm tắt micro.'
        : 'Cuộc gọi khác đã kết thúc');
    _notify();
  }

  // =====================================================================
  // Kết thúc & dọn dẹp
  // =====================================================================

  Future<void> _end(String? reason, {bool error = false}) async {
    if (isEnded) return _cleanupDone ?? Future.value();
    endReason = reason;
    endedWithError = error;
    _setPhase(MeetingPhase.ended);
    final f = _cleanupDone = _cleanup().catchError((Object e) => debugPrint('Dọn dẹp lỗi: $e'));
    await f;
  }

  Future<void> _cleanup() async {
    _recoveryTimer?.cancel();
    _giveUpTimer?.cancel();
    _callMonitor?.cancel();
    _stopStats();
    await _nativeSub?.cancel();
    await _guestSub?.cancel();
    _guestSub = null;
    await _detachSignaling();
    try {
      await _server?.stop();
    } catch (_) {}
    _server = null;
    final pc = _pc;
    final dc = _dc;
    _pc = null;
    _dc = null;
    _pcGen++;
    try {
      await dc?.close();
    } catch (_) {}
    try {
      await pc?.close();
    } catch (_) {}
    try {
      await pc?.dispose();
    } catch (_) {}
    await _clearRemoteMedia();
    screenOn = false;
    await _disposeScreenStream();
    _setSrc(localRenderer, null);
    for (final s in [_micStream, _camStream]) {
      if (s == null) continue;
      for (final t in s.getTracks()) {
        try {
          await t.stop();
        } catch (_) {}
      }
      try {
        await s.dispose();
      } catch (_) {}
    }
    _micStream = null;
    _camStream = null;
    if (enableNativeExtras) {
      await NativeBridge.instance.stopCallService();
      try {
        await WakelockPlus.disable();
      } catch (_) {}
      if (isAndroid) await NativeBridge.instance.setAudioManagement(true);
    }
    _notify();
  }

  @override
  void dispose() {
    _disposed = true;
    final Future<void> done;
    if (!isEnded) {
      _sendControl({'t': 'bye'});
      _sig?.send({'t': 'bye'}).catchError((_) {});
      done = _end(null);
    } else {
      done = _cleanupDone ?? Future.value();
    }
    // Chỉ giải phóng renderer sau khi dọn dẹp media xong.
    done.whenComplete(() async {
      _renderersDisposed = true;
      for (final r in [localRenderer, remoteCamRenderer, remoteScreenRenderer]) {
        try {
          await r.dispose();
        } catch (_) {}
      }
    });
    _notices.close();
    super.dispose();
  }

  // =====================================================================
  // Tiện ích
  // =====================================================================

  void _setSrc(RTCVideoRenderer r, MediaStream? stream) {
    if (!_renderersReady || _renderersDisposed) return;
    try {
      r.srcObject = stream;
    } catch (e) {
      debugPrint('Gắn video lỗi: $e');
    }
  }

  /// Ứng dụng vào nền/trở lại. iOS dừng camera khi ở nền nên báo cho bên kia
  /// hiện ảnh đại diện thay vì khung hình đứng yên.
  void setAppInBackground(bool background) {
    if (_backgrounded == background) return;
    _backgrounded = background;
    if (isIOS) _sendState();
  }

  void _setPhase(MeetingPhase p) {
    if (phase == p) return;
    if (phase == MeetingPhase.ended) return;
    phase = p;
    _notify();
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  void _notice(String text) {
    if (!_notices.isClosed) _notices.add(text);
  }

  static String _friendly(Object e) {
    if (e is UserFacingError) return e.message;
    if (e is CodeFormatException) return e.message;
    if (e is LanSignalingException) return e.message;
    final s = e.toString();
    if (s.contains('NotAllowedError') || s.contains('Permission')) return 'Chưa được cấp quyền';
    return s.length > 160 ? '${s.substring(0, 160)}…' : s;
  }

  /// Trạng thái tóm tắt (gỡ lỗi/kiểm thử).
  Map<String, dynamic> debugSnapshot() => {
        'phase': phase.name,
        'remotePresent': remotePresent,
        'remoteName': remoteName,
        'remoteMic': remoteMic,
        'remoteCam': remoteCam,
        'remoteScreen': remoteScreen,
        'hasRemoteCamStream': _remoteCamStream != null,
        'hasRemoteScreenStream': _remoteScreenStream != null,
        'remoteCamVideoTracks': _remoteCamStream?.getVideoTracks().length ?? 0,
        'remoteScreenVideoTracks': _remoteScreenStream?.getVideoTracks().length ?? 0,
        'chat': chat.map((c) => '${c.mine ? 'me' : 'peer'}:${c.text}').toList(),
        'mids': [_audioMid, _camMid, _screenMid],
        'path': stats.path,
        'inKbps': stats.inKbps.round(),
        'outKbps': stats.outKbps.round(),
        'screenOn': screenOn,
        'endReason': endReason,
      };
}
