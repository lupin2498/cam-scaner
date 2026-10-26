# Cam Scaner – Quét tài liệu ra PDF

Ứng dụng quét tài liệu bằng máy ảnh và xuất PDF cho **Android** (Kotlin, Jetpack Compose) và **iOS**
(SwiftUI), tham khảo các chức năng của bản trả phí (VIP) của CamScanner. Toàn bộ xử lý chạy trên máy,
không quảng cáo, không hình mờ, không cần tài khoản.

## Chức năng

**Quét**
- Máy ảnh nhận diện mép giấy theo thời gian thực, khung viền hiện trực tiếp trên ảnh xem trước.
- Tự động chụp khi tài liệu được giữ yên (bật/tắt được); chế độ một trang và nhiều trang liên tiếp.
- Chế độ chụp: **Tài liệu**, **Thẻ CCCD** (chụp hai mặt, cắt đúng khổ thẻ), **Sách** (mỗi ảnh tách
  thành hai trang), **Mã QR / mã vạch** (mở liên kết, sao chép, chia sẻ, quét từ ảnh có sẵn), **Ảnh**
  (giữ nguyên, không cắt, không lọc).
- Chọn **khổ giấy khi chụp** (A4, A5, A6, A3, B5, Letter, Legal, thẻ CCCD, hộ chiếu, danh thiếp, ảnh
  10×15, vuông): khung hướng dẫn trên màn hình và vùng cắt tự chọn theo khổ đó.
- Chụm để thu phóng, chạm để lấy nét, đèn flash, lưới căn chỉnh, chỉ báo cân bằng máy khi chụp tài liệu nằm phẳng.
- Nhập ảnh từ thư viện, nhập PDF (mỗi trang PDF thành một trang), nhập tệp; nhận ảnh/PDF chia sẻ từ ứng dụng khác;
  phím tắt màn hình chính (Android).

**Tự cắt và canh khổ giấy**
- Tự tìm 4 góc tờ giấy (OpenCV trên Android, Vision trên iOS), nắn phẳng phối cảnh.
- Tính tỉ lệ thật của tờ giấy khi chụp nghiêng (thuật toán Zhang & He) và tự khớp về đúng khổ chuẩn
  A4/A3/A5, Letter, Legal, thẻ CCCD nếu sai lệch < 3,5 %.
- **Tự chọn vùng theo khổ giấy**: chọn một khổ trong màn cắt, vùng cắt là vùng lớn nhất đúng tỉ lệ khổ đó
  nằm trong tờ giấy đã nhận diện (tính theo đúng phối cảnh bằng phép chiếu đồng dạng); trang ra đúng tỉ lệ khổ.
- Chỉnh tay từng góc hoặc từng cạnh, có kính lúp phóng to dưới ngón tay; nút "Tự động", "Toàn ảnh", "Chia đôi".
- Tự căn chỉnh trang theo hướng chữ và làm thẳng trang (đo độ nghiêng dòng chữ), thanh chỉnh góc.

**Làm đẹp ảnh**
- Bộ lọc: Gốc, Làm sáng, Tăng cường, Thần kỳ màu, Không có bóng, Thang xám, Đen trắng, Bản sao.
- Chỉnh độ sáng, tương phản, độ nét, độ mạnh bộ lọc; xoay trang; áp dụng cho tất cả trang.
- Ảnh gốc được giữ lại nên có thể cắt lại hoặc đổi bộ lọc bất cứ lúc nào mà không giảm chất lượng.

**Đánh dấu**
- Vẽ bút, bút dạ (highlight), tẩy; hoàn tác.
- Chữ ký: vẽ một lần, lưu lại để dùng cho các trang sau; kéo để di chuyển, chụm để đổi cỡ.
- Hộp chữ: thêm chữ lên trang, kéo, đổi cỡ, đổi màu, xoay.

**Xuất**
- PDF: khổ giấy Tự động (trang đã cắt theo khổ nào thì tờ PDF đúng khổ đó; thẻ CCCD/hộ chiếu/danh thiếp
  in đúng kích thước thật), Theo ảnh, A4, Letter, Legal, A3, A5, B5; hướng trang, lề, 4 mức chất lượng;
  1, 2 hoặc 4 trang trên mỗi tờ.
- PDF tìm kiếm được (lớp chữ OCR ẩn), ghép hai mặt thẻ vào một trang A4, mật khẩu, đánh số trang, hình mờ.
- In trực tiếp, lưu ảnh trang vào thư viện, ảnh dài (ghép mọi trang), văn bản TXT, Word DOCX (có hoặc không kèm ảnh).
- Xem trước bố cục trang trước khi xuất; mở, chia sẻ hoặc lưu file vào máy.

