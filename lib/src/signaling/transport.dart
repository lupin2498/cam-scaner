import 'dart:async';

/// Kênh báo hiệu dùng để trao đổi SDP/ICE giữa 2 máy.
///
/// [handshake] hoàn tất với tin nhắn đầu tiên nhận được; các tin nhắn sau đó
/// đi vào [messages] (stream một người nghe, có đệm cho tới khi được nghe).
abstract class SignalingTransport {
  Future<Map<String, dynamic>> get handshake;
  Stream<Map<String, dynamic>> get messages;
  Future<void> send(Map<String, dynamic> message);
  Future<void> close();
  bool get isClosed;

  /// Tên hiển thị và id của máy bên kia (điền sau bắt tay).
  String peerName = '';
  String peerId = '';
}

/// Cặp kênh trong bộ nhớ, dùng cho kiểm thử tự động.
class MemoryTransport extends SignalingTransport {
  MemoryTransport._();

  late MemoryTransport _other;
  final _handshake = Completer<Map<String, dynamic>>();
  final _controller = StreamController<Map<String, dynamic>>();
  bool _closed = false;

  static (MemoryTransport, MemoryTransport) pair() {
    final a = MemoryTransport._();
    final b = MemoryTransport._();
    a._other = b;
    b._other = a;
    return (a, b);
  }

  void _deliver(Map<String, dynamic> m) {
    if (_closed) return;
    if (!_handshake.isCompleted) {
      _handshake.complete(m);
    } else {
      _controller.add(m);
    }
  }

  @override
  Future<Map<String, dynamic>> get handshake => _handshake.future;

  @override
  Stream<Map<String, dynamic>> get messages => _controller.stream;

  @override
  bool get isClosed => _closed;

  @override
  Future<void> send(Map<String, dynamic> message) async {
    if (_closed) throw StateError('closed');
    // Sao chép qua JSON-like để tránh chia sẻ tham chiếu.
    final copy = Map<String, dynamic>.from(message);
    scheduleMicrotask(() => _other._deliver(copy));
  }

  @override
  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    if (!_handshake.isCompleted) _handshake.completeError(StateError('closed'));
    unawaited(_controller.close());
    scheduleMicrotask(() => _other.close());
  }
}
