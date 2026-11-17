# Sprintly — Ghi chú tiếp tục dự án

## Trạng thái bàn giao

- Project Android khởi tạo nằm ở `source/`. Dùng Android Studio để mở thư mục này hoặc chạy `cd source` và `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` trên Windows. Unit test hiện là test mẫu của project.
- `minSdk` hiện là 24, trong khi đặc tả chọn 26. Đối chiếu lại khi dựng nền Android. Chuẩn bị Android SDK trên máy mới và để Android Studio tạo `local.properties` theo đường dẫn trên máy đó.
- Quyết định BA đã được duyệt ngày 17/11/2026. Bước triển khai tiếp theo là Phase 0 trên nhánh `chore/bootstrap` từ `main`.
- [Đặc tả dự án](Sprintly_Codex_Implementation_Spec.md) · [BA](Sprintly_BA.md) · [Use Case](Sprintly_Use_Cases.md) · [ERD](Sprintly_ERD.md) · [Thiết kế Orbix đã chốt](Sprintly_Orbix_Design.html)

## Quyết định BA đã duyệt

| Chủ đề | Quyết định |
| --- | --- |
| Phạm vi MVP | Giữ FR-01–FR-12. Bình luận P0 gồm tạo và xem. Lời mời chờ chấp nhận thuộc P2. Tổng quan, Lịch công việc và phần trăm hoàn thành nằm sau P0. Khi làm phần trăm hoàn thành, dùng ID cột hoàn thành ổn định và định nghĩa công thức tính. |
| Quyền và dữ liệu khi xóa | Owner sửa/xóa workspace, tra cứu email khớp chính xác và thêm hoặc gỡ thành viên trong workspace của mình. Member có thể sửa dự án và thêm/đổi tên cột. Owner xóa dự án. Owner hoặc người tạo task còn là thành viên được xóa task. Gỡ thành viên giữ bình luận và tác giả, đồng thời gỡ phân công, tăng phiên bản task và ghi lịch sử trong cùng giao dịch. Xóa workspace, dự án hoặc task xóa vĩnh viễn dữ liệu con, gồm bình luận và lịch sử của task bị xóa. |
| Phiên và offline | Lỗi mạng, timeout hoặc `5xx` khi refresh cho phép đọc cache của tài khoản hiện tại đến khi xác thực lại thành công. Khi server xác nhận token không hợp lệ, hết hạn hoặc bị thu hồi, khóa cache và về đăng nhập. Đăng xuất kết thúc phiên cục bộ ngay. Bỏ phản hồi đến muộn từ mọi phiên đã kết thúc. |
| Kéo thả và thử lại | Kéo thả và menu **Di chuyển** dùng chung `targetIndex` và kiểm tra cả phiên bản task lẫn phiên bản thứ tự board. Tải lại board khi xung đột hoặc mất phản hồi trước khi cho thử lại. Tạo task và gửi bình luận dùng cùng `Idempotency-Key` và payload cho lần thử lại sau timeout. |

[Chi tiết các quyết định đã duyệt](Sprintly_BA.md#6-quyết-định-đã-duyệt)

## Thứ tự triển khai đề xuất

1. **Phase 0 · `chore/bootstrap`**. Dùng scaffold trong `source/`, chốt `minSdk` và `applicationId`, bật ViewBinding, dựng theme Orbix và điều hướng Fragment. Dựng PostgreSQL, migration ban đầu và `/health`. Thiết lập CI build và kiểm tra API cơ bản. Thử kéo task giữa hai cột trên emulator sớm.
2. **Mốc demo đầu tiên**. Làm theo luồng hai tài khoản với backend thật. Đăng nhập, tạo workspace, thêm thành viên, tạo dự án, giao task, di chuyển task và tải lại để kiểm tra vị trí. Viết kiểm thử phiên, phân quyền và xung đột trước khi chốt từng luồng.
3. **Mốc hoàn tất MVP**. Bổ sung offline chỉ đọc, tìm/lọc, bình luận và lịch sử. Kiểm tra lại AC-01–AC-06 trước khi đóng gói. FR-12 yêu cầu CI và ít nhất một UI test để nghiệm thu MVP.
