# Sprintly — Phân tích nghiệp vụ (BA)

| Thuộc tính | Nội dung |
| --- | --- |
| Trạng thái | Các quyết định P0 đã duyệt ngày 17/11/2026 |
| Phạm vi | MVP/P0, từ `FR-01` đến `FR-12` |
| Nguồn yêu cầu | [Sprintly Product & Implementation Specification v1.0](Sprintly_Codex_Implementation_Spec.md) |
| Tài liệu liên quan | [Đặc tả Use Case](Sprintly_Use_Cases.md) · [ERD](Sprintly_ERD.md) |

## 1. Bài toán và mục tiêu

Một nhóm phát triển phần mềm nhỏ cần biết công việc nào đang chờ, ai phụ trách, khi nào đến hạn và điều gì đã thay đổi. Sprintly tổ chức công việc theo workspace và project. Mỗi project có một bảng Kanban gồm các cột có thứ tự. Thành viên dùng bảng để phân công, theo dõi và cập nhật tiến độ, rồi mở task để trao đổi hoặc tra lại lịch sử.

MVP cần chạy được luồng từ đăng nhập, tạo workspace, tạo project, giao task và kéo task sang cột khác đến kiểm tra kết quả sau khi tải lại. Khi mất mạng, thành viên vẫn có thể đọc board đã đồng bộ. Khi đổi tài khoản, dữ liệu đã lưu của tài khoản trước phải nằm ngoài phạm vi truy cập của tài khoản mới. Kết quả được đánh giá bằng các tình huống nghiệm thu ở mục 7.

## 2. Đối tượng và phạm vi quyền

| Đối tượng | Mục tiêu và phạm vi |
| --- | --- |
| Khách | Đăng ký hoặc đăng nhập. |
| Người dùng đã đăng nhập | Xem danh sách workspace mình tham gia, tạo workspace và trở thành `OWNER` của workspace mới, dùng hồ sơ và đăng xuất. |
| `OWNER` | Quản lý workspace và thành viên, vận hành project và task trong workspace. |
| `MEMBER` | Cộng tác trong workspace, tạo project, tạo/sửa task, cập nhật tiến độ và bình luận. |
| Người ngoài workspace | Là trạng thái của một tài khoản đối với một workspace cụ thể. Server từ chối truy cập dữ liệu của workspace đó. |

Vai trò được gán theo từng workspace. Một tài khoản có thể là `OWNER` ở workspace này và `MEMBER` ở workspace khác. Trong ma trận dưới đây, cả hai vai trò đều được kiểm tra membership tại server. Quyền có dấu † được quy định tại `QD-02`.

| Thao tác trong workspace | `OWNER` | `MEMBER` |
| --- | --- | --- |
| Xem workspace, thành viên, project, board và task | Có | Có |
| Tra cứu tài khoản theo email chính xác trong luồng thêm thành viên | Có | — |
| Sửa hoặc xóa workspace, thêm hoặc xóa thành viên | Có | — |
| Tạo project | Có | Có |
| Sửa project | Có | Có † |
| Xóa project | Có † | — † |
| Thêm hoặc đổi tên cột | Có | Có † |
| Tạo, sửa, giao và di chuyển task | Có | Có |
| Xóa task | Có † | Có nếu là người tạo † |
| Tìm/lọc task, bình luận và xem lịch sử | Có | Có |

## 3. Luồng nghiệp vụ chính

1. Người dùng tạo tài khoản hoặc đăng nhập. App mở danh sách workspace theo quyền của tài khoản hiện tại.
2. Người dùng tạo workspace và nhận vai trò `OWNER`. Owner tra cứu tài khoản đã đăng ký bằng email chính xác rồi thêm vào workspace với vai trò `MEMBER`.
3. Owner hoặc Member tạo project. Hệ thống tạo `To Do`, `In Progress` và `Done` cùng lúc với project.
4. Thành viên tạo task trong một cột, nhập nội dung cần thiết và có thể chọn người phụ trách trong workspace. Thành viên kéo task trên board để cập nhật tiến độ.
5. Thành viên tìm/lọc task, mở chi tiết để bình luận hoặc kiểm tra ai đã tạo, chỉnh sửa, di chuyển hay thay đổi người phụ trách.
6. Khi mất mạng, app hiển thị dữ liệu đã đồng bộ của tài khoản hiện tại. Khi có mạng, thành viên làm mới để lấy dữ liệu và quyền truy cập mới nhất.

