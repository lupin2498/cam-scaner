import 'dart:async';

import 'transport.dart';

/// Bản giả cho nền tảng không có dart:io (web): không hỗ trợ chế độ LAN.
class LanHostServer {
  LanHostServer({required this.roomCode});
  final String roomCode;
  int get port => 0;
  Stream<SignalingTransport> get guests => const Stream.empty();
  Future<void> start({int preferredPort = 47800}) async =>
      throw UnsupportedError('Chế độ Wi-Fi/Hotspot không hỗ trợ trên nền tảng này');
  Future<void> stop() async {}
}

class LanClient {
  static Future<SignalingTransport> connect({
    required List<String> hosts,
    required int port,
    required String roomCode,
    required String name,
    required String guestId,
    Duration timeout = const Duration(seconds: 25),
  }) async =>
      throw UnsupportedError('Chế độ Wi-Fi/Hotspot không hỗ trợ trên nền tảng này');
}

class LanSignalingException implements Exception {
  LanSignalingException(this.message);
  final String message;
  @override
  String toString() => message;
}

Future<List<String>> localLanAddresses() async => const [];

bool get lanSupported => false;
