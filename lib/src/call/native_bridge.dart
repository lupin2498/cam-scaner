import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

bool get isAndroid => !kIsWeb && defaultTargetPlatform == TargetPlatform.android;
bool get isIOS => !kIsWeb && defaultTargetPlatform == TargetPlatform.iOS;
bool get isMobile => isAndroid || isIOS;

/// Cầu nối tới mã native (Android: dịch vụ nền + âm thanh; iOS: phát hiện cuộc gọi,
/// thông báo Broadcast Extension).
class NativeBridge {
  NativeBridge._() {
    if (isMobile) _channel.setMethodCallHandler(_onCall);
  }

  static final instance = NativeBridge._();
  static const _channel = MethodChannel('p2pmeet/native');

  final _events = StreamController<String>.broadcast();

  /// Sự kiện từ native: `broadcastStarted`, `broadcastStopped`, `serviceStopped`.
  Stream<String> get events => _events.stream;

  Future<dynamic> _onCall(MethodCall call) async {
    _events.add(call.method);
    return null;
  }

  Future<T?> _invoke<T>(String method, [Map<String, dynamic>? args]) async {
    if (!isMobile) return null;
    try {
      return await _channel.invokeMethod<T>(method, args);
    } catch (e) {
      debugPrint('NativeBridge.$method lỗi: $e');
      return null;
    }
  }

  /// Android: xin quyền camera, micro, thông báo, Bluetooth. Trả về các quyền đã có.
  Future<Set<String>> requestPermissions() async {
    if (!isAndroid) return const {'camera', 'microphone'};
    final r = await _invoke<List<dynamic>>('requestPermissions');
    return r?.map((e) => e.toString()).toSet() ?? const {};
  }

  /// Android: chạy dịch vụ nền để cuộc họp không bị ngắt khi chuyển sang ứng dụng khác.
  /// [screen] = true khi đang chia sẻ màn hình (bắt buộc từ Android 14).
  Future<bool> startCallService({bool screen = false}) async {
    if (!isAndroid) return true;
    final r = await _invoke<bool>('startCallService', {'screen': screen}).timeout(
      const Duration(seconds: 4),
      onTimeout: () => false,
    );
    return r ?? false;
  }

  Future<void> stopCallService() => _invoke<void>('stopCallService');

  /// Có cuộc gọi khác (điện thoại thường, Zalo, Messenger...) đang diễn ra không.
  Future<bool> isOtherCallActive({bool beforeOurCall = false}) async {
    final r = await _invoke<bool>('isOtherCallActive', {'beforeOurCall': beforeOurCall});
    return r ?? false;
  }

  /// Android: bật/tắt việc P2P Meet điều khiển chế độ âm thanh hệ thống.
  /// Tắt khi đang có cuộc gọi khác để không làm ảnh hưởng tới cuộc gọi đó.
  Future<void> setAudioManagement(bool enabled) =>
      _invoke<void>('setAudioManagement', {'enabled': enabled});
}
