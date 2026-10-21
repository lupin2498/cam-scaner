import 'package:flutter/material.dart';

import '../call/meeting_controller.dart';
import '../settings.dart';
import '../signaling/lan.dart';
import 'join_page.dart';
import 'meeting_page.dart';
import 'settings_page.dart';
import 'theme.dart';

class HomePage extends StatefulWidget {
  const HomePage({super.key, required this.settings});

  final AppSettings settings;

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  late final TextEditingController _name = TextEditingController(text: widget.settings.displayName);
  MeetingMode _mode = lanSupported ? MeetingMode.lan : MeetingMode.internet;

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  Future<bool> _saveName() async {
    final n = _name.text.trim();
    if (n.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Hãy nhập tên hiển thị trước')),
      );
      return false;
    }
    if (n != widget.settings.displayName) {
      widget.settings.displayName = n;
      await widget.settings.save();
    }
    return true;
  }

  Future<void> _newMeeting() async {
    if (!await _saveName() || !mounted) return;
    await Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => MeetingPage(
        controller: MeetingController(role: MeetingRole.host, mode: _mode, settings: widget.settings),
      ),
    ));
  }

  Future<void> _join() async {
    if (!await _saveName() || !mounted) return;
    await Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => JoinPage(settings: widget.settings, initialMode: _mode),
    ));
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(
        title: const Text('P2P Meet', style: TextStyle(fontWeight: FontWeight.w700)),
        actions: [
          IconButton(
            tooltip: 'Cài đặt',
            icon: const Icon(Icons.settings_outlined),
            onPressed: () => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => SettingsPage(settings: widget.settings)),
            ),
          ),
        ],
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
          children: [
            TextField(
              controller: _name,
              textCapitalization: TextCapitalization.words,
              decoration: const InputDecoration(
                labelText: 'Tên hiển thị',
                prefixIcon: Icon(Icons.person_outline),
              ),
              onSubmitted: (_) => _saveName(),
            ),
            const SizedBox(height: 20),
            Text('Cách kết nối 2 máy', style: theme.textTheme.titleSmall),
            const SizedBox(height: 8),
            SegmentedButton<MeetingMode>(
              segments: [
                ButtonSegment(
                  value: MeetingMode.lan,
                  icon: const Icon(Icons.wifi),
                  label: const Text('Wi-Fi / Hotspot'),
                  enabled: lanSupported,
                ),
                const ButtonSegment(
                  value: MeetingMode.internet,
                  icon: Icon(Icons.public),
                  label: Text('Internet'),
                ),
              ],
              selected: {_mode},
              onSelectionChanged: (s) => setState(() => _mode = s.first),
            ),
            const SizedBox(height: 8),
            Text(
              _mode == MeetingMode.lan
                  ? '2 máy cùng một Wi-Fi, hoặc một máy bật Hotspot cho máy kia kết nối. '
                      'Chỉ cần quét 1 mã QR. Không cần Internet, không qua máy chủ nào.'
                  : '2 máy ở xa nhau. Trao đổi 2 mã (mời ↔ trả lời) qua QR hoặc tin nhắn. '
                      'Hình/tiếng đi thẳng giữa 2 máy (P2P).',
              style: theme.textTheme.bodySmall,
            ),
            const SizedBox(height: 28),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                _BigAction(
                  color: brandOrange,
                  icon: Icons.videocam_rounded,
                  label: 'Cuộc họp mới',
                  onTap: _newMeeting,
                ),
                _BigAction(
                  color: brandBlue,
                  icon: Icons.add_rounded,
                  label: 'Tham gia',
                  onTap: _join,
                ),
              ],
            ),
            const SizedBox(height: 32),
            const _InfoTile(
              icon: Icons.lock_outline,
              title: 'Riêng tư, đầu cuối',
              text: 'Hình ảnh, âm thanh và màn hình đi trực tiếp giữa 2 điện thoại, mã hoá DTLS-SRTP. '
                  'Không tài khoản, không máy chủ trung gian lưu trữ.',
            ),
            const _InfoTile(
              icon: Icons.call_split,
              title: 'Không ảnh hưởng cuộc gọi khác',
              text: 'Không chiếm đường dây điện thoại, không giữ máy cuộc gọi đang có. '
                  'Cuộc gọi điện thoại/Zalo/Messenger vẫn đến và hoạt động bình thường.',
            ),
            const _InfoTile(
              icon: Icons.screen_share_outlined,
              title: 'Chia sẻ màn hình',
              text: 'Chia sẻ toàn bộ màn hình điện thoại (kể cả khi chuyển sang ứng dụng khác) '
                  'cùng lúc với camera và micro.',
            ),
          ],
        ),
      ),
    );
  }
}

class _BigAction extends StatelessWidget {
  const _BigAction({required this.color, required this.icon, required this.label, required this.onTap});

  final Color color;
  final IconData icon;
  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      label: label,
      excludeSemantics: true,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(22),
        child: Padding(
          padding: const EdgeInsets.all(6),
          child: Column(
            children: [
              Material(
                color: color,
                borderRadius: BorderRadius.circular(22),
                child: SizedBox(width: 76, height: 76, child: Icon(icon, color: Colors.white, size: 38)),
              ),
              const SizedBox(height: 8),
              Text(label, style: Theme.of(context).textTheme.labelLarge),
            ],
          ),
        ),
      ),
    );
  }
}

class _InfoTile extends StatelessWidget {
  const _InfoTile({required this.icon, required this.title, required this.text});

  final IconData icon;
  final String title;
  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: 14),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, color: theme.colorScheme.primary),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: theme.textTheme.titleSmall),
                const SizedBox(height: 2),
                Text(text, style: theme.textTheme.bodySmall),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
