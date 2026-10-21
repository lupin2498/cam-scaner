import 'package:flutter/material.dart';

import '../settings.dart';

class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key, required this.settings});

  final AppSettings settings;

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  AppSettings get s => widget.settings;
  late final _name = TextEditingController(text: s.displayName);
  late final _stun = TextEditingController(text: s.stunServers);
  late final _turnUrl = TextEditingController(text: s.turnUrl);
  late final _turnUser = TextEditingController(text: s.turnUser);
  late final _turnPass = TextEditingController(text: s.turnPass);
  late VideoQuality _quality = s.quality;
  late bool _useStun = s.usePublicStun;

  @override
  void dispose() {
    for (final c in [_name, _stun, _turnUrl, _turnUser, _turnPass]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _save() async {
    s.displayName = _name.text.trim();
    s.quality = _quality;
    s.usePublicStun = _useStun;
    s.stunServers = _stun.text.trim().isEmpty ? AppSettings.defaultStun : _stun.text.trim();
    s.turnUrl = _turnUrl.text.trim();
    s.turnUser = _turnUser.text.trim();
    s.turnPass = _turnPass.text;
    await s.save();
    if (mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(
        title: const Text('Cài đặt'),
        actions: [TextButton(onPressed: _save, child: const Text('Lưu'))],
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            TextField(controller: _name, decoration: const InputDecoration(labelText: 'Tên hiển thị')),
            const SizedBox(height: 20),
            Text('Chất lượng video', style: theme.textTheme.titleSmall),
            RadioGroup<VideoQuality>(
              groupValue: _quality,
              onChanged: (v) => setState(() => _quality = v ?? _quality),
              child: Column(
                children: [
                  for (final q in VideoQuality.values)
                    RadioListTile<VideoQuality>(
                      contentPadding: EdgeInsets.zero,
                      value: q,
                      title: Text(q.label),
                    ),
                ],
              ),
            ),
            const Divider(height: 32),
            Text('Chế độ Internet', style: theme.textTheme.titleSmall),
            const SizedBox(height: 4),
            Text(
              'Chế độ Wi-Fi/Hotspot không dùng máy chủ nào. Chế độ Internet cần STUN để 2 máy biết địa chỉ '
              'Internet của nhau (STUN không nhận hình/tiếng). Nếu 2 máy không thể kết nối trực tiếp '
              '(NAT đối xứng), bạn có thể dùng máy chủ TURN riêng của mình.',
              style: theme.textTheme.bodySmall,
            ),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Dùng STUN'),
              value: _useStun,
              onChanged: (v) => setState(() => _useStun = v),
            ),
            TextField(
              controller: _stun,
              enabled: _useStun,
              minLines: 2,
              maxLines: 4,
              decoration: const InputDecoration(labelText: 'Máy chủ STUN (mỗi dòng một địa chỉ)'),
            ),
            const SizedBox(height: 16),
            TextField(
              controller: _turnUrl,
              decoration: const InputDecoration(
                labelText: 'TURN riêng (tuỳ chọn)',
                hintText: 'turn:my-server.example.com:3478',
              ),
            ),
            const SizedBox(height: 12),
            TextField(controller: _turnUser, decoration: const InputDecoration(labelText: 'TURN username')),
            const SizedBox(height: 12),
            TextField(
              controller: _turnPass,
              obscureText: true,
              decoration: const InputDecoration(labelText: 'TURN mật khẩu'),
            ),
            const SizedBox(height: 24),
            FilledButton(onPressed: _save, child: const Text('Lưu cài đặt')),
          ],
        ),
      ),
    );
  }
}
