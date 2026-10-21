import 'dart:convert';

import 'package:cryptography/cryptography.dart';

import '../codes.dart';

class SecureBoxException implements Exception {
  SecureBoxException(this.message);
  final String message;
  @override
  String toString() => message;
}

/// Mã hoá + xác thực tin nhắn báo hiệu LAN bằng AES-256-GCM.
///
/// Khoá được dẫn xuất (HKDF-SHA256) từ mã phòng chỉ hiển thị trên máy chủ phòng
/// (qua QR), nên máy khác trong cùng mạng không đọc được và không giả mạo được
/// SDP/fingerprint DTLS — kênh media WebRTC vì vậy được xác thực đầu cuối.
/// Mỗi chiều có bộ đếm tăng dần để chống phát lại (replay).
class SecureBox {
  SecureBox._(this._key, this._side);

  static final _aes = AesGcm.with256bits();

  final SecretKey _key;
  final String _side;
  int _sendSeq = 0;
  int _recvSeq = 0;

  /// [side] là 'h' (chủ phòng) hoặc 'g' (khách).
  static Future<SecureBox> fromRoomCode(String roomCode, {required String side}) async {
    final hkdf = Hkdf(hmac: Hmac.sha256(), outputLength: 32);
    final key = await hkdf.deriveKey(
      secretKey: SecretKey(utf8.encode(RoomCode.normalize(roomCode))),
      nonce: utf8.encode('p2pmeet-lan-signaling-v1'),
    );
    return SecureBox._(key, side);
  }

  Future<String> seal(Map<String, dynamic> message) async {
    _sendSeq++;
    final framed = {'d': _side, 's': _sendSeq, 'm': message};
    final box = await _aes.encrypt(utf8.encode(jsonEncode(framed)), secretKey: _key);
    return base64Url.encode(box.concatenation());
  }

  Future<Map<String, dynamic>> open(String sealed) async {
    final List<int> bytes;
    try {
      bytes = base64Url.decode(sealed);
    } catch (_) {
      throw SecureBoxException('Dữ liệu không hợp lệ');
    }
    final SecretBox box;
    try {
      box = SecretBox.fromConcatenation(bytes, nonceLength: _aes.nonceLength, macLength: _aes.macAlgorithm.macLength);
    } catch (_) {
      throw SecureBoxException('Dữ liệu không hợp lệ');
    }
    final List<int> clear;
    try {
      clear = await _aes.decrypt(box, secretKey: _key);
    } on SecretBoxAuthenticationError {
      throw SecureBoxException('Sai mã phòng');
    }
    final framed = jsonDecode(utf8.decode(clear));
    if (framed is! Map<String, dynamic>) throw SecureBoxException('Dữ liệu không hợp lệ');
    final d = framed['d'];
    final s = framed['s'];
    final m = framed['m'];
    if (d == _side || s is! int || m is! Map<String, dynamic>) {
      throw SecureBoxException('Dữ liệu không hợp lệ');
    }
    if (s <= _recvSeq) throw SecureBoxException('Tin nhắn bị phát lại');
    _recvSeq = s;
    return m;
  }
}
