import 'dart:async';
import 'dart:io';

import 'secure_box.dart';
import 'transport.dart';

const _path = '/p2pmeet';
const _closeWrongCode = 4001;
const _closeBusy = 4002;

class LanSignalingException implements Exception {
  LanSignalingException(this.message);
  final String message;
  @override
  String toString() => message;
}

bool get lanSupported => true;

/// Kênh báo hiệu qua WebSocket trong mạng LAN, mọi tin nhắn được mã hoá bằng [SecureBox].
class WsTransport extends SignalingTransport {
  WsTransport(this._ws, this._box) {
    _sub = _ws.listen(
      (data) => _queue = _queue.then((_) => _onData(data)).catchError((_) {}),
      onDone: () => _queue = _queue.then((_) => _shutdown()).catchError((_) {}),
      onError: (_) => _queue = _queue.then((_) => _shutdown()).catchError((_) {}),
      cancelOnError: true,
    );
  }

  final WebSocket _ws;
  final SecureBox _box;
  late final StreamSubscription _sub;
  final _handshake = Completer<Map<String, dynamic>>();
  final _controller = StreamController<Map<String, dynamic>>();
  // Giải mã tuần tự để giữ đúng thứ tự tin nhắn.
  Future<void> _queue = Future.value();
  Future<void> _sendQueue = Future.value();
  bool _closed = false;

  int? get closeCode => _ws.closeCode;

  Future<void> _onData(dynamic data) async {
    if (_closed || data is! String) return;
    final Map<String, dynamic> m;
    try {
      m = await _box.open(data);
    } on SecureBoxException catch (e) {
      if (!_handshake.isCompleted) _handshake.completeError(LanSignalingException(e.message));
      await _ws.close(_closeWrongCode, 'auth');
      await _shutdown();
      return;
    }
    if (!_handshake.isCompleted) {
      _handshake.complete(m);
    } else if (!_controller.isClosed) {
      _controller.add(m);
    }
  }

  Future<void> _shutdown() async {
    if (_closed) return;
    _closed = true;
    if (!_handshake.isCompleted) {
      _handshake.completeError(LanSignalingException(_ws.closeCode == _closeWrongCode
          ? 'Sai mã phòng'
          : _ws.closeCode == _closeBusy
              ? 'Phòng đang bận'
              : 'Mất kết nối tới máy bên kia'));
    }
    unawaited(_controller.close());
  }

  @override
  Future<Map<String, dynamic>> get handshake => _handshake.future;

  @override
  Stream<Map<String, dynamic>> get messages => _controller.stream;

  @override
  bool get isClosed => _closed;

  @override
  Future<void> send(Map<String, dynamic> message) {
    final done = _sendQueue.then((_) async {
      if (_closed) throw LanSignalingException('Kênh báo hiệu đã đóng');
      final sealed = await _box.seal(message);
      _ws.add(sealed);
    });
    _sendQueue = done.catchError((_) {});
    return done;
  }

  Future<void> closeWith(int code, String reason) async {
    try {
      await _ws.close(code, reason);
    } catch (_) {}
    await _shutdown();
  }

  @override
  Future<void> close() async {
    try {
      await _sendQueue.timeout(const Duration(seconds: 2));
    } catch (_) {}
    try {
      await _ws.close(WebSocketStatus.normalClosure);
    } catch (_) {}
    await _sub.cancel();
    await _shutdown();
  }
}

/// Máy chủ báo hiệu nhỏ chạy ngay trên điện thoại chủ phòng (không cần máy chủ ngoài).
class LanHostServer {
  LanHostServer({required this.roomCode});

  final String roomCode;
  HttpServer? _server;
  final _guests = StreamController<SignalingTransport>.broadcast();

  int get port => _server?.port ?? 0;
  Stream<SignalingTransport> get guests => _guests.stream;

  Future<void> start({int preferredPort = 47800}) async {
    HttpServer? server;
    for (final p in [preferredPort, preferredPort + 1, preferredPort + 2, 0]) {
      try {
        server = await HttpServer.bind(InternetAddress.anyIPv4, p);
        break;
      } on SocketException {
        continue;
      }
    }
    if (server == null) throw LanSignalingException('Không mở được cổng mạng');
    _server = server;
    server.idleTimeout = const Duration(seconds: 30);
    server.listen(_onRequest, onError: (_) {});
  }

  Future<void> _onRequest(HttpRequest req) async {
    if (req.uri.path != _path || !WebSocketTransformer.isUpgradeRequest(req)) {
      req.response.statusCode = HttpStatus.notFound;
      req.response.write('P2P Meet');
      await req.response.close();
      return;
    }
    final WebSocket ws;
    try {
      ws = await WebSocketTransformer.upgrade(req);
    } catch (_) {
      return;
    }
    ws.pingInterval = const Duration(seconds: 5);
    final box = await SecureBox.fromRoomCode(roomCode, side: 'h');
    final t = WsTransport(ws, box);
    try {
      final hello = await t.handshake.timeout(const Duration(seconds: 15));
      if (hello['t'] != 'join') throw LanSignalingException('bad hello');
      t.peerName = (hello['name'] ?? '').toString();
      t.peerId = (hello['id'] ?? '').toString();
      if (_guests.isClosed) {
        await t.close();
        return;
      }
      _guests.add(t);
    } catch (_) {
      await t.close();
    }
  }

