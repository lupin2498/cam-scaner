# Cam Scaner – Quét tài liệu ra PDF

Ứng dụng Android quét tài liệu bằng máy ảnh và xuất PDF, tham khảo các chức năng của bản trả phí (VIP)
của CamScanner. Toàn bộ xử lý chạy trên máy, không quảng cáo, không hình mờ, không cần tài khoản.

## Chức năng

**Quét**
- Máy ảnh nhận diện mép giấy theo thời gian thực, khung viền hiện trực tiếp trên ảnh xem trước.
- Tự động chụp khi tài liệu được giữ yên (bật/tắt được).
- Chế độ một trang và nhiều trang liên tiếp; nhập nhiều ảnh từ thư viện hoặc nhận ảnh chia sẻ từ ứng dụng khác.
- Đèn flash (tắt / tự động / bật / đèn pin), lưới căn chỉnh.

**Tự cắt và canh khổ giấy**
- Tự tìm 4 góc tờ giấy (OpenCV), nắn phẳng phối cảnh.
- Tính tỉ lệ thật của tờ giấy khi chụp nghiêng (thuật toán Zhang & He) và tự khớp về đúng khổ chuẩn
  A4/A3/A5, Letter, Legal, thẻ CCCD nếu sai lệch < 3,5 %.
- Chỉnh tay từng góc hoặc từng cạnh, có kính lúp phóng to dưới ngón tay; nút "Tự động" và "Toàn ảnh".

**Làm đẹp ảnh**
- Bộ lọc: Gốc, Làm sáng, Tăng màu (Magic Color), Xóa bóng, Thang xám, Đen trắng, Bản sao.
- Ước lượng ánh sáng nền để làm trắng giấy và xóa bóng đổ nhưng giữ nguyên ảnh, logo, vùng màu.
- Chỉnh độ sáng, tương phản, độ nét; xoay trang; áp dụng một bộ lọc cho tất cả trang.
- Ảnh gốc được giữ lại nên có thể cắt lại hoặc đổi bộ lọc bất cứ lúc nào mà không giảm chất lượng.

**Xuất PDF**
- Khổ giấy: Tự động (nhận khổ theo tỉ lệ trang), Theo ảnh, A4, Letter, Legal, A3, A5, B5.
- Hướng trang tự động/dọc/ngang, lề trang, 4 mức chất lượng (đến HD ~275 dpi).
- Trang đen trắng được nén 1-bit không mất dữ liệu: nét chữ sắc, dung lượng rất nhỏ.
- PDF tìm kiếm được: lớp chữ OCR ẩn (ML Kit, hỗ trợ tiếng Việt) để tìm, bôi đen, sao chép chữ.
- Ghép mặt trước/sau thẻ CCCD vào một trang A4 đúng kích thước thật.
- Đặt mật khẩu PDF (AES-256), đánh số trang, hình mờ tùy chọn.
- Xem trước bố cục trang trước khi xuất; mở, chia sẻ hoặc lưu file vào máy.

**Quản lý tài liệu**
- Danh sách tài liệu, tìm kiếm, đổi tên, xóa, xuất PDF nhanh.
- Kéo thả để sắp xếp trang, xem trang phóng to, xoay, xóa trang.
- Nhận dạng chữ (OCR) từng trang, sao chép hoặc chia sẻ văn bản.

## Build

Yêu cầu Android Studio (JDK 17) hoặc dòng lệnh:

```bash
./gradlew assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit test
```

APK mặc định chứa thư viện cho `arm64-v8a` và `armeabi-v7a` (điện thoại thật). Để chạy trên máy ảo x86_64:
`./gradlew assembleDebug -PwithX86`.

Mỗi lần push, GitHub Actions build APK và đính kèm trong mục *Artifacts* của lần chạy.

## Cấu trúc

| Thư mục | Nội dung |
|---|---|
| `scanner/` | Nhận diện mép giấy, nắn phối cảnh, bộ lọc làm đẹp (OpenCV) |
| `pdf/` | Khổ giấy, bố cục trang, xuất PDF (PdfBox-Android) |
| `ocr/` | Nhận dạng chữ (ML Kit) |
| `data/` | Lưu tài liệu (ảnh gốc + thông số từng trang), cài đặt |
| `ui/` | Giao diện Jetpack Compose: máy ảnh (CameraX), cắt, bộ lọc, tài liệu, xuất PDF |