## 4. Yêu cầu chức năng

| Mã | Nghiệp vụ P0 | Kết quả cần quan sát |
| --- | --- | --- |
| FR-01 | Đăng ký, đăng nhập, khôi phục phiên, tự làm mới token, đăng xuất | Mở lại app giữ phiên hợp lệ. Làm mới thành công thì tiếp tục yêu cầu. Lỗi refresh tạm thời cho phép đọc cache. Phiên bị từ chối dứt khoát thì xóa phiên cục bộ và cache, rồi về màn đăng nhập. |
| FR-02 | Tạo, liệt kê và xem workspace. Owner sửa hoặc xóa workspace theo hợp đồng API | Workspace mới xuất hiện cho người tạo với quyền Owner. Xóa workspace cần xác nhận trên UI. |
| FR-03 | Xem thành viên. Owner thêm hoặc xóa tài khoản đã đăng ký | Tài khoản được thêm thấy workspace. Tài khoản bị xóa mất quyền truy cập từ server. |
| FR-04 | Tạo, đọc, sửa và xóa project | Project thuộc một workspace. Tạo project sinh đúng ba cột mặc định. |
| FR-05 | Xem board, thêm và đổi tên cột | Cột hiển thị theo thứ tự, task tiếp tục thuộc đúng cột sau khi đổi tên. |
| FR-06 | Tạo, xem, sửa và xóa task | Task lưu tiêu đề, mô tả, ưu tiên, hạn và người phụ trách theo dữ liệu đã nhập. |
| FR-07 | Kéo task trong cùng cột hoặc giữa các cột với cập nhật UI trước phản hồi server | Sau tải lại, vị trí khớp với server. Lỗi mạng, lỗi server hoặc xung đột giữ task trên board. |
| FR-08 | Tạo/xem bình luận và xem lịch sử thay đổi | Bình luận tồn tại sau tải lại. Lịch sử có loại thay đổi, người thực hiện và thời điểm. |
| FR-09 | Tìm task theo từ khóa tiêu đề và lọc theo cột, ưu tiên, người phụ trách | Có thể kết hợp bộ lọc và xóa lọc. Kết quả nằm trong phạm vi người dùng có quyền. |
| FR-10 | Đọc offline dữ liệu workspace, project, board và task đã đồng bộ | Hiện trạng thái offline. Dữ liệu gắn với tài khoản và được làm mới khi có mạng. |
| FR-11 | Xử lý tải dữ liệu, trạng thái rỗng, lỗi, thử lại, gửi yêu cầu và xoay màn hình | Có phản hồi kết thúc, tránh gửi trùng và giữ trạng thái màn hình cần thiết khi xoay. |
| FR-12 | Kiểm thử nghiệp vụ và thiết lập CI | Có unit test Android, test API, ít nhất một UI test trên emulator và GitHub Actions build/test. |

P1 gồm thông báo FCM khi được giao task (`FR-13`) và cập nhật realtime (`FR-14`). P2 gồm ghi offline và đồng bộ lại (`FR-15`), lời mời chờ chấp nhận qua link/email (`FR-16`) và đính kèm (`FR-17`). Thanh toán, chat riêng, tích hợp lịch, AI và trang quản trị web nằm ngoài MVP.

## 5. Quy tắc nghiệp vụ