  Future<void> stop() async {
    await _guests.close();
    await _server?.close(force: true);
    _server = null;
  }
}

class LanClient {
  /// Kết nối tới chủ phòng: thử song song mọi địa chỉ trong lời mời, thử lại
  /// cho tới [timeout] (iOS có thể cần thời gian để người dùng cho phép
  /// "Mạng cục bộ").
  static Future<WsTransport> connect({
    required List<String> hosts,
    required int port,
    required String roomCode,
    required String name,
    required String guestId,
    Duration timeout = const Duration(seconds: 25),
  }) async {
    final deadline = DateTime.now().add(timeout);
    while (DateTime.now().isBefore(deadline)) {
      try {
        final ws = await _connectAny(hosts, port);
        ws.pingInterval = const Duration(seconds: 5);
        final box = await SecureBox.fromRoomCode(roomCode, side: 'g');
        final t = WsTransport(ws, box);
        await t.send({'t': 'join', 'v': 1, 'name': name, 'id': guestId});
        final reply = await t.handshake.timeout(const Duration(seconds: 10), onTimeout: () {
          throw LanSignalingException('Chủ phòng không phản hồi (có thể sai mã phòng)');
        });
        if (reply['t'] == 'busy') {
          await t.close();
          throw LanSignalingException('Phòng đang có người khác tham gia');
        }
        if (reply['t'] != 'welcome') {
          await t.close();
          throw LanSignalingException('Phản hồi không hợp lệ từ chủ phòng');
        }
        t.peerName = (reply['name'] ?? '').toString();
        t.peerId = (reply['id'] ?? '').toString();
        return t;
      } on LanSignalingException {
        rethrow;
      } catch (_) {
        await Future<void>.delayed(const Duration(milliseconds: 800));
      }
    }
    throw LanSignalingException(
        'Không kết nối được tới chủ phòng. Hãy chắc chắn 2 máy cùng Wi-Fi/Hotspot '
        'và đã cho phép "Mạng cục bộ" (iPhone).');
  }

  static Future<WebSocket> _connectAny(List<String> hosts, int port) {
    final completer = Completer<WebSocket>();
    var pending = hosts.length;
    if (pending == 0) return Future.error(LanSignalingException('Thiếu địa chỉ chủ phòng'));
    for (final h in hosts) {
      final client = HttpClient()..connectionTimeout = const Duration(seconds: 4);
      WebSocket.connect('ws://$h:$port$_path', customClient: client)
          .timeout(const Duration(seconds: 6))
          .then((ws) {
        if (completer.isCompleted) {
          ws.close();
        } else {
          completer.complete(ws);
        }
      }).catchError((Object e) {
        pending--;
        if (pending == 0 && !completer.isCompleted) completer.completeError(e);
      });
    }
    return completer.future;
  }
}

/// Liệt kê IPv4 trong mạng cục bộ (Wi-Fi, Hotspot, Ethernet), bỏ qua mạng di động.
Future<List<String>> localLanAddresses() async {
  final List<NetworkInterface> ifs;
  try {
    ifs = await NetworkInterface.list(
      type: InternetAddressType.IPv4,
      includeLoopback: false,
      includeLinkLocal: false,
    );
  } catch (_) {
    return const [];
  }
  const cellular = ['rmnet', 'pdp_ip', 'ccmni', 'clat', 'v4-', 'dummy', 'ppp', 'ipsec', 'utun', 'tun', 'radio'];
  final scored = <(int, String)>[];
  for (final itf in ifs) {
    final name = itf.name.toLowerCase();
    if (cellular.any(name.startsWith)) continue;
    for (final addr in itf.addresses) {
      final ip = addr.address;
      final parts = ip.split('.').map(int.tryParse).toList();
      if (parts.length != 4 || parts.any((p) => p == null)) continue;
      final a = parts[0]!, b = parts[1]!;
      if (a == 127 || (a == 169 && b == 254)) continue;
      var score = 0;
      if (a == 192 && b == 168) {
        score = 30;
      } else if (a == 172 && b >= 16 && b <= 31) {
        score = 20;
      } else if (a == 10) {
        score = 15;
      } else if (a == 100 && b >= 64 && b <= 127) {
        score = 1;
      } else {
        score = 5;
      }
      if (name.startsWith('wlan') || name.startsWith('en') || name.startsWith('ap') ||
          name.startsWith('swlan') || name.startsWith('bridge') || name.startsWith('eth')) {
        score += 10;
      }
      scored.add((score, ip));
    }
  }
  scored.sort((x, y) => y.$1.compareTo(x.$1));
  return <String>{for (final s in scored) s.$2}.toList();
}
