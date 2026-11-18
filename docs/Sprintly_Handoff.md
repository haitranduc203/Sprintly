# Sprintly — Ghi chú tiếp tục dự án

## Trạng thái bàn giao

- Android ở `source/` dùng Kotlin/XML, ViewBinding và điều hướng Fragment. Phase 1 có màn kiểm tra phiên, đăng nhập, đăng ký và hồ sơ theo thiết kế Orbix. Phiên được mã hóa bằng Android Keystore. `minSdk` là 26 và `applicationId` là `com.example.sprintly`.
- Backend ở `backend/` có API xác thực `/api/v1`, xoay refresh token, thu hồi phiên, `GET /health`, migration PostgreSQL và seed hai tài khoản demo. [Hướng dẫn chạy và kiểm thử](../README.md)
- Thử nghiệm trên Pixel 7 Pro API 34 xác nhận Android có thể nhận thao tác thả giữa hai cột. Dùng kết quả này khi triển khai kéo thả Kanban ở Phase 3.
- Quyết định BA đã được duyệt ngày 17/11/2026. Nhánh `feature/authentication` tách từ `main` sau khi hợp nhất Phase 0. [Báo cáo kiểm thử Phase 0](Sprintly_Phase_0_Verification.md)
- Kiểm chứng Phase 1 trên máy ngày 18/11/2026. Backend đạt 10 test trên PostgreSQL 17.11, migration giữ nguyên hai tài khoản demo. Android đạt 8 unit test và 6 Espresso test trên Pixel 6a Android 14. Thử tắt hẳn tiến trình xác nhận app khôi phục phiên khi có kết nối, giữ đúng tài khoản khi API lỗi và xóa phiên khi đăng xuất offline.
- Phase 2 đưa người dùng đã đăng nhập đến danh sách workspace. Android hiển thị dự án, thành viên và board từ API, đồng thời áp dụng quyền Owner và Member trong từng workspace. Backend giữ thứ tự cột và `boardVersion` nhất quán. [Quyền và lệnh kiểm thử Phase 2](../README.md#phase-2-workspace-and-project-permissions)
- Kiểm chứng Phase 2 trên máy ngày 18/11/2026. Migration PostgreSQL 17.11 UTF-8 giữ nguyên hai tài khoản demo và phiên đang hoạt động. Backend đạt 15 test. Android đạt 17 unit test và 7 Espresso test trên Pixel 6a Android 14, gồm luồng hai tài khoản với workspace, thành viên, dự án và cột Kanban. Lint có 0 lỗi và 9 cảnh báo.
- Board Phase 2 hiển thị cột đã lưu. Phase 3 bổ sung task và xử lý gỡ phân công, tăng phiên bản task, ghi lịch sử trong cùng giao dịch gỡ thành viên. [Đặc tả Phase 3](Sprintly_Codex_Implementation_Spec.md#phase-3--task-crud--kanban)
- [Đặc tả dự án](Sprintly_Codex_Implementation_Spec.md) · [BA](Sprintly_BA.md) · [Use Case](Sprintly_Use_Cases.md) · [ERD](Sprintly_ERD.md) · [Thiết kế Orbix đã chốt](Sprintly_Orbix_Design.html)

## Quy ước Git

- Đặt tên nhánh theo dạng `<loại>/<mô-tả>`. Các loại đã thống nhất gồm `feature/`, `fix/`, `hotfix/`, `refactor/`, `chore/`, `docs/`, `test/` và `release/`. Phase 1 dùng `feature/authentication`. Phase 2 dùng `feature/workspace-management`.
- Tiêu đề commit mô tả thay đổi, chẳng hạn `feat: add authentication`. Prefix `codex/` không dùng cho tên nhánh hoặc commit.
- Commit từng phần đã hoàn thành và kiểm thử. Tách backend, lớp dữ liệu Android, giao diện và tài liệu thành các commit có thể rà soát riêng.

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