| Mã | Quy tắc |
| --- | --- |
| BR-01 | Server kiểm tra membership của workspace cho mọi lần đọc/ghi project, cột, task, bình luận và lịch sử. Chỉ Owner được sửa/xóa workspace và quản lý thành viên. Workspace luôn có ít nhất một Owner. |
| BR-02 | Email được chuẩn hóa bằng trim và chữ thường. P0 thêm tài khoản đã có qua email chính xác. Chỉ `OWNER` của workspace được tra cứu qua `GET /workspaces/:workspaceId/users/search?email=...`. Mỗi cặp workspace và người dùng có tối đa một membership. |
| BR-03 | Project và ba cột mặc định được tạo trong cùng giao dịch. Trạng thái task được xác định bằng ID cột, nên đổi tên cột giữ nguyên liên kết task. |
| BR-04 | Cột của task phải thuộc project đó. Nếu có người phụ trách, người này phải là thành viên của workspace chứa project. |
| BR-05 | Tên workspace/project dài 1–100 ký tự, tiêu đề task 1–150 và nội dung bình luận 1–2.000. Ưu tiên gồm `LOW`, `MEDIUM`, `HIGH`, `URGENT`. API dùng thời gian UTC, UI hiển thị giờ địa phương. |
| BR-06 | Sửa hoặc di chuyển task gửi `expectedVersion`. Kéo thả hoặc sửa task có đổi cột gửi thêm `expectedBoardVersion` từ cùng snapshot board với thứ tự đã tải. Server kiểm tra phiên bản, lưu thay đổi thứ tự và tăng revision trong giao dịch. Phiên bản cũ trả `409`. App chặn gửi trùng trên cùng task trong lúc move đang chờ. Khi phiên bản cũ hoặc chưa rõ thao tác đã được lưu, app hoàn tác vị trí dự đoán và tải lại board trước khi người dùng chọn lại vị trí. |
| BR-07 | Server ghi lịch sử dạng bổ sung bản ghi cho các thao tác tạo, sửa, di chuyển và thay đổi người phụ trách. Lịch sử ghi người thao tác và thời điểm. |
| BR-08 | App đọc được dữ liệu Room đã đồng bộ của đúng tài khoản. Các thao tác ghi dữ liệu công việc bị khóa khi offline. Khi chưa có cache, app cho biết cần kết nối để tải dữ liệu. Đăng xuất xóa phiên cục bộ và cách ly cache của tài khoản đó. |
| BR-09 | Khi access token hết hạn, app làm mới phiên với tối đa một lần refresh đang chạy tại một thời điểm trong phiên đăng nhập đó. Backend xoay refresh token và vô hiệu token cũ. Android lưu refresh token đã mã hóa bằng Android Keystore. App chỉ áp dụng phản hồi khi yêu cầu còn thuộc cùng phiên đăng nhập. |
| BR-10 | Tạo task và gửi bình luận dùng `Idempotency-Key` theo `QD-07` để mỗi lần gửi chỉ tạo một kết quả kể cả khi mất phản hồi. |

## 6. Quyết định đã duyệt

