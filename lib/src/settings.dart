import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'codes.dart';

enum VideoQuality {
  low('Tiết kiệm (360p)', 640, 360, 20, 600),
  medium('Cân bằng (540p)', 960, 540, 24, 1200),
  high('Nét (720p)', 1280, 720, 30, 2000);

  const VideoQuality(this.label, this.width, this.height, this.fps, this.maxKbps);
  final String label;
  final int width;
  final int height;
  final int fps;
  final int maxKbps;
}

/// Cài đặt người dùng, lưu cục bộ trên máy.
class AppSettings extends ChangeNotifier {
  AppSettings._(this._prefs);

  final SharedPreferences? _prefs;

  String displayName = '';
  String deviceId = '';
  VideoQuality quality = VideoQuality.medium;

  /// Dùng STUN công cộng để tìm địa chỉ Internet (chỉ trao đổi địa chỉ IP,
  /// không đi qua hình/tiếng). Chỉ áp dụng cho chế độ Internet.
  bool usePublicStun = true;
  String stunServers = defaultStun;

  /// TURN riêng (tuỳ chọn) khi 2 máy không kết nối trực tiếp được (NAT đối xứng).
  String turnUrl = '';
  String turnUser = '';
  String turnPass = '';

  static const defaultStun = 'stun:stun.l.google.com:19302\nstun:stun.cloudflare.com:3478';

  static Future<AppSettings> load() async {
    SharedPreferences? prefs;
    try {
      prefs = await SharedPreferences.getInstance();
    } catch (_) {}
    final s = AppSettings._(prefs);
    s.displayName = prefs?.getString('name') ?? '';
    s.deviceId = prefs?.getString('deviceId') ?? '';
    if (s.deviceId.isEmpty) {
      s.deviceId = randomId(9);
      await prefs?.setString('deviceId', s.deviceId);
    }
    final q = prefs?.getString('quality');
    s.quality = VideoQuality.values.firstWhere((v) => v.name == q, orElse: () => VideoQuality.medium);
    s.usePublicStun = prefs?.getBool('usePublicStun') ?? true;
    s.stunServers = prefs?.getString('stun') ?? defaultStun;
    s.turnUrl = prefs?.getString('turnUrl') ?? '';
    s.turnUser = prefs?.getString('turnUser') ?? '';
    s.turnPass = prefs?.getString('turnPass') ?? '';
    return s;
  }

  /// Bản cài đặt trong bộ nhớ (kiểm thử).
  factory AppSettings.memory({String name = 'Test'}) {
    final s = AppSettings._(null);
    s.displayName = name;
    s.deviceId = randomId(9);
    return s;
  }

  String get effectiveName => displayName.trim().isEmpty ? 'Khách' : displayName.trim();

  Future<void> save() async {
    final p = _prefs;
    if (p != null) {
      await p.setString('name', displayName.trim());
      await p.setString('quality', quality.name);
      await p.setBool('usePublicStun', usePublicStun);
      await p.setString('stun', stunServers);
      await p.setString('turnUrl', turnUrl.trim());
      await p.setString('turnUser', turnUser.trim());
      await p.setString('turnPass', turnPass);
    }
    notifyListeners();
  }

  /// Danh sách ICE server cho chế độ Internet. Chế độ LAN không dùng máy chủ nào.
  List<Map<String, dynamic>> iceServers() {
    final list = <Map<String, dynamic>>[];
    if (usePublicStun) {
      final urls = stunServers
          .split(RegExp(r'[\s,;]+'))
          .map((e) => e.trim())
          .where((e) => e.startsWith('stun:') || e.startsWith('stuns:'))
          .toList();
      if (urls.isNotEmpty) list.add({'urls': urls});
    }
    final turn = turnUrl.trim();
    if (turn.startsWith('turn:') || turn.startsWith('turns:')) {
      list.add({'urls': [turn], 'username': turnUser.trim(), 'credential': turnPass});
    }
    return list;
  }
}
