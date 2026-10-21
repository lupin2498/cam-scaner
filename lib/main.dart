import 'package:flutter/material.dart';

import 'src/settings.dart';
import 'src/ui/home_page.dart';
import 'src/ui/theme.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final settings = await AppSettings.load();
  runApp(P2PMeetApp(settings: settings));
}

class P2PMeetApp extends StatelessWidget {
  const P2PMeetApp({super.key, required this.settings});

  final AppSettings settings;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'P2P Meet',
      debugShowCheckedModeBanner: false,
      theme: buildTheme(Brightness.light),
      darkTheme: buildTheme(Brightness.dark),
      home: HomePage(settings: settings),
    );
  }
}
