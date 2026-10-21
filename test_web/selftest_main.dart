// Kiểm thử E2E WebRTC thật trên trình duyệt (Chromium) cho MeetingController.
// Build: flutter build web -t test_web/selftest_main.dart
import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';
import 'package:p2p_meet/src/call/meeting_controller.dart';
import 'package:p2p_meet/src/settings.dart';
import 'package:p2p_meet/src/signaling/transport.dart';

void main() {
  runApp(const MaterialApp(home: SelfTestPage()));
}

class SelfTestPage extends StatefulWidget {
  const SelfTestPage({super.key});

  @override
  State<SelfTestPage> createState() => _SelfTestPageState();
}

class _SelfTestPageState extends State<SelfTestPage> {
  final _log = <String>[];
  final _views = <MeetingController>[];
  int _pass = 0;
  int _fail = 0;

  @override
  void initState() {
    super.initState();
    final q = Uri.base.queryParameters;
    WidgetsBinding.instance.addPostFrameCallback((_) => _run(q['suite'] ?? 'all', int.tryParse(q['delay'] ?? '') ?? 0));
  }

  void log(String s) {
    final line = '[${DateTime.now().toIso8601String().substring(11, 23)}] $s';
    // ignore: avoid_print
    print('SELFTEST $line');
    setState(() => _log.add(line));
  }

  void check(String name, bool ok, [Object? detail]) {
    if (ok) {
      _pass++;
      log('PASS $name');
    } else {
      _fail++;
      log('FAIL $name ${detail ?? ''}');
    }
  }

  Future<bool> waitFor(bool Function() cond, {int seconds = 20}) async {
    final end = DateTime.now().add(Duration(seconds: seconds));
    while (DateTime.now().isBefore(end)) {
      if (cond()) return true;
      await Future<void>.delayed(const Duration(milliseconds: 100));
    }
    return cond();
  }

  void show(MeetingController c) => setState(() => _views.add(c));

  MeetingController lanGuest(String name, String id, StreamController<SignalingTransport> hostIn) {
    return MeetingController(
      role: MeetingRole.guest,
      mode: MeetingMode.lan,
      settings: AppSettings.memory(name: name)..deviceId = id,
      enableNativeExtras: false,
      connector: () async {
        final (hostSide, guestSide) = MemoryTransport.pair();
        unawaited(() async {
          final hello = await hostSide.handshake;
          hostSide.peerName = '${hello['name']}';
          hostSide.peerId = '${hello['id']}';
          hostIn.add(hostSide);
        }());
        await guestSide.send({'t': 'join', 'v': 1, 'name': name, 'id': id});
        final reply = await guestSide.handshake;
        if (reply['t'] == 'busy') throw Exception('Phòng đang có người khác tham gia');
        guestSide.peerName = '${reply['name']}';
        return guestSide;
      },
    );
  }

  Future<void> _run(String suite, int answerDelay) async {
    try {
      if (suite == 'all' || suite == 'lan') await _lanSuite();
      if (suite == 'all' || suite == 'internet') await _internetSuite(answerDelay);
    } catch (e, st) {
      check('không có ngoại lệ', false, '$e\n$st');
    }
    log('DONE pass=$_pass fail=$_fail');
    // ignore: avoid_print
    print('SELFTEST_RESULT ${jsonEncode({'pass': _pass, 'fail': _fail})}');
  }

  int vw(RTCVideoRenderer r) => r.videoWidth;

