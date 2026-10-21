import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:p2p_meet/src/codes.dart';

void main() {
  group('ConnectionCode', () {
    test('mã hoá/giải mã khứ hồi', () {
      final payload = {'s': 'abc', 'n': 'Minh Quang', 'sdp': 'v=0\r\n' * 200};
      final code = ConnectionCode.encode(CodeKind.offer, payload);
      expect(code.startsWith('P2PM1.O.'), isTrue);
      final d = ConnectionCode.decode(code);
      expect(d.kind, CodeKind.offer);
      expect(d.payload, payload);
    });

    test('tìm mã nằm lẫn trong tin nhắn, có xuống dòng', () {
      final code = ConnectionCode.encode(CodeKind.answer, {'s': 'x', 'sdp': 'abc'});
      final mid = code.length ~/ 2;
      final msg = 'Mã trả lời của mình nè:\n${code.substring(0, mid)}\n${code.substring(mid)}  cảm ơn';
      final d = ConnectionCode.decode(msg, expect: CodeKind.answer);
      expect(d.payload['sdp'], 'abc');
    });

    test('báo lỗi khi sai loại mã', () {
      final code = ConnectionCode.encode(CodeKind.answer, {'s': 'x'});
      expect(() => ConnectionCode.decode(code, expect: CodeKind.offer), throwsA(isA<CodeFormatException>()));
    });

    test('báo lỗi khi mã bị cắt cụt', () {
      final code = ConnectionCode.encode(CodeKind.offer, {'sdp': 'x' * 500, 'r': Random(1).nextDouble()});
      expect(() => ConnectionCode.decode(code.substring(0, code.length - 10)), throwsA(isA<CodeFormatException>()));
      expect(() => ConnectionCode.decode('xin chào'), throwsA(isA<CodeFormatException>()));
    });

    test('SDP thật được nén gọn', () {
      final sdp = List.generate(120, (i) => 'a=candidate:$i 1 udp 2122260223 192.168.1.$i 5${i}00 typ host generation 0').join('\r\n');
      final code = ConnectionCode.encode(CodeKind.offer, {'sdp': sdp});
      expect(code.length, lessThan(sdp.length));
    });
  });

  group('RoomCode', () {
    test('sinh mã hợp lệ 12 ký tự', () {
      for (var i = 0; i < 200; i++) {
        final c = RoomCode.generate();
        expect(c.length, 12);
        expect(RoomCode.isValid(c), isTrue);
      }
    });

    test('chuẩn hoá cách nhập của người dùng', () {
      const c = 'ABCDEFGHJKMN';
      expect(RoomCode.pretty(c), 'ABCD-EFGH-JKMN');
      expect(RoomCode.isValid('abcd efgh-jkmn'), isTrue);
      expect(RoomCode.normalize(' abcd-efgh-jkmn '), c);
      expect(RoomCode.isValid('ABCD-EFGH-JKM'), isFalse);
      expect(RoomCode.isValid('ABCD-EFGH-JKM0'), isFalse);
    });
  });

  group('LanInvite', () {
    test('khứ hồi qua mã', () {
      final inv = LanInvite(hosts: ['192.168.1.5', '10.0.0.2'], port: 47800, room: 'ABCD-EFGH-JKMN', hostName: 'An');
      final d = ConnectionCode.decode(inv.toCode(), expect: CodeKind.lan);
      final back = LanInvite.fromPayload(d.payload);
      expect(back.hosts, inv.hosts);
      expect(back.port, 47800);
      expect(back.room, 'ABCDEFGHJKMN');
      expect(back.hostName, 'An');
    });

    test('phân tích địa chỉ nhập tay', () {
      expect(LanInvite.parseAddress('192.168.1.5:47801'), (host: '192.168.1.5', port: 47801));
      expect(LanInvite.parseAddress(' 192.168.1.5 '), (host: '192.168.1.5', port: 47800));
      expect(LanInvite.parseAddress('192.168.1.256'), isNull);
      expect(LanInvite.parseAddress('abc'), isNull);
      expect(LanInvite.parseAddress('192.168.1.5:70000'), isNull);
    });
  });
}
