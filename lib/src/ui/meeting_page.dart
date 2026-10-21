import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';

import '../call/meeting_controller.dart';
import '../call/native_bridge.dart';
import '../codes.dart';
import 'code_widgets.dart';
import 'scanner_page.dart';
import 'theme.dart';

/// Màn hình cuộc họp (kiểu Zoom): video chính, ô tự xem, thanh điều khiển,
/// bảng mời tham gia, chat, thông tin kết nối.
class MeetingPage extends StatefulWidget {
  const MeetingPage({super.key, required this.controller});

  final MeetingController controller;

  @override
  State<MeetingPage> createState() => _MeetingPageState();
}

class _MeetingPageState extends State<MeetingPage> {
  MeetingController get c => widget.controller;
  StreamSubscription<String>? _noticeSub;
  Timer? _clock;
  bool _controlsVisible = true;
  bool _invitePanelOpen = true;
  Offset? _pipOffset;
  final _answerInput = TextEditingController();
  String? _answerError;
  bool _applyingAnswer = false;

  @override
  void initState() {
    super.initState();
    c.addListener(_onChange);
    _noticeSub = c.notices.listen((text) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text(text), duration: const Duration(seconds: 3)));
    });
    _clock = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted && c.phase == MeetingPhase.connected) setState(() {});
    });
    c.start();
  }

  MeetingPhase? _lastPhase;

  void _onChange() {
    if (!mounted) return;
    if (_lastPhase != c.phase) {
      if (c.phase == MeetingPhase.connected) _invitePanelOpen = false;
      if (c.phase == MeetingPhase.waiting) _invitePanelOpen = true;
      _lastPhase = c.phase;
    }
    setState(() {});
  }

  @override
  void dispose() {
    _clock?.cancel();
    _noticeSub?.cancel();
    c.removeListener(_onChange);
    c.dispose();
    _answerInput.dispose();
    super.dispose();
  }

  Future<void> _confirmLeave() async {
    if (c.isEnded) {
      Navigator.of(context).pop();
      return;
    }
    final isHost = c.role == MeetingRole.host;
    final ok = await showModalBottomSheet<bool>(
      context: context,
      showDragHandle: true,
      builder: (ctx) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(isHost ? 'Kết thúc cuộc họp?' : 'Rời cuộc họp?',
                  textAlign: TextAlign.center, style: Theme.of(ctx).textTheme.titleMedium),
              const SizedBox(height: 16),
              FilledButton(
                key: const Key('confirmLeave'),
                style: FilledButton.styleFrom(backgroundColor: dangerRed),
                onPressed: () => Navigator.pop(ctx, true),
                child: Text(isHost ? 'Kết thúc cuộc họp' : 'Rời cuộc họp'),
              ),
              const SizedBox(height: 8),
              OutlinedButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Huỷ')),
            ],
          ),
        ),
      ),
    );
    if (ok == true) {
      await c.hangUp();
      if (mounted) Navigator.of(context).pop();
    }
  }

  // ------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _confirmLeave();
      },
      child: Theme(
        data: buildTheme(Brightness.dark),
        child: Scaffold(
          backgroundColor: meetingBg,
          body: c.isEnded ? _buildEnded() : _buildMeeting(),
        ),
      ),
    );
  }

  Widget _buildEnded() {
    return SafeArea(
      child: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(c.endedWithError ? Icons.error_outline : Icons.call_end_rounded,
                  size: 64, color: c.endedWithError ? Colors.orangeAccent : Colors.white70),
              const SizedBox(height: 16),
              Text('Cuộc họp đã kết thúc',
                  style: Theme.of(context).textTheme.titleLarge?.copyWith(color: Colors.white)),
              if (c.endReason != null) ...[
                const SizedBox(height: 12),
                Text(c.endReason!,
                    key: const Key('endReason'),
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: Colors.white70)),
              ],
              const SizedBox(height: 24),
              FilledButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Về trang chủ')),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildMeeting() {
    return LayoutBuilder(builder: (context, box) {
      return Stack(
        fit: StackFit.expand,
        children: [
          GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: () => setState(() => _controlsVisible = !_controlsVisible),
            child: _buildStage(),
          ),
          if (_showSelfPip) _buildSelfPip(box),
          if (c.screenOn) _buildSharingBanner(),
          AnimatedPositioned(
            duration: const Duration(milliseconds: 200),
            left: 0,
            right: 0,
            top: _controlsVisible ? 0 : -120,
            child: _buildTopBar(),
          ),
          AnimatedPositioned(
            duration: const Duration(milliseconds: 200),
            left: 0,
            right: 0,
            bottom: _controlsVisible ? 0 : -140,
            child: _buildBottomBar(),
          ),
          if (_showInvitePanel) _buildInvitePanel(box),
          if (c.phase == MeetingPhase.reconnecting) _buildReconnecting(),
          if (c.phase == MeetingPhase.preparing) const Center(child: CircularProgressIndicator()),
        ],
      );
    });
  }

  bool get _remoteVisible => c.remotePresent || c.phase == MeetingPhase.connected;

  bool get _showSelfPip => _remoteVisible;

  bool get _showInvitePanel {
    if (!_invitePanelOpen) return false;
    if (c.phase == MeetingPhase.waiting) return true;
    if (c.phase == MeetingPhase.connecting) return true;
    return false;
  }

  // ---------------- Sân khấu chính ----------------

  Widget _buildStage() {
    if (!_remoteVisible) {
      // Chưa có người: hiện camera của mình toàn màn hình (như phòng chờ Zoom).
      return _videoOrAvatar(
        renderer: c.localRenderer,
        show: c.hasCam && c.camOn,
        name: c.localName,
        mirror: c.frontCamera,
        cover: true,
      );
    }
    if (c.remoteScreen) {
      return Stack(
        fit: StackFit.expand,
        children: [
          InteractiveViewer(
            maxScale: 5,
            child: RTCVideoView(
              c.remoteScreenRenderer,
              objectFit: RTCVideoViewObjectFit.RTCVideoViewObjectFitContain,
            ),
          ),
          if (c.remoteCam)
            Positioned(
              left: 12,
              top: MediaQuery.paddingOf(context).top + 64,
              width: 96,
              height: 128,
              child: _tile(
                child: RTCVideoView(c.remoteCamRenderer,
                    objectFit: RTCVideoViewObjectFit.RTCVideoViewObjectFitCover),
                label: c.remoteDisplayName,
                micOff: !c.remoteMic,
              ),
            ),
          Positioned(
            left: 12,
            bottom: 104,
            child: _nameChip('${c.remoteDisplayName} đang chia sẻ màn hình', !c.remoteMic),
          ),
        ],
      );
    }
    return Stack(
      fit: StackFit.expand,
      children: [
        _videoOrAvatar(
          renderer: c.remoteCamRenderer,
          show: c.remoteCam,
          name: c.remoteDisplayName,
          mirror: false,
          cover: true,
        ),
        Positioned(left: 12, bottom: 104, child: _nameChip(c.remoteDisplayName, !c.remoteMic)),
      ],
    );
  }

  Widget _videoOrAvatar({
    required RTCVideoRenderer renderer,
    required bool show,
    required String name,
    required bool mirror,
    required bool cover,
  }) {
    if (!show) return _Avatar(name: name);
    return RTCVideoView(
      renderer,
      mirror: mirror,
      objectFit: cover
          ? RTCVideoViewObjectFit.RTCVideoViewObjectFitCover
          : RTCVideoViewObjectFit.RTCVideoViewObjectFitContain,
    );
  }

  Widget _buildSelfPip(BoxConstraints box) {
    const w = 104.0, h = 148.0;
    final pad = MediaQuery.paddingOf(context);
    final def = Offset(box.maxWidth - w - 12, pad.top + 64);
    var o = _pipOffset ?? def;
    o = Offset(
      o.dx.clamp(8, box.maxWidth - w - 8).toDouble(),
      o.dy.clamp(pad.top + 8, box.maxHeight - h - 100).toDouble(),
    );
    return Positioned(
      left: o.dx,
      top: o.dy,
      width: w,
      height: h,
      child: GestureDetector(
        onPanUpdate: (d) => setState(() => _pipOffset = o + d.delta),
        child: _tile(
          child: (c.hasCam && c.camOn)
              ? RTCVideoView(c.localRenderer,
                  mirror: c.frontCamera, objectFit: RTCVideoViewObjectFit.RTCVideoViewObjectFitCover)
              : _Avatar(name: c.localName, small: true),
          label: 'Bạn',
          micOff: !(c.hasMic && c.micOn),
        ),
      ),
    );
  }

  Widget _tile({required Widget child, required String label, required bool micOff}) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(10),
      child: DecoratedBox(
        decoration: BoxDecoration(color: Colors.black, border: Border.all(color: Colors.white24)),
        child: Stack(
          fit: StackFit.expand,
          children: [
            child,
            Positioned(left: 4, bottom: 4, right: 4, child: _nameChip(label, micOff, small: true)),
          ],
        ),
      ),
    );
  }

  Widget _nameChip(String name, bool micOff, {bool small = false}) {
    return Container(
      padding: EdgeInsets.symmetric(horizontal: small ? 4 : 8, vertical: small ? 2 : 4),
      decoration: BoxDecoration(color: Colors.black54, borderRadius: BorderRadius.circular(6)),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (micOff) Icon(Icons.mic_off, color: Colors.redAccent, size: small ? 12 : 16),
          if (micOff) const SizedBox(width: 3),
          Flexible(
            child: Text(name,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(color: Colors.white, fontSize: small ? 10 : 13)),
          ),
        ],
      ),
    );
  }

  Widget _buildSharingBanner() {
    return Positioned(
      left: 0,
      right: 0,
      top: MediaQuery.paddingOf(context).top + 56,
      child: Center(
        child: Material(
          color: const Color(0xFF00A86B),
          borderRadius: BorderRadius.circular(20),
          child: Padding(
            padding: const EdgeInsets.only(left: 14, right: 4),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.screen_share, color: Colors.white, size: 18),
                const SizedBox(width: 8),
                Text(
                  c.screenLive ? 'Bạn đang chia sẻ màn hình' : 'Đang chờ bắt đầu phát…',
                  style: const TextStyle(color: Colors.white),
                ),
                TextButton(
                  onPressed: c.stopScreenShare,
                  child: const Text('Dừng', style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold)),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  // ---------------- Thanh trên ----------------

  Widget _buildTopBar() {
    final status = switch (c.phase) {
      MeetingPhase.preparing => 'Đang chuẩn bị…',
      MeetingPhase.waiting => 'Đang chờ người tham gia',
      MeetingPhase.connecting => 'Đang kết nối…',
      MeetingPhase.connected => _duration(),
      MeetingPhase.reconnecting => 'Đang kết nối lại…',
      MeetingPhase.ended => 'Đã kết thúc',
    };
    return Container(
      padding: EdgeInsets.only(top: MediaQuery.paddingOf(context).top),
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Colors.black87, Colors.transparent],
        ),
      ),
      child: SizedBox(
        height: 56,
        child: Row(
          children: [
            if (isMobile)
              IconButton(
                tooltip: c.speakerOn ? 'Loa ngoài' : 'Loa trong',
                color: Colors.white,
                icon: Icon(c.speakerOn ? Icons.volume_up_rounded : Icons.hearing_rounded),
                onPressed: c.toggleSpeaker,
              ),
            if (isMobile && c.hasCam)
              IconButton(
                tooltip: 'Đổi camera',
                color: Colors.white,
                icon: const Icon(Icons.cameraswitch_rounded),
                onPressed: c.switchCamera,
              ),
            const Spacer(),
            InkWell(
              onTap: _showInfo,
              borderRadius: BorderRadius.circular(8),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Icon(Icons.lock, size: 14, color: Color(0xFF3DDC84)),
                        const SizedBox(width: 4),
                        Text(
                          c.mode == MeetingMode.lan ? 'P2P · Wi-Fi/Hotspot' : 'P2P · Internet',
                          style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w600),
                        ),
                        const Icon(Icons.expand_more, color: Colors.white70, size: 18),
                      ],
                    ),
                    Text(status,
                        key: const Key('statusText'),
                        style: const TextStyle(color: Colors.white70, fontSize: 12)),
                  ],
                ),
              ),
            ),
            const Spacer(),
            if (c.otherCallActive)
              const Tooltip(
                message: 'Đang có cuộc gọi khác — cuộc họp chạy song song',
                child: Padding(
                  padding: EdgeInsets.all(8),
                  child: Icon(Icons.phone_in_talk_rounded, color: Colors.amber),
                ),
              ),
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: FilledButton(
                key: const Key('leaveButton'),
                style: FilledButton.styleFrom(
                  backgroundColor: dangerRed,
                  minimumSize: const Size(64, 36),
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                ),
                onPressed: _confirmLeave,
                child: Text(c.role == MeetingRole.host ? 'Kết thúc' : 'Rời'),
              ),
            ),
          ],
        ),
      ),
    );
  }

  String _duration() {
    final at = c.connectedAt;
    if (at == null) return 'Đã kết nối';
    final d = DateTime.now().difference(at);
    String two(int v) => v.toString().padLeft(2, '0');
    final h = d.inHours;
    return h > 0 ? '$h:${two(d.inMinutes % 60)}:${two(d.inSeconds % 60)}' : '${two(d.inMinutes)}:${two(d.inSeconds % 60)}';
  }

  // ---------------- Thanh dưới ----------------

  Widget _buildBottomBar() {
    final waitingInvite = c.role == MeetingRole.host &&
        (c.phase == MeetingPhase.waiting || c.phase == MeetingPhase.connecting) ||
        c.role == MeetingRole.guest && c.mode == MeetingMode.internet && c.phase == MeetingPhase.connecting;
    return Container(
      padding: EdgeInsets.only(bottom: MediaQuery.paddingOf(context).bottom + 6, top: 6),
      color: Colors.black.withValues(alpha: 0.85),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceEvenly,
        children: [
          _BarButton(
            key: const Key('micButton'),
            icon: c.hasMic && c.micOn ? Icons.mic_rounded : Icons.mic_off_rounded,
            label: c.hasMic && c.micOn ? 'Tắt tiếng' : 'Bật tiếng',
            color: c.hasMic && c.micOn ? Colors.white : Colors.redAccent,
            onTap: c.toggleMic,
          ),
          _BarButton(
            key: const Key('camButton'),
            icon: c.hasCam && c.camOn ? Icons.videocam_rounded : Icons.videocam_off_rounded,
            label: c.hasCam && c.camOn ? 'Dừng video' : 'Bật video',
            color: c.hasCam && c.camOn ? Colors.white : Colors.redAccent,
            onTap: c.toggleCamera,
          ),
          _BarButton(
            key: const Key('shareButton'),
            icon: c.screenOn ? Icons.stop_screen_share_rounded : Icons.screen_share_rounded,
            label: c.screenOn ? 'Dừng chia sẻ' : 'Chia sẻ',
            color: c.screenOn ? Colors.redAccent : const Color(0xFF3DDC84),
            onTap: c.toggleScreenShare,
          ),
          if (waitingInvite)
            _BarButton(
              icon: Icons.person_add_alt_1_rounded,
              label: 'Mời',
              color: Colors.white,
              onTap: () => setState(() => _invitePanelOpen = true),
            )
          else
            _BarButton(
              key: const Key('chatButton'),
              icon: Icons.chat_bubble_outline_rounded,
              label: 'Trò chuyện',
              color: Colors.white,
              badge: c.unreadChat,
              onTap: _openChat,
            ),
        ],
      ),
    );
  }

  // ---------------- Bảng mời / trao đổi mã ----------------

  Widget _buildInvitePanel(BoxConstraints box) {
    final maxW = box.maxWidth.clamp(0, 520).toDouble();
    final qrSize = (maxW - 72).clamp(160, 340).toDouble();
    return Positioned.fill(
      child: Container(
        color: Colors.black54,
        alignment: Alignment.bottomCenter,
        child: SafeArea(
          child: ConstrainedBox(
            constraints: BoxConstraints(maxWidth: maxW, maxHeight: box.maxHeight * 0.92),
            child: Card(
              margin: const EdgeInsets.all(8),
              color: const Color(0xFF262626),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
              child: SingleChildScrollView(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            _invitePanelTitle(),
                            style: Theme.of(context).textTheme.titleMedium?.copyWith(color: Colors.white),
                          ),
                        ),
                        IconButton(
                          tooltip: 'Thu nhỏ',
                          color: Colors.white70,
                          icon: const Icon(Icons.keyboard_arrow_down_rounded),
                          onPressed: () => setState(() => _invitePanelOpen = false),
                        ),
                      ],
                    ),
                    ..._invitePanelBody(qrSize),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  String _invitePanelTitle() {
    if (c.role == MeetingRole.host) {
      if (c.phase == MeetingPhase.connecting) return 'Đang kết nối…';
      return 'Mời người tham gia';
    }
    if (c.mode == MeetingMode.internet) return 'Gửi mã trả lời cho chủ phòng';
    return 'Đang kết nối tới chủ phòng…';
  }

  List<Widget> _invitePanelBody(double qrSize) {
    const muted = TextStyle(color: Colors.white70);
    if (c.role == MeetingRole.guest) {
      if (c.mode == MeetingMode.lan) {
        return [
          const SizedBox(height: 12),
          const Center(child: CircularProgressIndicator()),
          const SizedBox(height: 16),
          Text(
            c.remoteName.isEmpty ? 'Đang tìm chủ phòng trong mạng Wi-Fi/Hotspot…' : 'Đã gặp ${c.remoteName}, đang thiết lập P2P…',
            textAlign: TextAlign.center,
            style: muted,
          ),
        ];
      }
      final code = c.answerCode;
      return [
        const Text(
          'Bước 2: Gửi mã này cho chủ phòng (cho họ quét QR, hoặc sao chép/chia sẻ qua tin nhắn). '
          'Cuộc họp sẽ tự kết nối khi chủ phòng nhập mã.',
          style: muted,
        ),
        const SizedBox(height: 12),
        if (code == null)
          const Center(child: Padding(padding: EdgeInsets.all(24), child: CircularProgressIndicator()))
        else
          CodeCard(
            key: const Key('answerCodeCard'),
            code: code,
            qrSize: qrSize,
            shareText: 'Mã trả lời P2P Meet của ${c.localName} — dán vào ô "Mã trả lời" trên máy chủ phòng:',
          ),
        if (c.hint != null) ...[
          const SizedBox(height: 12),
          Text(c.hint!, style: const TextStyle(color: Colors.amberAccent)),
        ],
      ];
    }
    // Chủ phòng
    if (c.mode == MeetingMode.lan) {
      final inv = c.lanInvite;
      if (inv == null) return const [Center(child: CircularProgressIndicator())];
      return [
        const Text(
          'Trên điện thoại kia (cùng Wi-Fi, hoặc đang kết nối Hotspot của máy này): mở P2P Meet → Tham gia → Quét mã QR.',
          style: muted,
        ),
        const SizedBox(height: 12),
        CodeCard(
          code: inv.toCode(),
          qrSize: qrSize.clamp(160, 260).toDouble(),
          shareText: 'Mời tham gia P2P Meet (cùng Wi-Fi/Hotspot):',
        ),
        const SizedBox(height: 12),
        _kv('Mã phòng', RoomCode.pretty(inv.room)),
        _kv('Địa chỉ', inv.hosts.map((h) => '$h:${inv.port}').join('\n')),
        if (c.phase == MeetingPhase.connecting) ...[
          const SizedBox(height: 12),
          Text('${c.remoteDisplayName} đang vào phòng…', style: const TextStyle(color: Colors.lightGreenAccent)),
        ],
      ];
    }
    final code = c.offerCode;
    return [
      const Text(
        'Bước 1: Gửi mã mời này cho người tham gia (cho họ quét QR, hoặc sao chép/chia sẻ qua tin nhắn).',
        style: muted,
      ),
      const SizedBox(height: 12),
      if (code == null)
        const Center(child: Padding(padding: EdgeInsets.all(24), child: CircularProgressIndicator()))
      else
        CodeCard(
          key: const Key('offerCodeCard'),
          code: code,
          qrSize: qrSize,
          shareText: 'Mời tham gia P2P Meet của ${c.localName} — mở P2P Meet → Tham gia → dán mã:',
        ),
      const Divider(height: 28, color: Colors.white24),
      const Text('Bước 3: Nhập mã trả lời người tham gia gửi lại', style: TextStyle(color: Colors.white)),
      const SizedBox(height: 10),
      if (c.phase == MeetingPhase.connecting)
        const Text('Đã nhận mã trả lời, đang kết nối P2P…', style: TextStyle(color: Colors.lightGreenAccent))
      else
        CodeInput(
          controller: _answerInput,
          label: 'Mã trả lời',
          onScan: _scanAnswer,
          onSubmit: _applyingAnswer ? null : _submitAnswer,
        ),
      if (_answerError != null) ...[
        const SizedBox(height: 8),
        Text(_answerError!, style: const TextStyle(color: Colors.orangeAccent)),
      ],
    ];
  }

  Widget _kv(String k, String v) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(width: 84, child: Text(k, style: const TextStyle(color: Colors.white60))),
            Expanded(
              child: SelectableText(v,
                  style: const TextStyle(color: Colors.white, fontFamily: 'monospace', fontSize: 15)),
            ),
          ],
        ),
      );

  Future<void> _scanAnswer() async {
    final v = await Navigator.of(context).push<String>(
      MaterialPageRoute(builder: (_) => const ScannerPage(title: 'Quét mã trả lời')),
    );
    if (v == null || !mounted) return;
    _answerInput.text = v;
    await _submitAnswer();
  }

  Future<void> _submitAnswer() async {
    setState(() {
      _answerError = null;
      _applyingAnswer = true;
    });
    try {
      await c.applyAnswerCode(_answerInput.text);
    } on CodeFormatException catch (e) {
      _answerError = e.message;
    } catch (e) {
      _answerError = 'Không áp dụng được mã: $e';
    }
    if (mounted) setState(() => _applyingAnswer = false);
  }

  Widget _buildReconnecting() {
    return const Positioned(
      left: 0,
      right: 0,
      top: 120,
      child: Center(
        child: Card(
          color: Colors.black87,
          child: Padding(
            padding: EdgeInsets.symmetric(horizontal: 16, vertical: 10),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)),
                SizedBox(width: 10),
                Text('Mạng chập chờn, đang kết nối lại…', style: TextStyle(color: Colors.white)),
              ],
            ),
          ),
        ),
      ),
    );
  }

  // ---------------- Chat & thông tin ----------------

  Future<void> _openChat() async {
    c.chatOpen = true;
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => _ChatSheet(controller: c),
    );
    c.chatOpen = false;
  }

  void _showInfo() {
    showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (ctx) => AnimatedBuilder(
        animation: c,
        builder: (ctx, _) {
          final s = c.stats;
          return SafeArea(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Thông tin cuộc họp', style: Theme.of(ctx).textTheme.titleMedium),
                  const SizedBox(height: 12),
                  _info('Kiểu kết nối', c.mode == MeetingMode.lan ? 'Wi-Fi/Hotspot (không máy chủ)' : 'Internet (trao đổi mã)'),
                  _info('Đường truyền', s.path.isEmpty ? '—' : s.path),
                  _info('Mã hoá', 'DTLS-SRTP đầu cuối (WebRTC)'),
                  _info('Độ trễ', s.rttMs == null ? '—' : '${s.rttMs} ms'),
                  _info('Nhận / Gửi', '${s.inKbps.round()} / ${s.outKbps.round()} kbps'),
                  _info('Ứng viên ICE', s.localType.isEmpty ? '—' : '${s.localType} ↔ ${s.remoteType} (${s.protocol})'),
                  _info('Người tham gia', c.remotePresent ? c.remoteDisplayName : '—'),
                  if (c.otherCallActive) _info('Cuộc gọi khác', 'Đang diễn ra — chạy song song'),
                ],
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _info(String k, String v) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 5),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(width: 120, child: Text(k, style: const TextStyle(color: Colors.white60))),
            Expanded(child: Text(v)),
          ],
        ),
      );
}