  Future<void> _lanSuite() async {
    log('=== LAN (trickle ICE qua kênh báo hiệu) ===');
    final hostIn = StreamController<SignalingTransport>.broadcast();
    final host = MeetingController(
      role: MeetingRole.host,
      mode: MeetingMode.lan,
      settings: AppSettings.memory(name: 'Chủ Phòng'),
      enableNativeExtras: false,
      guestSource: hostIn.stream,
    );
    show(host);
    await host.start();
    check('chủ phòng ở trạng thái chờ', await waitFor(() => host.phase == MeetingPhase.waiting), host.phase);
    check('chủ phòng có mic+camera giả lập', host.hasMic && host.hasCam);

    var guest = lanGuest('Khách Một', 'G1', hostIn);
    show(guest);
    await guest.start();
    check('2 bên kết nối P2P', await waitFor(() => host.phase == MeetingPhase.connected && guest.phase == MeetingPhase.connected),
        '${host.phase} / ${guest.phase} ${guest.endReason}');
    check('trao đổi trạng thái qua data channel', await waitFor(() => host.remotePresent && guest.remotePresent));
    check('tên người kia đúng', host.remoteName == 'Khách Một' && guest.remoteName == 'Chủ Phòng', '${host.remoteName}/${guest.remoteName}');
    check('mid đúng bố cục audio/cam/screen', host.debugSnapshot()['mids'].toString() == guest.debugSnapshot()['mids'].toString(),
        '${host.debugSnapshot()['mids']} vs ${guest.debugSnapshot()['mids']}');
    check('khách nhận video camera của chủ', await waitFor(() => vw(guest.remoteCamRenderer) > 0), guest.debugSnapshot());
    check('chủ nhận video camera của khách', await waitFor(() => vw(host.remoteCamRenderer) > 0), host.debugSnapshot());
    check('trạng thái mic/cam từ xa = bật', host.remoteMic && host.remoteCam && guest.remoteMic && guest.remoteCam);

    host.sendChat('xin chào');
    guest.sendChat('chào bạn 👋');
    check('chat 2 chiều', await waitFor(() =>
        guest.chat.any((m) => !m.mine && m.text == 'xin chào') && host.chat.any((m) => !m.mine && m.text == 'chào bạn 👋')));
    check('đếm tin chưa đọc', guest.unreadChat == 1, guest.unreadChat);

    guest.toggleMic();
    check('tắt mic -> bên kia thấy', await waitFor(() => !host.remoteMic));
    guest.toggleMic();
    check('bật lại mic', await waitFor(() => host.remoteMic));
    host.toggleCamera();
    check('tắt camera -> bên kia thấy', await waitFor(() => !guest.remoteCam));
    host.toggleCamera();
    check('bật lại camera', await waitFor(() => guest.remoteCam));

    await host.startScreenShare();
    if (host.screenOn) {
      check('khách thấy chủ đang chia sẻ màn hình', await waitFor(() => guest.remoteScreen));
      check('khách nhận video màn hình', await waitFor(() => vw(guest.remoteScreenRenderer) > 0, seconds: 15),
          guest.debugSnapshot());
      check('camera vẫn chạy song song khi chia sẻ', vw(guest.remoteCamRenderer) > 0);
      await host.stopScreenShare();
      check('dừng chia sẻ -> khách hết thấy', await waitFor(() => !guest.remoteScreen));
    } else {
      check('bắt đầu chia sẻ màn hình (getDisplayMedia)', false, 'không lấy được màn hình');
    }

    await guest.startScreenShare();
    if (guest.screenOn) {
      check('chủ thấy khách chia sẻ màn hình', await waitFor(() => host.remoteScreen));
      check('chủ nhận video màn hình của khách', await waitFor(() => vw(host.remoteScreenRenderer) > 0, seconds: 15),
          host.debugSnapshot());
      await guest.stopScreenShare();
      check('khách dừng chia sẻ', await waitFor(() => !host.remoteScreen));
    }

    check('thống kê đường truyền LAN', await waitFor(() => host.stats.path.isNotEmpty, seconds: 8), host.stats.path);
    log('stats: ${host.debugSnapshot()}');

    // Khách thứ 2 vào khi phòng đang bận.
    final intruder = lanGuest('Người Lạ', 'G9', hostIn);
    await intruder.start();
    check('khách thứ 2 bị từ chối vì phòng bận', await waitFor(() => intruder.isEnded),
        '${intruder.phase} ${intruder.endReason}');
    check('cuộc họp chính không bị ảnh hưởng', host.phase == MeetingPhase.connected && guest.phase == MeetingPhase.connected);
    intruder.dispose();

    // Khách rời -> chủ phòng quay lại chờ, khách khác vào lại được.
    await guest.hangUp();
    check('khách rời: khách kết thúc', guest.isEnded);
    check('khách rời: chủ phòng quay lại chờ', await waitFor(() => host.phase == MeetingPhase.waiting && !host.remotePresent),
        host.phase);
    setState(() => _views.remove(guest));
    guest.dispose();

    guest = lanGuest('Khách Hai', 'G2', hostIn);
    show(guest);
    await guest.start();
    check('vào lại phòng sau khi người trước rời', await waitFor(() => host.phase == MeetingPhase.connected && guest.phase == MeetingPhase.connected),
        '${host.phase} / ${guest.phase} ${guest.endReason}');
    check('video sau khi vào lại', await waitFor(() => vw(guest.remoteCamRenderer) > 0 && vw(host.remoteCamRenderer) > 0),
        '${host.debugSnapshot()} ${guest.debugSnapshot()}');
    check('tên khách mới', await waitFor(() => host.remoteName == 'Khách Hai'), host.remoteName);

    await host.hangUp();
    check('chủ kết thúc -> khách nhận thông báo', await waitFor(() => guest.isEnded), guest.phase);
    check('lý do kết thúc rõ ràng', (guest.endReason ?? '').contains('Chủ Phòng'), guest.endReason);
    setState(() => _views.clear());
    host.dispose();
    guest.dispose();
    await hostIn.close();
  }

