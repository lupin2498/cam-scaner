import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:share_plus/share_plus.dart';

/// Hiển thị một mã kết nối: QR + nút sao chép / chia sẻ.
class CodeCard extends StatelessWidget {
  const CodeCard({
    super.key,
    required this.code,
    required this.shareText,
    this.qrSize = 260,
    this.showText = false,
  });

  final String code;
  final String shareText;
  final double qrSize;
  final bool showText;

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          padding: const EdgeInsets.all(10),
          decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(12)),
          child: QrImageView(
            data: code,
            size: qrSize,
            backgroundColor: Colors.white,
            errorCorrectionLevel: QrErrorCorrectLevel.L,
            gapless: true,
            errorStateBuilder: (context, err) => SizedBox(
              width: qrSize,
              height: qrSize,
              child: const Center(
                child: Padding(
                  padding: EdgeInsets.all(16),
                  child: Text(
                    'Mã quá dài để hiện QR.\nHãy dùng "Sao chép" hoặc "Chia sẻ" để gửi mã.',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: Colors.black87),
                  ),
                ),
              ),
            ),
          ),
        ),
        if (showText) ...[
          const SizedBox(height: 8),
          SelectableText(
            code,
            maxLines: 3,
            style: const TextStyle(fontFamily: 'monospace', fontSize: 11),
          ),
        ],
        const SizedBox(height: 10),
        Wrap(
          spacing: 10,
          runSpacing: 8,
          alignment: WrapAlignment.center,
          children: [
            FilledButton.tonalIcon(
              key: const Key('copyCode'),
              icon: const Icon(Icons.copy_rounded),
              label: const Text('Sao chép mã'),
              onPressed: () async {
                await Clipboard.setData(ClipboardData(text: code));
                if (context.mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('Đã sao chép mã'), duration: Duration(seconds: 2)),
                  );
                }
              },
            ),
            FilledButton.tonalIcon(
              icon: const Icon(Icons.share_rounded),
              label: const Text('Chia sẻ'),
              onPressed: () async {
                final box = context.findRenderObject() as RenderBox?;
                try {
                  await SharePlus.instance.share(ShareParams(
                    text: '$shareText\n\n$code',
                    sharePositionOrigin: box == null ? null : box.localToGlobal(Offset.zero) & box.size,
                  ));
                } catch (_) {
                  await Clipboard.setData(ClipboardData(text: code));
                }
              },
            ),
          ],
        ),
      ],
    );
  }
}

/// Ô nhập mã: dán từ bộ nhớ tạm hoặc quét QR.
class CodeInput extends StatelessWidget {
  const CodeInput({
    super.key,
    required this.controller,
    required this.label,
    required this.onScan,
    this.onSubmit,
  });

  final TextEditingController controller;
  final String label;
  final VoidCallback onScan;
  final VoidCallback? onSubmit;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        TextField(
          key: const Key('codeInput'),
          controller: controller,
          minLines: 2,
          maxLines: 4,
          style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
          decoration: InputDecoration(
            labelText: label,
            hintText: 'P2PM1.…',
            suffixIcon: IconButton(
              tooltip: 'Dán',
              icon: const Icon(Icons.content_paste_rounded),
              onPressed: () async {
                final d = await Clipboard.getData(Clipboard.kTextPlain);
                if (d?.text != null) controller.text = d!.text!.trim();
              },
            ),
          ),
        ),
        const SizedBox(height: 10),
        Row(
          children: [
            Expanded(
              child: OutlinedButton.icon(
                icon: const Icon(Icons.qr_code_scanner_rounded),
                label: const Text('Quét QR'),
                onPressed: onScan,
              ),
            ),
            if (onSubmit != null) ...[
              const SizedBox(width: 10),
              Expanded(
                child: FilledButton.icon(
                  key: const Key('submitCode'),
                  icon: const Icon(Icons.check_rounded),
                  label: const Text('Xác nhận'),
                  onPressed: onSubmit,
                ),
              ),
            ],
          ],
        ),
      ],
    );
  }
}
