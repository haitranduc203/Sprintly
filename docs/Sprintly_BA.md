# Sprintly — Phân tích nghiệp vụ (BA)

| Thuộc tính | Nội dung |
| --- | --- |
| Trạng thái | Bản dự thảo để rà soát |
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

Vai trò được gán theo từng workspace. Một tài khoản có thể là `OWNER` ở workspace này và `MEMBER` ở workspace khác. Trong ma trận dưới đây, cả hai vai trò đều được kiểm tra membership tại server. Quyền có dấu † là đề xuất `QD-02` để chốt các thao tác mà v1.0 chưa phân vai chi tiết.

| Thao tác trong workspace | `OWNER` | `MEMBER` |
| --- | --- | --- |
| Xem workspace, thành viên, project, board và task | Có | Có |
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
| FR-01 | Đăng ký, đăng nhập, khôi phục phiên, tự làm mới token, đăng xuất | Mở lại app giữ phiên hợp lệ. Làm mới thành công thì tiếp tục yêu cầu. Phiên bị từ chối thì về màn đăng nhập. |
| FR-02 | Tạo, liệt kê và xem workspace. Owner sửa hoặc xóa workspace theo hợp đồng API | Workspace mới xuất hiện cho người tạo với quyền Owner. Xóa workspace cần xác nhận trên UI. |
| FR-03 | Xem thành viên. Owner thêm hoặc xóa tài khoản đã đăng ký | Tài khoản được thêm thấy workspace. Tài khoản bị xóa mất quyền truy cập từ server. |
| FR-04 | Tạo, đọc, sửa và xóa project | Project thuộc một workspace. Tạo project sinh đúng ba cột mặc định. |
| FR-05 | Xem board, thêm và đổi tên cột | Cột hiển thị theo thứ tự, task tiếp tục thuộc đúng cột sau khi đổi tên. |
| FR-06 | Tạo, xem, sửa và xóa task | Task lưu tiêu đề, mô tả, ưu tiên, hạn và người phụ trách theo dữ liệu đã nhập. |
| FR-07 | Kéo thả task với cập nhật UI trước phản hồi server | Sau tải lại, vị trí khớp với server. Lỗi mạng, lỗi server hoặc xung đột giữ task trên board. |
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
| BR-02 | Email được chuẩn hóa bằng trim và chữ thường. P0 thêm tài khoản đã có qua email chính xác. Mỗi cặp workspace và người dùng có tối đa một membership. |
| BR-03 | Project và ba cột mặc định được tạo trong cùng giao dịch. Trạng thái task được xác định bằng ID cột, nên đổi tên cột giữ nguyên liên kết task. |
| BR-04 | Cột của task phải thuộc project đó. Nếu có người phụ trách, người này phải là thành viên của workspace chứa project. |
| BR-05 | Tên workspace/project dài 1–100 ký tự, tiêu đề task 1–150 và nội dung bình luận 1–2.000. Ưu tiên gồm `LOW`, `MEDIUM`, `HIGH`, `URGENT`. API dùng thời gian UTC, UI hiển thị giờ địa phương. |
| BR-06 | Sửa hoặc di chuyển task gửi `expectedVersion`. Server trả `409` nếu phiên bản đã cũ. Di chuyển và sắp xếp task được lưu trong giao dịch. App chặn gửi trùng trên cùng task trong lúc move đang chờ. Với `409`, app tải lại dữ liệu trước khi người dùng thử lại. Nếu thao tác kéo thả gặp lỗi khác, app hoàn tác cập nhật dự đoán và cho phép thử lại. |
| BR-07 | Server ghi lịch sử dạng bổ sung bản ghi cho các thao tác tạo, sửa, di chuyển và thay đổi người phụ trách. Lịch sử ghi người thao tác và thời điểm. |
| BR-08 | App đọc được dữ liệu Room đã đồng bộ của đúng tài khoản. Các thao tác ghi dữ liệu công việc bị khóa khi offline. Khi chưa có cache, app cho biết cần kết nối để tải dữ liệu. Đăng xuất xóa phiên cục bộ và cách ly cache của tài khoản đó. |
| BR-09 | Khi access token hết hạn, app làm mới phiên với tối đa một lần refresh đang chạy tại một thời điểm. Backend xoay refresh token và vô hiệu token cũ. Android lưu refresh token đã mã hóa bằng Android Keystore. |

## 6. Quyết định đề xuất để duyệt