class _BarButton extends StatelessWidget {
  const _BarButton({
    super.key,
    required this.icon,
    required this.label,
    required this.color,
    required this.onTap,
    this.badge = 0,
  });

  final IconData icon;
  final String label;
  final Color color;
  final VoidCallback onTap;
  final int badge;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(10),
      child: SizedBox(
        width: 80,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 6),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Badge(
                isLabelVisible: badge > 0,
                label: Text('$badge'),
                child: Icon(icon, color: color, size: 28),
              ),
              const SizedBox(height: 4),
              Text(label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(color: Colors.white, fontSize: 11)),
            ],
          ),
        ),
      ),
    );
  }
}

class _Avatar extends StatelessWidget {
  const _Avatar({required this.name, this.small = false});

  final String name;
  final bool small;

  @override
  Widget build(BuildContext context) {
    final initials = name
        .trim()
        .split(RegExp(r'\s+'))
        .where((p) => p.isNotEmpty)
        .take(2)
        .map((p) => p.characters.first.toUpperCase())
        .join();
    return ColoredBox(
      color: const Color(0xFF232323),
      child: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            CircleAvatar(
              radius: small ? 22 : 48,
              backgroundColor: brandBlue,
              child: Text(initials.isEmpty ? '?' : initials,
                  style: TextStyle(color: Colors.white, fontSize: small ? 16 : 34)),
            ),
            if (!small) ...[
              const SizedBox(height: 12),
              Text(name, style: const TextStyle(color: Colors.white, fontSize: 18)),
            ],
          ],
        ),
      ),
    );
  }
}

