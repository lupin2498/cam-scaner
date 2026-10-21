// Chạy ứng dụng thật với cây ngữ nghĩa (semantics) bật sẵn để Playwright điều khiển giao diện.
import 'package:flutter/rendering.dart';
import 'package:flutter/widgets.dart';
import 'package:p2p_meet/main.dart' as app;

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  SemanticsBinding.instance.ensureSemantics();
  await app.main();
}