| Mã | Quyết định và lý do |
| --- | --- |
| QD-01 | Lấy thứ tự ưu tiên trong bảng chức năng và hợp đồng API v1.0 làm căn cứ. P0 thêm người dùng hiện hữu bằng email chính xác. Lời mời chờ chấp nhận thuộc P2 theo `FR-16`, dù mục 1.3 nêu P1. P0 bình luận gồm tạo và xem, vì mục 4.1 để xóa bình luận tùy phạm vi và mục 5.5 chỉ có API tạo/xem. |
| QD-02 | Member được sửa project và thêm/đổi tên cột như một phần của cộng tác. Xóa project dành cho Owner. Xóa task dành cho Owner hoặc người tạo task còn là thành viên. |
| QD-03 | Khi xóa thành viên, giữ tài khoản và các tham chiếu tác giả của họ. Server gỡ người phụ trách trên các task liên quan trong workspace, tăng `version` và ghi audit với Owner là người thực hiện trong cùng giao dịch. Client loại bỏ dữ liệu mất quyền khi lần đồng bộ online xác nhận quyền đã bị thu hồi. Trong lúc offline, quyền thu hồi chỉ có thể được phát hiện sau khi kết nối lại. |
| QD-04 | Xóa workspace, project hoặc task sẽ xóa hẳn nhánh dữ liệu liên quan, gồm bình luận và lịch sử của task bị xóa. Chọn quy tắc này cho MVP và ghi rõ hệ quả đối với lưu trữ lịch sử. |
| QD-05 | Cho phép kéo đổi thứ tự trong cùng cột. `targetIndex` tính từ 0 theo thứ tự hiện tại trên server, sau khi loại task đang kéo khỏi danh sách nếu di chuyển trong cùng cột. Vị trí ngoài phạm vi trả `422`. Các task lân cận có thể đổi vị trí khi đánh lại thứ tự. Client đối chiếu hoặc tải lại board để nhận vị trí mới. |
| QD-06 | Khi refresh token bị server từ chối dứt khoát, app kết thúc phiên và chặn truy cập cache của tài khoản đó. Khi refresh gặp lỗi kết nối, timeout hoặc lỗi server tạm thời, app giữ phiên cục bộ để người dùng tiếp tục đọc cache và thử lại. Đăng xuất offline vẫn kết thúc phiên cục bộ ngay, còn việc thu hồi token server phụ thuộc kết nối. |

## 7. Tiêu chí nghiệm thu và truy vết

| Mã | Tình huống cần xác minh | Yêu cầu |
| --- | --- | --- |
| AC-01 | Đăng ký, đăng nhập, mở lại app, làm mới khi token hết hạn, về login khi refresh bị thu hồi. Sau logout online, gọi refresh với token cũ bị từ chối. | FR-01, FR-11 |
| AC-02 | Dùng hai tài khoản để thêm thành viên và xem workspace. Gọi API quản lý thành viên trực tiếp bằng tài khoản Member bị từ chối. Tạo project có đủ ba cột mặc định. | FR-02–05 |
| AC-03 | Tạo và giao task, sửa nội dung, kéo `To Do` sang `In Progress`, rồi tải lại. Với phản hồi `500` hoặc `409`, giao diện giữ task và khôi phục hoặc đồng bộ vị trí đúng. | FR-05–07, FR-11 |
| AC-04 | Tạo bình luận, tải lại và thấy bình luận. Kiểm tra lịch sử tạo/sửa/di chuyển/giao task hiển thị đúng hành động, người thực hiện và thời điểm. | FR-06–08 |
| AC-05 | Tìm và kết hợp bộ lọc, sau đó xóa lọc. Mở board đã sync với chế độ máy bay, kiểm tra giới hạn ghi, bật mạng và làm mới. Đăng xuất tài khoản A rồi đăng nhập tài khoản B trên cùng thiết bị để kiểm tra cách ly cache. | FR-03, FR-09–11 |
| AC-06 | Từ bản checkout mới, làm theo README để chạy backend và Android debug với dữ liệu demo. Chạy test backend, unit test Android, ít nhất một UI test và kiểm tra GitHub Actions. | FR-12 và các yêu cầu bàn giao |

## 8. Yêu cầu phi chức năng và bàn giao

- **Nền tảng.** Android dùng Kotlin native, XML Views, ViewBinding và MVVM + Clean Architecture. Backend REST dùng TypeScript và PostgreSQL, cache Android dùng Room.
- **Bảo mật.** Server băm mật khẩu và refresh token, xác thực và kiểm tra quyền trên từng tài nguyên, kiểm tra đầu vào và giới hạn tốc độ ở luồng xác thực. Token và mật khẩu nằm ngoài log. Bản release dùng HTTPS.
- **Trải nghiệm và khả năng tiếp cận.** Giao diện nêu rõ nội dung đang tải, rỗng, lỗi có thể thử lại và dữ liệu đã lưu. Trường nhập và nút biểu tượng có nhãn phù hợp. Bố cục hỗ trợ màn hình khoảng 360–440dp và cỡ chữ hệ thống.
- **Độ ổn định.** I/O chạy ngoài luồng giao diện. Request đang chờ có trạng thái rõ và chặn gửi trùng. Danh sách lớn có phân trang theo API và thứ tự ổn định.
- Mỗi phase cần báo cáo phạm vi, lệnh đã chạy, kết quả và giới hạn còn tồn tại. Nghiệm thu sản phẩm dựa trên API, app và dữ liệu chạy thực tế.
- Bàn giao có README tiếng Anh, hướng dẫn môi trường và tài khoản demo, tài liệu API, sơ đồ kiến trúc, test/CI, kịch bản demo và APK theo khả năng ký build.
