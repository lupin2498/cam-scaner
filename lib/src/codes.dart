import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:archive/archive.dart';

/// Loại mã trao đổi giữa 2 máy.
enum CodeKind {
  /// Lời mời cùng mạng Wi-Fi/Hotspot (địa chỉ máy chủ phòng + mã phòng).
  lan('L'),

  /// Lời mời qua Internet (SDP offer của chủ phòng).
  offer('O'),

  /// Mã trả lời qua Internet (SDP answer của người tham gia).
  answer('A');

  const CodeKind(this.tag);
  final String tag;

  static CodeKind? fromTag(String tag) {
    for (final k in CodeKind.values) {
      if (k.tag == tag) return k;
    }
    return null;
  }
}

class DecodedCode {
  DecodedCode(this.kind, this.payload);
  final CodeKind kind;
  final Map<String, dynamic> payload;
}

class CodeFormatException implements Exception {
  CodeFormatException(this.message);
  final String message;
  @override
  String toString() => message;
}

/// Mã hoá/giải mã các mã kết nối dạng `P2PM1.<loại>.<base64url(deflate(json))>`.
///
/// Mã có thể được chia sẻ qua QR, sao chép/dán hoặc gửi qua ứng dụng nhắn tin;
/// khi giải mã sẽ tự tìm mã nằm lẫn trong đoạn tin nhắn và bỏ qua xuống dòng.
class ConnectionCode {
  static const prefix = 'P2PM1';
  static final _pattern = RegExp(r'P2PM1\.([LOA])\.([A-Za-z0-9_\-]+)');

  static String encode(CodeKind kind, Map<String, dynamic> payload) {
    final raw = utf8.encode(jsonEncode(payload));
    final packed = ZLibEncoder().encodeBytes(raw, level: 9);
    final body = base64Url.encode(packed).replaceAll('=', '');
    return '$prefix.${kind.tag}.$body';
  }

  static DecodedCode decode(String text, {CodeKind? expect}) {
    final compact = text.replaceAll(RegExp(r'\s+'), '');
    final m = _pattern.firstMatch(compact);
    if (m == null) {
      throw CodeFormatException('Không tìm thấy mã P2P Meet hợp lệ.');
    }
    final kind = CodeKind.fromTag(m.group(1)!)!;
    if (expect != null && kind != expect) {
      throw CodeFormatException(_wrongKindMessage(expect, kind));
    }
    try {
      var body = m.group(2)!;
      body = body.padRight((body.length + 3) ~/ 4 * 4, '=');
      final packed = base64Url.decode(body);
      final raw = ZLibDecoder().decodeBytes(packed);
      final payload = jsonDecode(utf8.decode(raw));
      if (payload is! Map<String, dynamic>) {
        throw const FormatException('payload');
      }
      return DecodedCode(kind, payload);
    } catch (_) {
      throw CodeFormatException('Mã bị thiếu hoặc sai. Hãy sao chép lại toàn bộ mã.');
    }
  }

  static String _wrongKindMessage(CodeKind expect, CodeKind got) {
    String name(CodeKind k) => switch (k) {
          CodeKind.lan => 'mã mời Wi-Fi/Hotspot',
          CodeKind.offer => 'mã mời qua Internet',
          CodeKind.answer => 'mã trả lời',
        };
    return 'Đây là ${name(got)}, cần ${name(expect)}.';
  }
}

/// Mã phòng 12 ký tự (60 bit ngẫu nhiên) dùng để xác thực và mã hoá kênh báo hiệu LAN.
class RoomCode {
  // Bảng chữ base32 bỏ các ký tự dễ nhầm (0/O, 1/I/L, U).
  static const alphabet = 'ABCDEFGHJKMNPQRSTVWXYZ23456789';

  static String generate([Random? random]) {
    final r = random ?? Random.secure();
    final b = StringBuffer();
    for (var i = 0; i < 12; i++) {
      b.write(alphabet[r.nextInt(alphabet.length)]);
    }
    return b.toString();
  }

  /// Chuẩn hoá mã người dùng nhập: bỏ dấu cách/gạch, viết hoa.
  static String normalize(String input) =>
      input.toUpperCase().replaceAll(RegExp(r'[\s\-_.]'), '');

  static bool isValid(String input) {
    final n = normalize(input);
    if (n.length != 12) return false;
    for (final c in n.split('')) {
      if (!alphabet.contains(c)) return false;
    }
    return true;
  }

  /// Hiển thị dạng `ABCD-EFGH-JKMN`.
  static String pretty(String code) {
    final n = normalize(code);
    final parts = <String>[];
    for (var i = 0; i < n.length; i += 4) {
      parts.add(n.substring(i, min(i + 4, n.length)));
    }
    return parts.join('-');
  }
}

/// Lời mời LAN: danh sách IP của chủ phòng, cổng và mã phòng.
class LanInvite {
  LanInvite({required this.hosts, required this.port, required this.room, this.hostName = ''});

  final List<String> hosts;
  final int port;
  final String room;
  final String hostName;

  String toCode() => ConnectionCode.encode(CodeKind.lan, {
        'h': hosts,
        'p': port,
        'k': RoomCode.normalize(room),
        'n': hostName,
      });

  static LanInvite fromPayload(Map<String, dynamic> p) {
    final hosts = (p['h'] as List?)?.map((e) => e.toString()).toList() ?? const <String>[];
    final port = p['p'];
    final room = p['k'];
    if (hosts.isEmpty || port is! int || room is! String || !RoomCode.isValid(room)) {
      throw CodeFormatException('Mã mời Wi-Fi/Hotspot không hợp lệ.');
    }
    return LanInvite(hosts: hosts, port: port, room: room, hostName: (p['n'] ?? '').toString());
  }

  /// Phân tích địa chỉ người dùng nhập tay, ví dụ `192.168.1.5:47800`.
  static ({String host, int port})? parseAddress(String input, {int defaultPort = 47800}) {
    final s = input.trim();
    if (s.isEmpty) return null;
    final m = RegExp(r'^([0-9]{1,3}(?:\.[0-9]{1,3}){3})(?::([0-9]{1,5}))?$').firstMatch(s);
    if (m == null) return null;
    final parts = m.group(1)!.split('.').map(int.parse).toList();
    if (parts.any((p) => p > 255)) return null;
    final port = m.group(2) == null ? defaultPort : int.parse(m.group(2)!);
    if (port <= 0 || port > 65535) return null;
    return (host: m.group(1)!, port: port);
  }
}

/// Tạo id ngẫu nhiên ngắn cho phiên.
String randomId([int bytes = 8]) {
  final r = Random.secure();
  final data = Uint8List.fromList(List.generate(bytes, (_) => r.nextInt(256)));
  return base64Url.encode(data).replaceAll('=', '');
}