  Future<void> _internetSuite(int answerDelay) async {
    log('=== Internet (trao đổi mã, ICE không trickle), trễ nhập mã trả lời ${answerDelay}s ===');
    final host = MeetingController(
      role: MeetingRole.host,
      mode: MeetingMode.internet,
      settings: AppSettings.memory(name: 'Chủ Xa')..usePublicStun = false,
      enableNativeExtras: false,
    );
    show(host);
    await host.start();
    check('tạo mã mời', await waitFor(() => host.offerCode != null && host.phase == MeetingPhase.waiting), host.phase);
    log('độ dài mã mời: ${host.offerCode?.length}');
    final guest = MeetingController(
      role: MeetingRole.guest,
      mode: MeetingMode.internet,
      settings: AppSettings.memory(name: 'Khách Xa')..usePublicStun = false,
      enableNativeExtras: false,
      offerText: 'Mời bạn: \n${host.offerCode}\n',
    );
    show(guest);
    await guest.start();
    check('tạo mã trả lời', await waitFor(() => guest.answerCode != null), '${guest.phase} ${guest.endReason}');
    log('độ dài mã trả lời: ${guest.answerCode?.length}');

    // Mã sai/khác phiên phải bị từ chối, không làm hỏng phiên.
    var rejected = false;
    try {
      await host.applyAnswerCode(host.offerCode!);
    } catch (_) {
      rejected = true;
    }
    check('từ chối nhập nhầm mã mời vào ô mã trả lời', rejected);

    if (answerDelay > 0) {
      log('chờ $answerDelay giây trước khi chủ nhập mã trả lời…');
      await Future<void>.delayed(Duration(seconds: answerDelay));
    }
    await host.applyAnswerCode(guest.answerCode!);
    check('kết nối qua mã', await waitFor(() => host.phase == MeetingPhase.connected && guest.phase == MeetingPhase.connected, seconds: 30),
        '${host.phase} / ${guest.phase} / ${guest.hint}');
    check('video 2 chiều', await waitFor(() => vw(guest.remoteCamRenderer) > 0 && vw(host.remoteCamRenderer) > 0));
    host.sendChat('internet ok');
    check('chat qua internet mode', await waitFor(() => guest.chat.any((m) => m.text == 'internet ok')));

    await guest.hangUp();
    check('khách rời -> chủ tạo mã mời mới', await waitFor(() => host.phase == MeetingPhase.waiting && host.offerCode != null, seconds: 15),
        host.phase);
    await host.hangUp();
    setState(() => _views.clear());
    host.dispose();
    guest.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('P2P selftest pass=$_pass fail=$_fail')),
      body: Column(
        children: [
          SizedBox(
            height: 160,
            child: ListView(
              scrollDirection: Axis.horizontal,
              children: [
                for (final c in _views)
                  for (final r in [c.localRenderer, c.remoteCamRenderer, c.remoteScreenRenderer])
                    SizedBox(width: 120, child: RTCVideoView(r)),
              ],
            ),
          ),
          Expanded(
            child: ListView(children: [for (final l in _log) Text(l, style: const TextStyle(fontSize: 11))]),
          ),
        ],
      ),
    );
  }
}