**Quản lý tài liệu**
- Thư mục, sắp xếp (mới sửa / ngày tạo / tên), tìm kiếm cả trong chữ đã nhận dạng của trang.
- Chọn nhiều tài liệu: xóa, gộp thành một, chuyển thư mục; tạo bản sao; chi tiết tài liệu.
- Thùng rác: khôi phục hoặc xóa vĩnh viễn, tự dọn sau 30 ngày.
- Trong tài liệu: kéo thả sắp xếp trang; chọn trang để xoay, chia sẻ, xóa, chuyển/sao chép sang tài liệu khác
  hoặc tạo tài liệu mới; nhận dạng chữ mọi trang.
- Nhận dạng chữ (OCR) từng trang, sửa, sao chép, chia sẻ, dịch văn bản.

**Khác**
- Khóa ứng dụng bằng vân tay / Face ID / khóa màn hình.
- Sao lưu toàn bộ (ảnh gốc, thông số, thư mục) ra một tệp .zip và khôi phục.
- Giao diện sáng/tối, tiền tố tên tài liệu mới.

## Tải về

Mỗi bản phát hành trên GitHub (mục *Releases*) gồm:

- `CamScaner-<phiên bản>.apk` – Android 7.0 trở lên, cài trực tiếp.
- `CamScaner-<phiên bản>-unsigned.ipa` – iOS 15 trở lên, bản chưa ký: ký bằng tài khoản Apple của bạn
  (AltStore, Sideloadly, Xcode hoặc Apple Configurator) rồi cài.

## Build

### Android

Yêu cầu Android Studio (JDK 17) hoặc dòng lệnh:

```bash
./gradlew assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit test
./gradlew -PwithX86 connectedDebugAndroidTest   # test trên máy ảo/thiết bị
```

APK mặc định chứa thư viện cho `arm64-v8a` và `armeabi-v7a` (điện thoại thật). Để chạy trên máy ảo x86_64:
`./gradlew assembleDebug -PwithX86`.

### iOS

Yêu cầu macOS với Xcode 15 trở lên và [XcodeGen](https://github.com/yonaskolb/XcodeGen):

```bash
cd ios
xcodegen generate              # tạo CamScaner.xcodeproj từ project.yml
open CamScaner.xcodeproj       # chọn Team của bạn rồi chạy trên máy thật
```

Unit test (hình học, bố cục PDF, dữ liệu, ZIP) và test giao diện XCUITest (nhập ảnh, cắt theo khổ, lọc, lưu,
xuất PDF/TXT, hộp chữ, nhận dạng chữ, thư mục, tìm kiếm, thùng rác, giao diện tối, máy ảnh) chạy trên simulator:

```bash
xcodebuild test -project CamScaner.xcodeproj -scheme CamScaner -destination 'platform=iOS Simulator,name=iPhone 16 Pro'
```

Trên CI, job `ios` dựng bản không ký và đóng gói `.ipa`:

```bash
xcodebuild -project CamScaner.xcodeproj -scheme CamScaner -configuration Release -sdk iphoneos \
  -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
```

Mỗi lần push, GitHub Actions build APK, chạy unit test và UI test trên máy ảo Android, dựng IPA, rồi đính kèm
trong mục *Artifacts* của lần chạy. Chạy workflow thủ công với `publish = true` để tạo bản phát hành.

## Cấu trúc

| Thư mục | Nội dung |
|---|---|
| `app/.../scanner/` | Nhận diện mép giấy, nắn phối cảnh, khổ giấy, bộ lọc (OpenCV) – Android |
| `app/.../pdf/` | Khổ giấy, bố cục trang, xuất PDF (PdfBox-Android) |
| `app/.../ocr/`, `export/` | Nhận dạng chữ (ML Kit); xuất ảnh, TXT, DOCX, in |
| `app/.../data/` | Lưu tài liệu (ảnh gốc + thông số từng trang), thư mục, thùng rác, sao lưu, cài đặt |
| `app/.../ui/` | Giao diện Jetpack Compose: máy ảnh (CameraX), cắt, bộ lọc, đánh dấu, tài liệu, xuất |
| `ios/CamScaner/Scan/` | Hình học (tỉ lệ thật, phép chiếu, khớp khổ giấy), nhận diện (Vision), xử lý ảnh (Core Image) |
| `ios/CamScaner/PDF/`, `Export/`, `OCR/` | Bố cục và xuất PDF (PDFKit, Core Text), xuất khác, OCR (Vision) |
| `ios/CamScaner/Data/` | Kho tài liệu, cài đặt, chữ ký, ZIP |
| `ios/CamScaner/UI/` | Giao diện SwiftUI: máy ảnh (AVFoundation), cắt, bộ lọc, đánh dấu, tài liệu, xuất |
