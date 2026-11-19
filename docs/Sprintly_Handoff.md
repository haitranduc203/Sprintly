# Sprintly — Ghi chú tiếp tục dự án

## Trạng thái bàn giao

- Android ở `source/` dùng Kotlin/XML, ViewBinding và điều hướng Fragment. Phase 1 có màn kiểm tra phiên, đăng nhập, đăng ký và hồ sơ theo thiết kế Orbix. Phiên được mã hóa bằng Android Keystore. `minSdk` là 26 và `applicationId` là `com.example.sprintly`.
- Backend ở `backend/` có API xác thực `/api/v1`, xoay refresh token, thu hồi phiên, `GET /health`, migration PostgreSQL và seed hai tài khoản demo. [Hướng dẫn chạy và kiểm thử](../README.md)
- Quyết định BA đã được duyệt ngày 17/11/2026. Nhánh `feature/authentication` tách từ `main` sau khi hợp nhất Phase 0. [Báo cáo kiểm thử Phase 0](Sprintly_Phase_0_Verification.md)
- Kiểm chứng Phase 1 trên máy ngày 18/11/2026. Backend đạt 10 test trên PostgreSQL 17.11, migration giữ nguyên hai tài khoản demo. Android đạt 8 unit test và 6 Espresso test trên Pixel 6a Android 14. Thử tắt hẳn tiến trình xác nhận app khôi phục phiên khi có kết nối, giữ đúng tài khoản khi API lỗi và xóa phiên khi đăng xuất offline.
- Phase 2 đưa người dùng đã đăng nhập đến danh sách workspace. Android hiển thị dự án, thành viên và board từ API, đồng thời áp dụng quyền Owner và Member trong từng workspace. Backend giữ thứ tự cột và `boardVersion` nhất quán. [Quyền và lệnh kiểm thử Phase 2](../README.md#phase-2-workspace-and-project-permissions)
- Kiểm chứng Phase 2 trên máy ngày 18/11/2026. Migration PostgreSQL 17.11 UTF-8 giữ nguyên hai tài khoản demo và phiên đang hoạt động. Backend đạt 15 test. Android đạt 17 unit test và 7 Espresso test trên Pixel 6a Android 14, gồm luồng hai tài khoản với workspace, thành viên, dự án và cột Kanban. Lint có 0 lỗi và 9 cảnh báo.
- Phase 3 có board Kanban, biểu mẫu tạo và sửa task, màn chi tiết với bình luận và lịch sử. Người dùng kéo thả hoặc dùng **Di chuyển** để chọn cột và vị trí. Khi gỡ thành viên, backend gỡ phân công, tăng phiên bản task và ghi lịch sử trong cùng giao dịch. [Đặc tả Phase 3](Sprintly_Codex_Implementation_Spec.md#phase-3--task-crud--kanban) · [Lệnh kiểm thử Phase 3](../README.md#verify-the-end-to-end-flows)
- Phase 4 lưu workspace, dự án, thành viên, board và task đã đồng bộ trong Room theo từng lần đăng nhập. Có thể mở dữ liệu đã lưu khi mất mạng và app làm mới khi kết nối trở lại. [Lược đồ Room](Sprintly_ERD.md#4-room-trên-android) · [Cách kiểm thử offline](../README.md#verify-the-end-to-end-flows)
- [Đặc tả dự án](Sprintly_Codex_Implementation_Spec.md) · [BA](Sprintly_BA.md) · [Use Case](Sprintly_Use_Cases.md) · [ERD](Sprintly_ERD.md) · [Thiết kế Orbix đã chốt](Sprintly_Orbix_Design.html)

## Quy ước Git

- Đặt tên nhánh theo dạng `<loại>/<mô-tả>`. Các loại đã thống nhất gồm `feature/`, `fix/`, `hotfix/`, `refactor/`, `chore/`, `docs/`, `test/` và `release/`. Phase 1 dùng `feature/authentication`. Phase 2 dùng `feature/workspace-management`. Phase 3 dùng `feature/task-kanban`. Phase 4 dùng `feature/offline-cache`. Phase 5 dùng `feature/mvp-finish`.
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

## Kiểm chứng Phase 3

| Kiểm tra ngày 18/11/2026 | Kết quả |
| --- | --- |
| PostgreSQL 17.11 UTF-8, `prisma migrate deploy` và đối chiếu schema | PASS. Migration giữ dữ liệu Phase 2, bao gồm tài khoản, phiên đang hoạt động và cột dự án đã lưu. Prisma xác nhận schema khớp migration. |
| `npm run build` và `npm test` | PASS. 20 test backend. Bộ test bao phủ quyền ghi và xóa, phiên bản task và board, thứ tự cột, gửi lại bằng cùng khóa và lịch sử sau khi gỡ thành viên. |
| `assembleDebug`, `testDebugUnitTest`, `lintDebug` | PASS. 31 unit test Android, gồm hoàn tác move khi mô phỏng HTTP 409 và 500. Lint có 0 lỗi và 8 cảnh báo. |
| AndroidJUnitRunner trên Pixel 6a Android 14 với API thật | PASS. 8 ca Espresso, gồm kéo thả vào cột đã có task, tải lại đúng thứ tự và mở lại task để xác nhận nội dung sửa, bình luận và lịch sử. Luồng xác thực và phân quyền workspace cũng pass. |
| `android-cli` trên Pixel 6a Android 14 | Đối chiếu ảnh board, chi tiết và biểu mẫu task với thiết kế Orbix. Kiểm tra vị trí điều hướng, nhãn, ưu tiên và CTA sau khi nạp dữ liệu thật. |

## Kiểm chứng Phase 4

- `assembleDebug`, `assembleDebugAndroidTest` và `testDebugUnitTest` đạt. Có 32 unit test. `lintDebug` báo 0 lỗi và 12 cảnh báo.
- Kiểm tra Room trên Pixel 7 Pro API 34 xác nhận giữ đúng snapshot board, loại dữ liệu khi danh sách đầy đủ xác nhận mất quyền và cách ly từng lần đăng nhập.
- Luồng offline trên Pixel 7 Pro API 34 dùng backend với PostgreSQL 17.12. App mở được board và task đã lưu, giữ nháp khi tái tạo Activity, khóa ghi khi mất mạng, tự làm mới khi kết nối lại và cách ly dữ liệu sau khi đăng xuất offline rồi đổi tài khoản. Các ca workspace và Kanban đạt khi chạy riêng với phiên kiểm thử mới. [Lệnh kiểm thử](../README.md#verify-the-end-to-end-flows)

## Kiểm chứng Phase 5

Tìm kiếm chạy trong dự án hiện tại và kết hợp từ khóa theo tiêu đề với cột, ưu tiên và người phụ trách. Khi trở lại từ chi tiết, kết quả làm mới theo tiêu chí đang giữ. Chi tiết task hiển thị trạng thái tải và thử lại riêng cho bình luận và lịch sử, kiểm tra nội dung bình luận tại ô nhập và cuộn đến bình luận sau khi máy chủ xác nhận.

| Tiêu chí | Kết quả và bằng chứng |
| --- | --- |
| AC-01 · Phiên | PASS. `AuthApiFlowTest` đăng ký, tái tạo Activity, đăng xuất và đăng nhập lại. Kiểm thử backend và `AuthRepositoryTest` kiểm tra xoay token, lỗi refresh tạm thời, từ chối token bị thu hồi và phản hồi đến muộn sau khi đổi phiên. |
| AC-02 · Workspace và dự án | PASS. `WorkspaceApiFlowTest` chạy hai vai trò, xác nhận cột mặc định và phân quyền quản lý. Kiểm thử PostgreSQL xác nhận tra email chính xác, từ chối truy cập ngoài phạm vi và gỡ người phụ trách khi xóa thành viên. |
| AC-03 · Task và Kanban | PASS cho luồng chính. `KanbanApiFlowTest` kéo thả và tải lại thứ tự. `BoardMoveTest` kiểm tra hoàn tác và làm mới khi API lỗi hoặc có xung đột. Kiểm thử PostgreSQL xác nhận yêu cầu tạo lặp cùng khóa chỉ tạo một task và một bản ghi lịch sử. |
| AC-04 · Bình luận và lịch sử | PASS cho luồng chính. `KanbanApiFlowTest` kiểm tra bình luận trống, gửi và mở lại, cùng lịch sử move và sửa tiêu đề. Kiểm thử PostgreSQL xác nhận nội dung gửi lại, thông tin tác nhân và xóa dây chuyền. |
| AC-05 · Tìm kiếm và offline | PASS. `TaskSearchApiFlowTest` kiểm tra ba bộ lọc kết hợp, làm mới sau move, quay lại từ chi tiết, giữ tiêu chí khi tái tạo Activity, xóa lọc, đặt lại và về board đã lưu khi mất mạng. `OfflineApiFlowTest` và `OfflineCacheTest` kiểm tra board từ Room, khóa ghi, làm mới khi có mạng, dọn snapshot mất quyền và cách ly phiên. |

- `npm run build` và `npm test` đạt, gồm 21 kiểm thử với PostgreSQL 17.12. Gradle `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest` và `lintDebug` đạt, gồm 32 unit test, 0 lỗi lint và 11 cảnh báo.
- 11 ca AndroidJUnitRunner đạt trên Pixel 7 Pro API 34 với các lớp kiểm thử chạy riêng trên cùng emulator. [Cách chạy lại](../README.md#verify-the-end-to-end-flows)
- Kiểm tra thủ công đạt khi nhập sai mật khẩu, mở lại ứng dụng sau khi tiến trình bị dừng, mở board chưa có cache trong chế độ máy bay, tải board khi kết nối lại và dùng **Thử lại** để chạy tiếp truy vấn sau khi API phục hồi. Đã đối chiếu giao diện tìm kiếm, bộ lọc và chi tiết với Orbix trên emulator.
- Mô phỏng mất phản hồi sau khi máy chủ đã tạo task hoặc bình luận là mục còn mở trên Android. Kiểm thử API đã xác nhận gửi lại cùng khóa và nội dung chỉ tạo một bản ghi.

## Thứ tự triển khai đề xuất

1. **Phase 0 · `chore/bootstrap`**. Dùng scaffold trong `source/`, chốt `minSdk` và `applicationId`, bật ViewBinding, dựng theme Orbix và điều hướng Fragment. Dựng PostgreSQL, migration ban đầu và `/health`. Thiết lập CI build và kiểm tra API cơ bản. Thử kéo task giữa hai cột trên emulator sớm.
2. **Mốc demo đầu tiên**. Làm theo luồng hai tài khoản với backend thật. Đăng nhập, tạo workspace, thêm thành viên, tạo dự án, giao task, di chuyển task và tải lại để kiểm tra vị trí. Viết kiểm thử phiên, phân quyền và xung đột trước khi chốt từng luồng.
3. **Phase 4 · Room và đọc offline**. Lưu workspace, project, board và task theo tài khoản. Kiểm tra mở lại dữ liệu từng tải khi tắt mạng và bảo đảm tài khoản khác không đọc được dữ liệu đã lưu.
4. **Mốc hoàn tất MVP**. Bổ sung giao diện tìm/lọc và hoàn thiện trải nghiệm bình luận, lịch sử. Kiểm tra lại AC-01–AC-06 trước khi đóng gói. FR-12 yêu cầu CI và ít nhất một UI test để nghiệm thu MVP.
