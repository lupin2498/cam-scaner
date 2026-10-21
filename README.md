# P2P Meet

Ứng dụng họp 2 người kiểu Zoom: **video call, micro, chia sẻ màn hình** giữa 2 điện thoại (Android/iOS),
kết nối **P2P trực tiếp, mã hoá đầu cuối (WebRTC DTLS-SRTP)**, không cần tài khoản hay máy chủ trung gian.

## Cách dùng

| Chế độ | Khi nào | Cách kết nối |
| --- | --- | --- |
| **Wi-Fi / Hotspot** | 2 máy cùng Wi-Fi, hoặc 1 máy bật Hotspot cho máy kia | Chủ phòng: *Cuộc họp mới* → máy kia: *Tham gia* → quét QR (1 lần). Không cần Internet, không dùng bất kỳ máy chủ nào. Kênh báo hiệu mã hoá AES-256-GCM bằng mã phòng trong QR. |
| **Internet** | 2 máy ở xa nhau | Chủ phòng gửi *mã mời* → người tham gia dán/quét → gửi lại *mã trả lời* → chủ phòng dán/quét. Chỉ dùng STUN để biết địa chỉ (không đi qua hình/tiếng). Có thể cấu hình TURN riêng trong Cài đặt nếu 2 mạng không kết nối trực tiếp được (NAT đối xứng). |

Trong cuộc họp: bật/tắt micro, camera, đổi camera, loa ngoài, **chia sẻ màn hình** (toàn màn hình, kể cả khi
chuyển sang app khác), trò chuyện, xem thông tin đường truyền.

**Không ảnh hưởng cuộc gọi khác**: không dùng CallKit/ConnectionService nên không chiếm đường dây điện thoại;
âm thanh dùng chế độ trộn (iOS `mixWithOthers`, Android audio focus *transient-may-duck*); nếu đang có
cuộc gọi khác thì không đổi chế độ âm thanh hệ thống. Cuộc gọi đến vẫn đổ chuông bình thường, cuộc họp giữ kết nối.

## Bộ cài

GitHub Actions (`.github/workflows/build.yml`) build tự động ở mỗi lần push và đăng lên mục **Releases**:

- `P2PMeet-*.apk` — cài trực tiếp trên Android 7.0+.
- `P2PMeet-*-unsigned.ipa` — iOS 15+, chưa ký: cài bằng Sideloadly/AltStore/Xcode với Apple ID của bạn.
  Chia sẻ toàn màn hình iOS dùng Broadcast Upload Extension + App Group `group.com.lupin2498.p2pMeet`.

## Phát triển

```bash
flutter pub get
flutter test          # unit test (mã kết nối, mã hoá, báo hiệu LAN qua socket thật)
flutter run
```
