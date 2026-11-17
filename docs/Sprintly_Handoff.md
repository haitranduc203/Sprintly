# Sprintly — Ghi chú tiếp tục dự án

## Trạng thái bàn giao

- Project Android khởi tạo nằm ở `source/`. Dùng Android Studio để mở thư mục này hoặc chạy `cd source` và `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` trên Windows. Unit test hiện là test mẫu của project.
- `minSdk` hiện là 24, trong khi đặc tả chọn 26. Đối chiếu lại khi dựng nền Android. Chuẩn bị Android SDK trên máy mới và để Android Studio tạo `local.properties` theo đường dẫn trên máy đó.
- Bước tiếp theo là duyệt các quyết định BA bên dưới. Sau khi duyệt, cập nhật đặc tả, BA, Use Case và ERD để chúng mô tả cùng một hành vi, rồi tạo branch `chore/bootstrap` từ `main`.
- [Đặc tả dự án](Sprintly_Codex_Implementation_Spec.md) · [BA](Sprintly_BA.md) · [Use Case](Sprintly_Use_Cases.md) · [ERD](Sprintly_ERD.md) · [Thiết kế Orbix đã chốt](Sprintly_Orbix_Design.html)

## Quyết định BA đề xuất, chờ duyệt

| Chủ đề | Đề xuất |
| --- | --- |
| Phạm vi MVP | Giữ FR-01–FR-12. Owner được sửa/xóa workspace và thêm tài khoản đã đăng ký bằng email chính xác. Bình luận P0 gồm tạo và xem. Lời mời chờ chấp nhận thuộc P2. Tổng quan và Lịch công việc là màn hình đề xuất. Nếu làm thanh tiến độ ở P0, cần định nghĩa cột hoàn thành bằng ID ổn định và công thức đếm. |
| Quyền và dữ liệu khi xóa | Owner xóa dự án. Owner hoặc người tạo task còn là thành viên được xóa task. Giới hạn tra email cho Owner trong phạm vi workspace. Khi gỡ thành viên, gỡ phân công, tăng phiên bản các task liên quan và ghi lịch sử trong cùng giao dịch. Giữ bình luận và danh tính tác giả. Cần duyệt riêng việc xóa task có xóa luôn lịch sử hay không. |
| Phiên và offline | Lỗi kết nối, timeout hoặc lỗi server tạm thời khi làm mới phiên cho phép đọc cache của tài khoản hiện tại. Khi server xác nhận phiên hết hạn hoặc bị thu hồi, khóa cache và về đăng nhập. Khi đổi tài khoản, bỏ phản hồi đến muộn thuộc phiên cũ. |
| Kéo thả và thử lại | Tính `targetIndex` theo thứ tự hiện tại trên server, kéo trên board đầy đủ và tải lại board khi xung đột hoặc mất phản hồi. Menu **Di chuyển** dùng chung quy tắc với kéo thả. Chốt cách xác minh thao tác tạo task hoặc gửi bình luận đã được lưu hay chưa trước khi thử lại sau timeout. |

## Thứ tự triển khai đề xuất

1. **Phase 0 · `chore/bootstrap`**. Dùng scaffold trong `source/`, chốt `minSdk` và `applicationId`, bật ViewBinding, dựng theme Orbix và điều hướng Fragment. Dựng PostgreSQL, migration ban đầu và `/health`. Thiết lập CI build và kiểm tra API cơ bản. Thử kéo task giữa hai cột trên emulator sớm.
2. **Mốc demo đầu tiên**. Làm theo luồng hai tài khoản với backend thật. Đăng nhập, tạo workspace, thêm thành viên, tạo dự án, giao task, di chuyển task và tải lại để kiểm tra vị trí. Viết kiểm thử phiên, phân quyền và xung đột trước khi chốt từng luồng.
3. **Mốc hoàn tất MVP**. Bổ sung offline chỉ đọc, tìm/lọc, bình luận và lịch sử. Kiểm tra lại AC-01–AC-06 trước khi đóng gói. FR-12 yêu cầu CI và ít nhất một UI test để nghiệm thu MVP.
