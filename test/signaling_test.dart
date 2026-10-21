import 'dart:async';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:p2p_meet/src/codes.dart';
import 'package:p2p_meet/src/signaling/lan_io.dart';
import 'package:p2p_meet/src/signaling/secure_box.dart';
import 'package:p2p_meet/src/signaling/transport.dart';

void main() {
  group('SecureBox', () {
    test('mã hoá/giải mã giữa chủ phòng và khách', () async {
      final h = await SecureBox.fromRoomCode('ABCD-EFGH-JKMN', side: 'h');
      final g = await SecureBox.fromRoomCode('abcdefghjkmn', side: 'g');
      final sealed = await g.seal({'t': 'join', 'name': 'Bình'});
      expect(sealed.contains('Bình'), isFalse);
      expect(await h.open(sealed), {'t': 'join', 'name': 'Bình'});
    });

    test('sai mã phòng bị từ chối', () async {
      final h = await SecureBox.fromRoomCode('ABCD-EFGH-JKMN', side: 'h');
      final g = await SecureBox.fromRoomCode('ABCD-EFGH-JKMP', side: 'g');
      final sealed = await g.seal({'t': 'join'});
      expect(() => h.open(sealed), throwsA(isA<SecureBoxException>()));
    });

    test('chống phát lại và phản xạ', () async {
      final h = await SecureBox.fromRoomCode('ABCD-EFGH-JKMN', side: 'h');
      final g = await SecureBox.fromRoomCode('ABCD-EFGH-JKMN', side: 'g');
      final m1 = await g.seal({'n': 1});
      final m2 = await g.seal({'n': 2});
      expect(await h.open(m1), {'n': 1});
      expect(await h.open(m2), {'n': 2});
      expect(() => h.open(m1), throwsA(isA<SecureBoxException>()));
      // Tin của chính chủ phòng bị gửi ngược lại cũng bị loại.
      final own = await h.seal({'t': 'x'});
      expect(() => h.open(own), throwsA(isA<SecureBoxException>()));
    });

    test('dữ liệu bị sửa bị phát hiện', () async {
      final h = await SecureBox.fromRoomCode('ABCD-EFGH-JKMN', side: 'h');
      final g = await SecureBox.fromRoomCode('ABCD-EFGH-JKMN', side: 'g');
      final sealed = await g.seal({'t': 'offer', 'sdp': 'abc'});
      final chars = sealed.split('');
      final i = chars.length - 5;
      chars[i] = chars[i] == 'A' ? 'B' : 'A';
      expect(() => h.open(chars.join()), throwsA(isA<SecureBoxException>()));
    });
  });

  group('LAN signaling (socket thật)', () {
    late LanHostServer server;
    late String room;

    setUp(() async {
      room = RoomCode.generate();
      server = LanHostServer(roomCode: room);
      await server.start(preferredPort: 0);
    });

    tearDown(() => server.stop());

    test('khách kết nối, bắt tay, trao đổi tin nhắn 2 chiều', () async {
      final guests = <SignalingTransport>[];
      final sub = server.guests.listen((t) async {
        guests.add(t);
        await t.send({'t': 'welcome', 'name': 'Chủ', 'id': 'H1'});
        t.messages.listen((m) => t.send({'t': 'echo', 'v': m['v']}));
      });
      final client = await LanClient.connect(
        hosts: ['10.255.255.1', '127.0.0.1'],
        port: server.port,
        roomCode: room,
        name: 'Khách',
        guestId: 'G1',
        timeout: const Duration(seconds: 10),
      );
      expect(client.peerName, 'Chủ');
      expect(guests.single.peerName, 'Khách');
      expect(guests.single.peerId, 'G1');
      final replies = <int>[];
      final done = Completer<void>();
      client.messages.listen((m) {
        replies.add(m['v'] as int);
        if (replies.length == 20) done.complete();
      });
      for (var i = 0; i < 20; i++) {
        await client.send({'t': 'ping', 'v': i});
      }
      await done.future.timeout(const Duration(seconds: 5));
      expect(replies, List.generate(20, (i) => i));
      await client.close();
      await sub.cancel();
    });

    test('sai mã phòng: khách nhận lỗi, chủ phòng không nhận khách', () async {
      var accepted = 0;
      final sub = server.guests.listen((_) => accepted++);
      await expectLater(
        LanClient.connect(
          hosts: ['127.0.0.1'],
          port: server.port,
          roomCode: RoomCode.generate(),
          name: 'Kẻ lạ',
          guestId: 'X',
          timeout: const Duration(seconds: 8),
        ),
        throwsA(isA<LanSignalingException>()),
      );
      expect(accepted, 0);
      await sub.cancel();
    });

    test('phòng bận: khách thứ hai bị từ chối', () async {
      final sub = server.guests.listen((t) async {
        if (t.peerId == 'G1') {
          await t.send({'t': 'welcome', 'name': 'Chủ', 'id': 'H1'});
        } else {
          await t.send({'t': 'busy'});
          await t.close();
        }
      });
      final first = await LanClient.connect(
          hosts: ['127.0.0.1'], port: server.port, roomCode: room, name: 'A', guestId: 'G1');
      await expectLater(
        LanClient.connect(hosts: ['127.0.0.1'], port: server.port, roomCode: room, name: 'B', guestId: 'G2'),
        throwsA(isA<LanSignalingException>().having((e) => e.message, 'message', contains('Phòng'))),
      );
      await first.close();
      await sub.cancel();
    });

    test('đường dẫn lạ trả 404, không làm sập máy chủ', () async {
      final client = HttpClient();
      final req = await client.get('127.0.0.1', server.port, '/');
      final res = await req.close();
      expect(res.statusCode, 404);
      await res.drain<void>();
      client.close();
    });

    test('không có máy chủ: báo lỗi rõ ràng sau thời gian chờ', () async {
      await expectLater(
        LanClient.connect(
          hosts: ['127.0.0.1'],
          port: 1,
          roomCode: room,
          name: 'A',
          guestId: 'G',
          timeout: const Duration(seconds: 2),
        ),
        throwsA(isA<LanSignalingException>()),
      );
    });
  });

  test('liệt kê địa chỉ LAN không lỗi', () async {
    final list = await localLanAddresses();
    for (final ip in list) {
      expect(ip.split('.').length, 4);
      expect(ip.startsWith('127.'), isFalse);
    }
  });

  test('MemoryTransport giữ thứ tự và đóng 2 đầu', () async {
    final (a, b) = MemoryTransport.pair();
    await a.send({'t': 'join'});
    expect(await b.handshake, {'t': 'join'});
    await b.send({'t': 'welcome'});
    expect(await a.handshake, {'t': 'welcome'});
    final got = <int>[];
    final sub = b.messages.listen((m) => got.add(m['i'] as int));
    for (var i = 0; i < 5; i++) {
      await a.send({'i': i});
    }
    await Future<void>.delayed(const Duration(milliseconds: 20));
    expect(got, [0, 1, 2, 3, 4]);
    await a.close();
    await Future<void>.delayed(const Duration(milliseconds: 20));
    expect(b.isClosed, isTrue);
    await sub.cancel();
  });
}