| Mã | Quyết định ngày 17/11/2026 |
| --- | --- |
| QD-01 | P0 thêm người dùng hiện hữu bằng email chính xác. Chỉ `OWNER` của workspace được tra cứu qua `GET /workspaces/:workspaceId/users/search?email=...`. Lời mời chờ chấp nhận thuộc P2 theo `FR-16`. P0 hỗ trợ tạo và xem bình luận. Các màn Tổng quan và Lịch công việc cùng chỉ số phần trăm hoàn thành được lùi sau P0. |
| QD-02 | Member được sửa project và thêm/đổi tên cột như một phần của cộng tác. Xóa project dành cho Owner. Xóa task dành cho Owner hoặc người tạo task còn là thành viên. |
| QD-03 | Khi xóa thành viên, giữ tài khoản và các tham chiếu tác giả của họ. Trong cùng giao dịch, server gỡ người phụ trách trên các task liên quan trong workspace, tăng `version`, ghi audit với Owner là người thực hiện, rồi xóa membership. Client loại bỏ dữ liệu mất quyền khi lần đồng bộ online xác nhận quyền đã bị thu hồi. Trong lúc offline, quyền thu hồi chỉ có thể được phát hiện sau khi kết nối lại. |
| QD-04 | Xóa workspace, project hoặc task sẽ xóa hẳn nhánh dữ liệu liên quan, gồm bình luận và lịch sử của task bị xóa. Lịch sử chỉ được lưu trong vòng đời của task. |
| QD-05 | Kéo thả trong cùng cột và giữa các cột dựa trên snapshot board đầy đủ, trong đó thứ tự và `boardVersion` được đọc nhất quán. `targetIndex` tính từ 0 trên danh sách đích sau khi loại task nguồn nếu cùng cột. Với `n` task còn lại, `n` là vị trí cuối. Tạo hoặc xóa task, thay đổi thứ tự task và thêm cột tăng `boardVersion` trong cùng giao dịch. Khi đổi cột qua biểu mẫu sửa, máy chủ kiểm tra cả phiên bản task và board rồi chèn task vào cuối cột đích trong cùng giao dịch. Phiên bản task hoặc board đã cũ trả `409`. `targetIndex` sai trên board hiện hành trả `422`. App tải lại board khi xung đột hoặc mất phản hồi. Mọi lần thử move tiếp theo dùng chỉ số tính lại từ board mới. |
| QD-06 | Khi server từ chối refresh dứt khoát vì token không hợp lệ, hết hạn hoặc bị thu hồi, app xóa phiên cục bộ và cache của tài khoản rồi yêu cầu đăng nhập. Lỗi mạng, timeout hoặc `5xx` trong lúc refresh giữ phiên cục bộ ở chế độ chỉ đọc cache đến khi xác thực lại. Nếu máy chủ đã xoay token mà phản hồi bị mất, lần refresh tiếp theo có thể bị từ chối dứt khoát và cần đăng nhập lại. App bỏ phản hồi đến muộn thuộc phiên đăng nhập cũ. Đăng xuất kết thúc phiên cục bộ ngay. Thu hồi phiên trên máy chủ chỉ được xác nhận khi yêu cầu logout thành công. |
| QD-07 | Khi tạo task hoặc gửi bình luận, app sinh một UUID cho header `Idempotency-Key` và giữ nguyên khóa cùng payload khi thử lại thao tác đó. Phạm vi khóa gồm tài khoản, loại thao tác và tài nguyên cha. Server kiểm tra quyền hiện tại trước khi trả kết quả cũ. Server ghi kết quả cùng giao dịch với task hoặc bình luận và audit tạo task nếu áp dụng. Cùng khóa và payload trả lại kết quả đã lưu. Nếu kết quả đã bị xóa và tài nguyên cha còn tồn tại, server giữ khóa và trả `404` để tránh tạo lại. Cùng khóa với payload khác trả `409`. |

## 7. Tiêu chí nghiệm thu và truy vết

