import 'package:flutter/material.dart';

import '../call/meeting_controller.dart';
import '../codes.dart';
import '../settings.dart';
import '../signaling/lan.dart';
import 'code_widgets.dart';
import 'meeting_page.dart';
import 'scanner_page.dart';

/// Tham gia cuộc họp: quét QR / dán mã mời, hoặc nhập địa chỉ + mã phòng (Wi-Fi).
class JoinPage extends StatefulWidget {
  const JoinPage({super.key, required this.settings, required this.initialMode});

  final AppSettings settings;
  final MeetingMode initialMode;

  @override
  State<JoinPage> createState() => _JoinPageState();
}

class _JoinPageState extends State<JoinPage> {
  final _code = TextEditingController();
  final _address = TextEditingController();
  final _room = TextEditingController();
  String? _error;

  @override
  void dispose() {
    _code.dispose();
    _address.dispose();
    _room.dispose();
    super.dispose();
  }

  Future<void> _scan() async {
    final v = await Navigator.of(context).push<String>(
      MaterialPageRoute(builder: (_) => const ScannerPage(title: 'Quét mã mời')),
    );
    if (v == null || !mounted) return;
    _code.text = v;
    _joinWithCode();
  }

  void _joinWithCode() {
    setState(() => _error = null);
    final DecodedCode d;
    try {
      d = ConnectionCode.decode(_code.text);
    } on CodeFormatException catch (e) {
      setState(() => _error = e.message);
      return;
    }
    switch (d.kind) {
      case CodeKind.lan:
        if (!lanSupported) {
          setState(() => _error = 'Thiết bị này không hỗ trợ chế độ Wi-Fi/Hotspot.');
          return;
        }
        try {
          final inv = LanInvite.fromPayload(d.payload);
          _open(MeetingController(
            role: MeetingRole.guest,
            mode: MeetingMode.lan,
            settings: widget.settings,
            lanInviteToJoin: inv,
          ));
        } on CodeFormatException catch (e) {
          setState(() => _error = e.message);
        }
        break;
      case CodeKind.offer:
        _open(MeetingController(
          role: MeetingRole.guest,
          mode: MeetingMode.internet,
          settings: widget.settings,
          offerText: _code.text,
        ));
        break;
      case CodeKind.answer:
        setState(() => _error = 'Đây là mã trả lời — mã này phải nhập trên máy của chủ phòng.');
        break;
    }
  }

  void _joinManual() {
    setState(() => _error = null);
    final addr = LanInvite.parseAddress(_address.text);
    if (addr == null) {
      setState(() => _error = 'Địa chỉ không hợp lệ. Ví dụ: 192.168.1.5:47800');
      return;
    }
    if (!RoomCode.isValid(_room.text)) {
      setState(() => _error = 'Mã phòng gồm 12 ký tự, ví dụ ABCD-EFGH-JKMN');
      return;
    }
    _open(MeetingController(
      role: MeetingRole.guest,
      mode: MeetingMode.lan,
      settings: widget.settings,
      lanInviteToJoin: LanInvite(hosts: [addr.host], port: addr.port, room: _room.text),
    ));
  }

  void _open(MeetingController c) {
    Navigator.of(context).pushReplacement(MaterialPageRoute(builder: (_) => MeetingPage(controller: c)));
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('Tham gia cuộc họp')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            FilledButton.icon(
              style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(56)),
              icon: const Icon(Icons.qr_code_scanner_rounded),
              label: const Text('Quét mã QR của chủ phòng'),
              onPressed: _scan,
            ),
            const SizedBox(height: 20),
            Text('Hoặc dán mã mời nhận qua tin nhắn', style: theme.textTheme.titleSmall),
            const SizedBox(height: 8),
            CodeInput(
              controller: _code,
              label: 'Mã mời',
              onScan: _scan,
              onSubmit: _joinWithCode,
            ),
            if (_error != null) ...[
              const SizedBox(height: 12),
              Text(_error!, style: TextStyle(color: theme.colorScheme.error)),
            ],
            if (lanSupported) ...[
              const SizedBox(height: 28),
              ExpansionTile(
                initiallyExpanded: false,
                tilePadding: EdgeInsets.zero,
                title: const Text('Nhập thủ công (cùng Wi-Fi/Hotspot)'),
                subtitle: const Text('Dùng khi không quét được QR'),
                childrenPadding: const EdgeInsets.only(bottom: 8),
                children: [
                  TextField(
                    controller: _address,
                    keyboardType: const TextInputType.numberWithOptions(decimal: true),
                    decoration: const InputDecoration(
                      labelText: 'Địa chỉ chủ phòng',
                      hintText: '192.168.1.5:47800',
                    ),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _room,
                    textCapitalization: TextCapitalization.characters,
                    decoration: const InputDecoration(labelText: 'Mã phòng', hintText: 'ABCD-EFGH-JKMN'),
                  ),
                  const SizedBox(height: 12),
                  FilledButton(onPressed: _joinManual, child: const Text('Tham gia')),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }
}