class _ChatSheet extends StatefulWidget {
  const _ChatSheet({required this.controller});

  final MeetingController controller;

  @override
  State<_ChatSheet> createState() => _ChatSheetState();
}

class _ChatSheetState extends State<_ChatSheet> {
  final _input = TextEditingController();

  @override
  void dispose() {
    _input.dispose();
    super.dispose();
  }

  void _send() {
    widget.controller.sendChat(_input.text);
    _input.clear();
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.controller;
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: SizedBox(
        height: MediaQuery.sizeOf(context).height * 0.6,
        child: AnimatedBuilder(
          animation: c,
          builder: (context, _) => Column(
            children: [
              Expanded(
                child: c.chat.isEmpty
                    ? const Center(child: Text('Chưa có tin nhắn'))
                    : ListView.builder(
                        reverse: true,
                        padding: const EdgeInsets.all(12),
                        itemCount: c.chat.length,
                        itemBuilder: (context, i) {
                          final m = c.chat[c.chat.length - 1 - i];
                          return Align(
                            alignment: m.mine ? Alignment.centerRight : Alignment.centerLeft,
                            child: Container(
                              margin: const EdgeInsets.symmetric(vertical: 4),
                              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                              constraints: const BoxConstraints(maxWidth: 280),
                              decoration: BoxDecoration(
                                color: m.mine ? brandBlue : const Color(0xFF3A3A3A),
                                borderRadius: BorderRadius.circular(14),
                              ),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  if (!m.mine)
                                    Text(m.name, style: const TextStyle(fontSize: 11, color: Colors.white70)),
                                  Text(m.text, style: const TextStyle(color: Colors.white)),
                                ],
                              ),
                            ),
                          );
                        },
                      ),
              ),
              SafeArea(
                top: false,
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(12, 4, 8, 8),
                  child: Row(
                    children: [
                      Expanded(
                        child: TextField(
                          key: const Key('chatInput'),
                          controller: _input,
                          textInputAction: TextInputAction.send,
                          onSubmitted: (_) => _send(),
                          decoration: const InputDecoration(
                            hintText: 'Nhập tin nhắn…',
                            isDense: true,
                          ),
                        ),
                      ),
                      IconButton(
                        key: const Key('chatSend'),
                        icon: const Icon(Icons.send_rounded),
                        onPressed: _send,
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