| Mã | Tình huống cần xác minh | Yêu cầu |
| --- | --- | --- |
| AC-01 | Đăng ký, đăng nhập, mở lại app và làm mới khi access token hết hạn. Gây lỗi mạng, timeout hoặc `5xx` khi refresh và kiểm tra app chỉ đọc cache. Khi refresh bị từ chối dứt khoát, kiểm tra app xóa phiên và cache rồi về đăng nhập. Thử mất phản hồi sau khi xoay token và từ chối token cũ khi thử lại. Đăng xuất kết thúc phiên cục bộ ngay, kể cả offline. Sau khi logout online thành công, gọi refresh với token cũ bị từ chối. | FR-01, FR-11 |
| AC-02 | Dùng hai tài khoản để tra email chính xác, thêm thành viên và xem workspace. Gọi API tra cứu và quản lý thành viên trực tiếp bằng tài khoản Member bị từ chối. Tạo project có đủ ba cột mặc định. Khi gỡ thành viên đang được giao task, kiểm tra gỡ assignee, tăng phiên bản, ghi audit, giữ tác giả và chặn quyền truy cập. | FR-02–05 |
| AC-03 | Tạo và giao task, sửa nội dung, đổi cột qua biểu mẫu, kéo trong cột và kéo `To Do` sang `In Progress`, rồi tải lại để xác nhận thứ tự. Dùng hai tài khoản, một người đổi thứ tự cột đích trong khi người kia đang kéo một task khác từ snapshot cũ. Move cũ trả `409` dù phiên bản task nguồn vẫn hợp lệ. Chỉ số ngoài phạm vi trên snapshot hiện hành trả `422`. Với `500` hoặc timeout, app giữ task trên board và làm mới khi chưa rõ kết quả ghi. Lần thử lại dùng vị trí trên board mới. Nếu tạo task mất phản hồi sau commit, thử lại cùng khóa và payload trả cùng ID và chỉ ghi một task và một sự kiện tạo. Cùng khóa với payload khác trả `409`. | FR-05–07, FR-11 |
| AC-04 | Tạo bình luận, tải lại và thấy bình luận. Nếu gửi bình luận mất phản hồi sau commit, thử lại cùng khóa và payload trả cùng ID và chỉ ghi một bình luận. Cùng khóa với payload khác trả `409`. Kiểm tra lịch sử tạo/sửa/di chuyển/giao task hiển thị đúng hành động, người thực hiện và thời điểm. Xóa task hoặc tài nguyên cha thì bình luận và lịch sử liên quan được xóa. | FR-06–08, FR-11 |
| AC-05 | Tìm và kết hợp bộ lọc, sau đó xóa lọc. Mở board đã sync với chế độ máy bay, kiểm tra giới hạn ghi, bật mạng và làm mới. Đăng xuất tài khoản A rồi đăng nhập tài khoản B trên cùng thiết bị để kiểm tra cách ly cache. Kiểm tra phản hồi đến muộn của một phiên không ghi vào cache hoặc thay đổi trạng thái phiên đăng nhập sau đó, kể cả khi đăng nhập lại cùng tài khoản. | FR-03, FR-09–11 |
| AC-06 | Từ bản checkout mới, làm theo README để chạy backend và Android debug với dữ liệu demo. Chạy test backend, unit test Android, ít nhất một UI test và kiểm tra GitHub Actions. | FR-12 và các yêu cầu bàn giao |

## 8. Yêu cầu phi chức năng và bàn giao

- **Nền tảng.** Android dùng Kotlin native, XML Views, ViewBinding và MVVM + Clean Architecture. Backend REST dùng TypeScript và PostgreSQL, cache Android dùng Room.
- **Bảo mật.** Server băm mật khẩu và refresh token, xác thực và kiểm tra quyền trên từng tài nguyên, kiểm tra đầu vào và giới hạn tốc độ ở luồng xác thực. Token và mật khẩu nằm ngoài log. Bản release dùng HTTPS.
- **Trải nghiệm và khả năng tiếp cận.** Giao diện nêu rõ nội dung đang tải, rỗng, lỗi có thể thử lại và dữ liệu đã lưu. Trường nhập và nút biểu tượng có nhãn phù hợp. Bố cục hỗ trợ màn hình khoảng 360–440dp và cỡ chữ hệ thống.
- **Độ ổn định.** I/O chạy ngoài luồng giao diện. Request đang chờ có trạng thái rõ và chặn gửi trùng. Danh sách lớn có phân trang theo API và thứ tự ổn định.
- Mỗi phase cần báo cáo phạm vi, lệnh đã chạy, kết quả và giới hạn còn tồn tại. Nghiệm thu sản phẩm dựa trên API, app và dữ liệu chạy thực tế.
- Bàn giao có README tiếng Anh, hướng dẫn môi trường và tài khoản demo, tài liệu API, sơ đồ kiến trúc, test/CI, kịch bản demo và APK theo khả năng ký build.
