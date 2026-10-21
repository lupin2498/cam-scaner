// Chạy ứng dụng thật với cây ngữ nghĩa (semantics) bật sẵn để Playwright điều khiển giao diện.
// ?platform=android: giả lập bố cục điện thoại Android (hiện nút loa, đổi camera…).
import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/widgets.dart';
import 'package:p2p_meet/main.dart' as app;

Future<void> main() async {
  if (Uri.base.queryParameters['platform'] == 'android') {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
  }
  WidgetsFlutterBinding.ensureInitialized();
  SemanticsBinding.instance.ensureSemantics();
  await app.main();
}
