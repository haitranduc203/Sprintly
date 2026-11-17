# SPRINTLY — PRODUCT & IMPLEMENTATION SPECIFICATION

> **Tài liệu duy nhất giao cho Codex triển khai project**  
> Phiên bản: 1.0 — 17/11/2026  
> Mục tiêu: Portfolio ứng tuyển **Fresher Android Developer**; kế hoạch 8 tuần.  
> Bắt buộc: **Android Native Kotlin + XML truyền thống (KHÔNG dùng Jetpack Compose)**.  
> Độ khó mục tiêu: ~4.5/5; ưu tiên sản phẩm chạy thực tế, test được, giải thích được khi phỏng vấn.
>
> Phạm vi MVP và quy tắc nghiệp vụ đã duyệt ngày 17/11/2026. [Quyết định nghiệp vụ](Sprintly_BA.md#6-quyết-định-đã-duyệt)

---

## 0. HƯỚNG DẪN CHO CODEX (ĐỌC TRƯỚC KHI CODE)

### 0.1. Vai trò và nhiệm vụ

Bạn là một Senior Android Engineer kiêm Tech Lead. Hãy xây dựng **Sprintly**, ứng dụng quản lý công việc nhóm theo mô hình Kanban, bằng Kotlin/XML, sử dụng MVVM + Clean Architecture, có backend REST thật, local cache, test và README.

**File này là nguồn yêu cầu chính.** Nếu repo đã tồn tại, phải đọc cấu trúc và code trước khi thay đổi. Nếu repo trống, tạo project từ đầu. Không tự ý đổi thành Jetpack Compose, Flutter, React Native hoặc app chỉ có UI giả.

### 0.2. Quy tắc triển khai

1. **Làm theo phase** ở mục 13, không cố code hết toàn bộ trong một lần chạy. Bắt đầu bằng Phase 0, rồi Phase 1; tiếp tục từng phase khi phase trước chạy/test được.
2. Sau mỗi phase: báo cáo file đã tạo/sửa, tính năng hoạt động, lệnh build/test đã chạy và kết quả thực tế, lỗi hoặc giới hạn còn tồn tại, bước kế tiếp. Không tuyên bố hoàn tất nếu chưa xác minh.
3. Ưu tiên **MUST (P0)** trước **SHOULD (P1)** và **OPTIONAL (P2)**. Nếu thiếu thời gian, giữ P0 hoạt động end-to-end, có seed data và account demo.
4. Không hardcode API token, mật khẩu, secret hoặc URL môi trường production. Cấu hình qua `.env.example`, `local.properties` (không commit bí mật), BuildConfig và hướng dẫn rõ.
5. Không dùng pseudo-code cho feature P0; API, Room entities, repository, ViewModel và UI phải được nối thật. Không để nút bấm “cho có”.
6. Bám tiêu chí nghiệm thu/Definition of Done. Viết test cho nghiệp vụ quan trọng. Ghi trade-off kỹ thuật trong README hoặc `docs` nếu được phép tạo nhiều file trong repository.
7. Hãy dùng phiên bản ổn định tương thích với nhau tại thời điểm triển khai; kiểm tra dependency/API docs chính thức trước khi chọn phiên bản. Không bịa phiên bản mới nhất.
8. Giữ code dễ đọc cho một Fresher học từ project: tên rõ ràng, phân lớp đúng trách nhiệm, comment giải thích quyết định khó, tránh abstraction không mang lại giá trị.
9. Không cần yêu cầu người dùng trả lời các câu hỏi không thiết yếu; áp dụng default ở tài liệu này và ghi chú quyết định. Nếu có blocker thực sự (credential/SDK/path), chỉ rõ cách khắc phục.

### 0.3. Tiêu chí thành công cao nhất

> Cài ứng dụng trên emulator/thiết bị → đăng nhập → tạo workspace → tạo project → tạo task → giao task → đổi cột Kanban bằng drag & drop → xem bình luận/lịch sử → tắt mạng vẫn thấy dữ liệu đã tải → mở mạng tải lại dữ liệu chính xác → logout/login không rò dữ liệu giữa người dùng.

---

## 1. PRODUCT OVERVIEW

### 1.1. Bài toán

Một nhóm phát triển phần mềm nhỏ cần quản lý project và task, phân công thành viên, deadline, mức độ ưu tiên, tiến độ Kanban và lịch sử cập nhật trong một ứng dụng Android đơn giản.

### 1.2. Đối tượng và vai trò

- **Owner**: tạo và quản lý workspace, thêm hoặc gỡ thành viên, quản lý project và task.
- **Member**: xem các project thuộc workspace, tạo/sửa task theo chính sách, cập nhật trạng thái, bình luận.
- **Người không thuộc workspace**: tuyệt đối không được truy cập tài nguyên của workspace, kể cả đoán được ID qua URL.

Cho MVP, role gồm `OWNER`, `MEMBER`. Chưa cần admin cấp hệ thống hay invite qua email thật.

### 1.3. Luồng người dùng cốt lõi

1. Đăng ký → đăng nhập → xem workspace.
2. Tạo workspace → Owner tra cứu email khớp chính xác và thêm tài khoản đã đăng ký. Lời mời chờ chấp nhận thuộc P2 (`FR-16`).
3. Tạo project → tạo 3 cột mặc định `To Do`, `In Progress`, `Done`.
4. Tạo task có tiêu đề, mô tả, deadline, priority, người phụ trách.
5. Xem board, kéo task từ cột này sang cột khác → cập nhật server.
6. Mở task detail → sửa trường, bình luận, xem audit trail.
7. Tìm kiếm/lọc task theo keyword, status/column, assignee, priority.
8. Mất mạng → danh sách/board đã cache vẫn hiển thị (read-only MVP). Quay lại online → làm mới dữ liệu.

### 1.4. Phạm vi tính năng

| ID | Module | Yêu cầu | Ưu tiên |
|---|---|---|---|
| FR-01 | Auth | Đăng ký, đăng nhập, logout, phiên đăng nhập, refresh token | P0 |
| FR-02 | Workspace | Tạo, liệt kê và xem workspace. Owner được sửa và xóa workspace. | P0 |
| FR-03 | Members | Xem thành viên. Owner tra cứu email khớp chính xác, thêm tài khoản đã đăng ký và gỡ thành viên. | P0 |
| FR-04 | Project | CRUD project trong workspace | P0 |
| FR-05 | Board | Danh sách cột, 3 cột mặc định, rename/add column | P0 |
| FR-06 | Task | CRUD; assignee, priority, deadline, description | P0 |
| FR-07 | Kanban | Kéo thả và dùng menu Di chuyển trong hoặc giữa các cột, cập nhật UI dự đoán và phục hồi khi lỗi. | P0 |
| FR-08 | Task detail | Tạo và xem bình luận, xem lịch sử thay đổi của task đang tồn tại. | P0 |
| FR-09 | Search & Filter | Tìm keyword; lọc cột/priority/assignee | P0 |
| FR-10 | Cache | Room cache workspace/project/board/task; xem offline | P0 |
| FR-11 | State handling | Loading/empty/error/retry, refresh, rotation | P0 |
| FR-12 | Tests & CI | Unit tests, API tests, ít nhất một UI/instrumented test, build CI | P0 |
| FR-13 | Notification | FCM khi được giao task | P1 |
| FR-14 | Realtime | Socket.IO/WebSocket cập nhật task, reconnect + catch-up | P1 |
| FR-15 | Offline write | Queue tạo/sửa task offline, retry, conflict policy | P2 |
| FR-16 | Invite | Invite link/email, pending invite/accept/reject | P2 |
| FR-17 | Attachments | Đính kèm ảnh/file cho task | P2 |

**Định nghĩa MVP:** FR-01 đến FR-12 đều hoạt động. FR-13 đến FR-17 không được làm hỏng MVP; nếu hết thời gian, ghi lại là backlog.

### 1.5. Ngoài phạm vi MVP

Hai màn **Tổng quan**, **Lịch công việc** và thanh phần trăm hoàn thành dự án thuộc phạm vi sau P0. Khi triển khai phần trăm hoàn thành, xác định cột hoàn thành bằng ID ổn định và định nghĩa công thức tính.

Không làm payment, chat riêng, calendar tích hợp Google, AI agent, admin web, push notification phức tạp, CRDT, realtime concurrent editing toàn diện, đa tenant trả phí hoặc microservices.

---

## 2. TECH STACK MẶC ĐỊNH

### 2.1. Android (bắt buộc)

| Khía cạnh | Công nghệ |
|---|---|
| Ngôn ngữ | Kotlin |
| UI | Android XML Views, Fragment, ViewBinding, Material Components |
| Kiến trúc | MVVM + Clean Architecture có phân chia Presentation/Domain/Data |
| DI | Hilt |
| HTTP | Retrofit + OkHttp + Kotlin serialization hoặc Gson (chọn một, dùng nhất quán) |
| Async | Coroutines, `suspend`, Flow, StateFlow, SharedFlow |
| Local DB | Room |
| Preferences | DataStore Preferences |
| Background | WorkManager (cache refresh/P1 sync) |
| Navigation | Jetpack Navigation Component for Fragments |
| Lists | RecyclerView, ListAdapter, DiffUtil; ItemTouchHelper cho drag & drop |
| Images | Glide (avatar/ảnh trong UI nếu có) |
| Testing | JUnit, MockK (hoặc Mockito), Turbine cho Flow nếu cần, Espresso cho UI |
| Build | Gradle Kotlin DSL, version catalog nếu hợp lý |
| CI | GitHub Actions |

**Định hướng:** Min SDK 26, target/compile SDK theo bộ Android SDK ổn định hiện có và yêu cầu nền tảng. Single Activity + Fragments. Không dùng Compose. Hỗ trợ portrait; tablet landscape/large screen là P2.

### 2.2. Backend (mặc định để Codex triển khai không bị mơ hồ)

MVP sử dụng **REST API tự xây** trong cùng một monorepo:

- Node.js + TypeScript + Fastify (hoặc Express nếu Fastify gây blocker), PostgreSQL, Prisma ORM.
- JWT access token ngắn hạn + refresh token ngẫu nhiên được hash trong database, refresh rotation.
- bcrypt/argon2 cho password hash, validation schema, centralized error handler.
- Docker Compose cho PostgreSQL phục vụ local development; Prisma migration + seed.
- P1 realtime: Socket.IO; P1 push: Firebase Cloud Messaging, chỉ khi P0 ổn định.

**Lý do:** Chủ động chuẩn hóa API cho Retrofit, thực hành auth/interceptor, 401/409, permission, audit và optimistic updates. Codex phải ghi rõ các lệnh chạy backend và cấu hình emulator truy cập backend (`10.0.2.2` nếu backend chạy trên máy host). Không dùng Supabase cho MVP trừ khi người dùng yêu cầu thay đổi stack.

### 2.3. Cấu trúc repo đề xuất

```text
sprintly/
├── android/                     # Android Studio project
│   ├── app/
│   ├── gradle/
│   ├── build.gradle.kts
│   └── settings.gradle.kts
├── backend/
│   ├── src/
│   │   ├── modules/
│   │   │   ├── auth/
│   │   │   ├── users/
│   │   │   ├── workspaces/
│   │   │   ├── projects/
│   │   │   ├── boards/
│   │   │   ├── tasks/
│   │   │   └── comments/
│   │   ├── middlewares/
│   │   ├── plugins/
│   │   └── server.ts
│   ├── prisma/
│   │   ├── schema.prisma
│   │   └── seed.ts
│   ├── package.json
│   └── .env.example
├── .github/workflows/
├── docs/                        # Architecture/API/test notes nếu cần
├── docker-compose.yml
├── README.md
└── .gitignore
```

**Lưu ý:** Đây là cấu trúc code REPO mà Codex có thể tạo; người dùng chỉ cần gửi **file specification này** để khởi động yêu cầu.

---

## 3. ANDROID ARCHITECTURE & CODING CONVENTIONS

### 3.1. Kiến trúc luồng dữ liệu

```text
Fragment / ViewBinding
     ↓ user action / collect with repeatOnLifecycle
ViewModel (StateFlow<UiState>, SharedFlow<UiEffect>)
     ↓
UseCase (domain business logic)
     ↓
Repository interface (domain)
     ↓
Repository implementation (data)
  ┌───────────────┴────────────────┐
RemoteDataSource             LocalDataSource
Retrofit API                 Room DAO
  └───────────────┬────────────────┘
         Mapper DTO ↔ Entity ↔ Domain Model
```

- `ViewModel` không giữ reference `Activity`, `Fragment`, View hay `Context` (ngoại trừ `Application` nếu cần và có giải thích).
- Fragment chỉ render UI và chuyển event; `viewLifecycleOwner.repeatOnLifecycle(STARTED)` để collect Flow.
- UI state immutable, `sealed interface` cho loading/content/empty/error hoặc state model phù hợp.
- One-shot events dùng effect/channel/shared flow có cách xử lý lifecycle rõ ràng; không đưa navigation event vào StateFlow vô thời hạn.
- Repository là nguồn truy cập dữ liệu tập trung; DTO không xuất hiện trong UI.
- Mapper tách remote DTO, Room Entity và Domain Model; dùng `Result`/sealed error model nhất quán.
- Mỗi API call có xử lý success, offline, timeout, unauthorized, forbidden, conflict, server error.

### 3.2. Gợi ý package structure (single Gradle app module ở MVP)

```text
com.sprintly
├── SprintlyApplication.kt
├── MainActivity.kt
├── core/
│   ├── common/         # Result, dispatcher, errors, extensions
│   ├── network/        # Retrofit, interceptors, authenticator
│   ├── database/       # Room database, converters, dao
│   ├── datastore/      # session/preferences
│   ├── designsystem/   # themes, reusable XML views/adapters
│   └── di/             # Hilt modules
├── domain/
│   ├── model/
│   ├── repository/     # Interfaces
│   └── usecase/
├── data/
│   ├── remote/         # API service + DTO
│   ├── local/          # Room entities + DAOs
│   ├── mapper/
│   └── repository/     # Implementations
└── feature/
    ├── auth/
    ├── workspace/
    ├── project/
    ├── board/
    ├── task/
    ├── search/
    └── settings/
```

Nếu phát sinh nhu cầu rõ ràng, có thể tách modules `core`, `data`, `domain`, `feature` ở cuối dự án; **không modularize quá sớm**.

### 3.3. Naming và style

- Kotlin: `*Fragment`, `*ViewModel`, `*UiState`, `*UseCase`, `*Repository`, `*RepositoryImpl`, `*Dto`, `*Entity`, `*Dao`.
- XML: `fragment_*`, `item_*`, `dialog_*`, `view_*`.
- String/Color/Dimen resources thay vì hardcode tại UI; hỗ trợ dark mode nếu dư thời gian P1.
- Trạng thái task là **board column ID**, không đóng cứng business logic theo tên cột.
- Dùng UTC ISO-8601 cho timestamps trong API, định dạng giờ địa phương khi hiển thị.

### 3.4. Auth phía Android

- Request interceptor gắn access token cho endpoint có xác thực.
- OkHttp Authenticator (hoặc cơ chế equivalent được giải thích) xử lý 401, thực hiện refresh chỉ **một lần tại một thời điểm** (mutex/single-flight), retry request tối đa hợp lý, tránh vòng lặp 401.
- Refresh API dùng client không tự retry qua cùng Authenticator để tránh recursion.
- Access token ngắn hạn; refresh token lưu **mã hóa bằng khóa Android Keystore** (không plaintext trong DataStore). Không ghi token vào log.
- Khi refresh gặp lỗi mạng, timeout hoặc `5xx`, giữ phiên cục bộ và chỉ cho đọc cache của tài khoản hiện tại. Mở lại thao tác ghi sau khi xác thực phiên thành công. Nếu máy chủ đã xoay token nhưng phản hồi bị mất, lần thử sau có thể nhận xác nhận token cũ đã vô hiệu và cần đăng nhập lại.
- Khi `/auth/refresh` trả `REFRESH_TOKEN_INVALID` vì token hết hạn, bị thu hồi hoặc không hợp lệ, khóa quyền đọc cache ngay, xóa phiên và cache của tài khoản, rồi điều hướng về đăng nhập một lần.
- Khi logout, giữ token trong bộ nhớ đủ để gửi yêu cầu thu hồi, đồng thời kết thúc phiên cục bộ và khóa cache ngay. Server thu hồi phiên khi xử lý thành công yêu cầu logout. Ràng buộc việc ghi token và cache với đúng lần đăng nhập đã gửi yêu cầu để phản hồi đến muộn không khôi phục phiên hoặc dữ liệu cũ.

---

## 4. DATABASE MODEL & INVARIANTS

### 4.1. Backend PostgreSQL entities

| Table | Columns cốt lõi | Ghi chú |
|---|---|---|
| `users` | `id UUID PK`, `email UNIQUE`, `display_name`, `password_hash`, `created_at`, `updated_at` | Email lowercase, trim |
| `sessions` | `id UUID PK`, `user_id FK`, `refresh_token_hash UNIQUE`, `expires_at`, `revoked_at`, `created_at` | Rotation/revoke |
| `workspaces` | `id UUID PK`, `name`, `description?`, `created_by FK`, timestamps | Owner có membership OWNER |
| `workspace_members` | `workspace_id FK`, `user_id FK`, `role OWNER/MEMBER`, `joined_at`; composite unique | Quyền truy cập |
| `projects` | `id UUID PK`, `workspace_id FK`, `name`, `description?`, `board_version`, timestamps | Thuộc workspace, `board_version` bắt đầu từ 1 |
| `board_columns` | `id UUID PK`, `project_id FK`, `name`, `position INT`, timestamps | Mặc định 3 cột |
| `tasks` | `id UUID PK`, `project_id FK`, `column_id FK`, `title`, `description?`, `priority`, `assignee_id?`, `due_at?`, `position INT`, `version INT`, `created_by`, timestamps | Optimistic concurrency |
| `comments` | `id UUID PK`, `task_id FK`, `author_id FK`, `body`, `created_at`, `updated_at` | P0 tạo và xem |
| `task_activities` | `id UUID PK`, `task_id FK`, `actor_id FK`, `action`, `payload JSONB`, `created_at` | Chỉ thêm bản ghi trong vòng đời task |
| `idempotency_requests` | Tài khoản, loại thao tác, tài nguyên cha, khóa yêu cầu, mã băm payload và kết quả | Dùng cho tạo task và gửi bình luận. Xem [ERD](Sprintly_ERD.md). |

Indexes tối thiểu: workspace-members by `user_id`, projects by `workspace_id`, columns by `(project_id,position)`, tasks by `(project_id,column_id,position)`, tasks by `(assignee_id,due_at)`, comments/activities by `(task_id,created_at)`.

### 4.2. Invariants và validation

- User phải thuộc workspace của project/task mới được đọc dữ liệu.
- Chỉ Owner được sửa hoặc xóa workspace, tra cứu email để thêm người, thêm hoặc gỡ thành viên và xóa project. Owner và Member được tạo/sửa project, thêm/đổi tên cột, tạo/sửa/giao/di chuyển task và bình luận. Owner hoặc người tạo task còn là thành viên được xóa task. Ghi ma trận quyền ở README.
- `assignee_id` phải là member thuộc workspace; `column_id` phải thuộc cùng `project_id` của task.
- Workspace phải luôn có ít nhất một OWNER; không cho xóa owner cuối cùng.
- Khi gỡ thành viên, giữ tài khoản, bình luận và tham chiếu tác giả. Trong cùng giao dịch, gỡ phân công trên các task thuộc workspace, tăng phiên bản task, ghi lịch sử thay đổi người phụ trách với Owner là người thực hiện rồi xóa membership. Tuần tự hóa thao tác này với giao việc.
- Xóa workspace, project hoặc task là xóa vĩnh viễn cùng dữ liệu con, gồm bình luận và lịch sử của các task bị xóa. UI nêu rõ hệ quả trong hộp thoại xác nhận.
- Tạo project tạo 3 board columns mặc định trong **cùng transaction**.
- `title` task 1–150 ký tự, workspace/project name 1–100, comment 1–2000; validate cả client/server.
- Priority enum: `LOW`, `MEDIUM`, `HIGH`, `URGENT`.
- Audit activity phải ghi cho create/update/move/assign task, không dựa vào UI tự bịa lịch sử.
- Các mutating API kiểm tra quyền **server-side**, không chỉ ẩn nút trong Android.
- Trả `409` khi phiên bản task hoặc phiên bản thứ tự board không còn khớp. Client hoàn tác cập nhật dự đoán nếu có, tải lại dữ liệu và để người dùng xác nhận thao tác trên dữ liệu hiện tại.
- Tạo, xóa hoặc đổi thứ tự task, đổi cột của task và thêm cột phải cập nhật `board_version` trong cùng giao dịch với thay đổi tương ứng. Dùng vị trí nguyên liên tục từ 0 theo từng cột. Tuần tự hóa các thay đổi trước khi kiểm tra phiên bản và cập nhật thứ tự.

### 4.3. Room cache (P0)

Room entities: `CachedWorkspaceEntity`, `CachedProjectEntity`, `CachedColumnEntity`, `CachedTaskEntity`, `CachedMemberEntity`, `CachedCommentEntity` (comment tùy lượng dữ liệu). Mỗi bản ghi cache có `accountId` hoặc database riêng theo tài khoản. Dữ liệu truy vấn qua Flow. Lưu `boardVersion`, cột và thứ tự task của snapshot trong cùng giao dịch khi làm mới board.

**Offline P0 = đọc dữ liệu đã đồng bộ**. Người dùng không được tạo/sửa task offline như thể chắc chắn đã lưu; UI disable action và báo trạng thái offline. Queue ghi offline + conflict resolver là **P2 riêng**, không mô tả P0 như đã hỗ trợ.

---

## 5. REST API CONTRACT (v1)

Base URL: `/api/v1`. `Content-Type: application/json`. Authorization: `Bearer <accessToken>` với các endpoint được bảo vệ. IDs là UUID. Timestamps UTC ISO-8601. Endpoint phải có validation và server authorization.

### 5.1. Quy ước response

Success có thể trả JSON object trực tiếp hoặc `{ "data": ... }` nhưng **chọn một định dạng duy nhất**. Đề xuất `{ "data": ... }`, list trả `{ "data": [...], "meta": { "page": 1, "pageSize": 20, "total": 42 } }`.

Lỗi tiêu chuẩn:

```json
{
  "error": {
    "code": "TASK_VERSION_CONFLICT",
    "message": "Task has changed. Reload and try again.",
    "details": {}
  }
}
```

Mã HTTP: `400` validation, `401` missing/expired token, `403` forbidden, `404` resource not found, `409` conflict, `422` invalid business condition, `429` rate-limited, `500` internal error.

### 5.2. Authentication endpoints

| Method | Path | Body/Query | Result |
|---|---|---|---|
| POST | `/auth/register` | `{email,displayName,password}` | user + tokens |
| POST | `/auth/login` | `{email,password}` | user + tokens |
| POST | `/auth/refresh` | `{refreshToken}` | Trả access token mới và refresh token đã xoay. Trả `401` với `REFRESH_TOKEN_INVALID` khi token hết hạn, bị thu hồi hoặc không hợp lệ. |
| POST | `/auth/logout` | `{refreshToken}` (authorized session semantics documented) | success, revoke session |
| GET | `/users/me` | — | current user |

Example login:

```json
// POST /api/v1/auth/login
{ "email": "owner@sprintly.demo", "password": "DemoPass123!" }

// 200
{
  "data": {
    "user": { "id": "<uuid>", "email": "owner@sprintly.demo", "displayName": "Demo Owner" },
    "accessToken": "<jwt>",
    "refreshToken": "<opaque-token>",
    "expiresIn": 900
  }
}
```

### 5.3. Workspaces & members

| Method | Path | Ý nghĩa |
|---|---|---|
| GET | `/workspaces` | Danh sách workspace của current user |
| POST | `/workspaces` | Tạo workspace `{name,description?}` |
| GET | `/workspaces/:workspaceId` | Workspace detail |
| PATCH | `/workspaces/:workspaceId` | Owner sửa name/description |
| DELETE | `/workspaces/:workspaceId` | Owner xóa workspace; explicit confirm UI |
| GET | `/workspaces/:workspaceId/members` | Danh sách members |
| GET | `/workspaces/:workspaceId/users/search?email=...` | Owner của workspace tra cứu email khớp chính xác sau khi chuẩn hóa để thêm thành viên. Trả tối đa một kết quả với `id`, `displayName`, `email`. |
| POST | `/workspaces/:workspaceId/members` | Owner thêm `{userId,role: "MEMBER"}` |
| DELETE | `/workspaces/:workspaceId/members/:userId` | Owner remove member theo invariants |

### 5.4. Projects & board columns

| Method | Path | Ý nghĩa |
|---|---|---|
| GET | `/workspaces/:workspaceId/projects` | List project |
| POST | `/workspaces/:workspaceId/projects` | Create project; seed 3 columns |
| GET | `/projects/:projectId` | Project detail |
| PATCH | `/projects/:projectId` | Owner hoặc Member sửa tên và mô tả |
| DELETE | `/projects/:projectId` | Owner xóa dự án và dữ liệu con |
| GET | `/projects/:projectId/board` | Trả `boardVersion`, các cột và task đã sắp xếp trong cùng snapshot |
| POST | `/projects/:projectId/columns` | Owner hoặc Member thêm cột `{name}` |
| PATCH | `/columns/:columnId` | Owner hoặc Member đổi tên cột `{name}` |

P0 không cần xóa/reorder cột nếu ảnh hưởng đáng kể tới tiến độ. API board response phải bao gồm `version`, `position`, `assignee`, `priority` cho mỗi task để client render.

### 5.5. Tasks, comments, audit

| Method | Path | Ý nghĩa |
|---|---|---|
| GET | `/projects/:projectId/tasks` | Query `q`, `columnId`, `assigneeId`, `priority`, `page`, `pageSize` |
| POST | `/projects/:projectId/tasks` | Tạo task ở cuối cột `{columnId,title,description?,priority,assigneeId?,dueAt?}` với header `Idempotency-Key` |
| GET | `/tasks/:taskId` | Task detail |
| PATCH | `/tasks/:taskId` | Sửa task với `{expectedVersion,...changes}`. Nếu đổi `columnId`, gửi thêm `expectedBoardVersion` và chèn task vào cuối cột đích theo cùng quy tắc giao dịch và thứ tự của lệnh Di chuyển. |
| DELETE | `/tasks/:taskId` | Owner hoặc người tạo còn là thành viên xóa task cùng bình luận và lịch sử |
| PATCH | `/tasks/:taskId/move` | `{targetColumnId,targetIndex,expectedVersion,expectedBoardVersion}` |
| GET | `/tasks/:taskId/comments` | List comments ascending/descending documented |
| POST | `/tasks/:taskId/comments` | Tạo bình luận `{body}` với header `Idempotency-Key` |
| GET | `/tasks/:taskId/activities` | Audit trail (paginated) |

Move example:

```json
// PATCH /api/v1/tasks/<task-id>/move
{
  "targetColumnId": "<target-column-uuid>",
  "targetIndex": 0,
  "expectedVersion": 3,
  "expectedBoardVersion": 7
}

// 200: {"data":{"id":"<task-id>","columnId":"<target-column-uuid>","position":0,"version":4,"boardVersion":8,...}}
// 409: {"error":{"code":"TASK_VERSION_CONFLICT","message":"..."}}
// 409: {"error":{"code":"BOARD_VERSION_CONFLICT","message":"..."}}
```

`targetIndex` bắt đầu từ 0 trên thứ tự cột đích trong snapshot đã tải. Khi di chuyển trong cùng cột, lấy task đang kéo ra trước khi xác định chỉ số. Với `n` task còn trong danh sách đích, chấp nhận từ 0 đến `n`, gồm vị trí cuối cột. Sau khi kiểm tra phiên bản, trả `422` nếu chỉ số nằm ngoài khoảng hoặc cột đích thuộc project khác.

Với hai API tạo task và gửi bình luận, client sinh một `Idempotency-Key` kiểu UUID cho mỗi lần gửi có chủ ý, giữ nguyên khóa và payload khi thử lại cùng thao tác. Máy chủ kiểm tra quyền hiện tại, kể cả khi trả kết quả cũ. Máy chủ ghi kết quả thành công cùng giao dịch với dữ liệu và lịch sử tạo task nếu có. Khóa có phạm vi theo tài khoản, loại thao tác và tài nguyên cha. Cùng khóa và payload trả lại kết quả thành công đã lưu. Cùng khóa nhưng payload khác trả `409` với `IDEMPOTENCY_KEY_REUSED`. Nếu kết quả đã bị xóa nhưng tài nguyên cha vẫn tồn tại, server trả `404` và giữ khóa để lần thử lại không tạo lại bản ghi. [Ràng buộc và vòng đời dữ liệu](Sprintly_ERD.md)

For security, task-specific endpoints must trace task -> project -> workspace -> membership before returning data. Search is scoped to a project or workspace accessible by current user. Server returns consistent ordering with a stable tie-breaker.

### 5.6. P1 realtime (chỉ sau khi P0 ổn định)

- Client joins authorized `project:<id>` room.
- Event types: `task.created`, `task.updated`, `task.moved`, `task.deleted`, `comment.created` with IDs/version/timestamp.
- Server validates membership before room subscription; never trust client-supplied workspace ID alone.
- Client uses event id/version dedupe; after reconnect, refetch board to recover events missed while offline.
- Realtime UI is **eventual consistency**, REST + database remain source of truth.

### 5.7. P1 push

- FCM device token register/revoke endpoint, notification when assignee changes, tap opens task detail.
- Android 13+ notification runtime permission where applicable; account/session changes invalidate device registrations.
- FCM is not a substitute for persisted server task state.

---

## 6. ANDROID SCREENS & UX REQUIREMENTS

### 6.1. Danh sách màn hình P0

| Screen | Thành phần UI chính | Hành vi |
|---|---|---|
| Splash / Session Router | Logo/progress | Kiểm tra session → Login hoặc Workspace List; không block main thread |
| Register | Name, email, password, confirm | Validation, register, inline error |
| Login | Email, password, loading | Login, errors, navigate, session persist |
| Workspace List | RecyclerView, FAB, empty state | Xem/tạo/chọn workspace |
| Workspace Detail | Members, projects tabs/list | Xem members, thêm/xóa nếu owner, tạo project |
| Project List | Project cards, add dialog | List/create/edit/delete project |
| Board | 3+ columns, horizontal board, task cards | Drag/drop task, add task, open detail, refresh |
| Task Detail | Title, description, assignee, priority, due date, comments, audit | View/edit/save/comment |
| Search/Filter | Search input, filter selectors, results | Debounce keyword, filter, reset |
| Settings/Profile | Current user, logout | Clear session/isolated cached data |

### 6.2. UI state chung

Bắt buộc hiển thị rõ: `loading`, `content`, `empty`, `error + retry`, `offline cached`, submit success/failure. Không hiển thị loader vô hạn. Disable nút submit khi đang request. Điều hướng không tạo nhiều bản sao của cùng một screen khi double-tap.

Nếu tạo task hoặc gửi bình luận bị timeout, giữ bản nháp và báo kết quả chưa được xác nhận. Khi người dùng thử lại online, gửi cùng khóa và payload của thao tác đang chờ. Hiển thị dữ liệu chính thức khi server xác nhận.

### 6.3. Kanban drag & drop

- Board là các cột theo trục ngang, mỗi cột chứa RecyclerView tasks.
- Dùng `ItemTouchHelper` hoặc custom gesture nếu cần để chuyển task giữa các cột. Nếu cross-list drag/drop bằng ItemTouchHelper đơn giản không đáp ứng được, tự triển khai logic drag target rõ ràng, test bằng hai cột.
- Khi online, kéo thả và menu **Di chuyển** dùng cùng quy tắc từ dữ liệu board đầy đủ. Cập nhật giao diện ngay rồi gọi `PATCH /tasks/:id/move` với phiên bản task và board đã tải.
- Khi API thành công, đối chiếu thứ tự và phiên bản với server. Khi lỗi `409`, hoàn tác cập nhật dự đoán, tải lại board và yêu cầu chọn lại vị trí. Khi biết chắc thao tác ghi thất bại, hoàn tác và cho thử lại. Khi timeout hoặc mất kết nối giữa chừng, đối chiếu board trước khi thử lại và tính vị trí từ dữ liệu mới.
- Chặn thao tác trùng trên cùng task trong lúc request move đang pending; không cho mất task khỏi cả hai cột.
- Recreated screen/rotation không phải là nguyên nhân mất task.

### 6.4. UI guideline

- Thiết kế tối giản, dễ nhìn, Material Components, typography nhất quán, spacing 8dp, touch targets tối thiểu phù hợp.
- Dùng resources cho màu/string/dimen; hỗ trợ màn hình ~360–440dp và font scaling hợp lý.
- Accessibility: content description cho icon, nhãn input, độ tương phản phù hợp.
- Nếu dùng mock avatar/image, không phụ thuộc dịch vụ ảnh không ổn định để màn hình render được.

---

## 7. OFFLINE & SYNC BEHAVIOR

### 7.1. MVP P0: offline read-only

1. Sau khi fetch online, lưu workspace/project/board/task vào Room.
2. Với dữ liệu có cache, UI đọc/observe Room và hiện nội dung ngay, đồng thời thực hiện remote refresh khi có mạng.
3. Nếu làm mới dữ liệu gặp lỗi mạng tạm thời, giữ cache và hiển thị banner `Đang xem dữ liệu đã lưu`. Nếu refresh phiên gặp lỗi tạm thời, áp dụng chế độ chỉ đọc theo §3.4 đến khi xác thực phiên thành công.
4. Nếu chưa từng sync, hiện empty offline state với hướng dẫn thử lại khi có mạng.
5. Khi mạng quay trở lại, refresh tại lần mở/resume/pull-to-refresh; có thể dùng observer network an toàn, nhưng không spam request.
6. **Không cho thao tác write offline trong MVP P0**; có thông báo rõ ràng.
7. Logout hoặc xác nhận phiên không hợp lệ khóa cache ngay. Phản hồi từ phiên cũ bị bỏ, kể cả khi cùng tài khoản đăng nhập lại. Loại cache thuộc phạm vi mất quyền khi server xác nhận đã thu hồi quyền truy cập.

### 7.2. P2: offline write sync (không làm trừ khi P0 hoàn chỉnh)

- Thêm `PendingMutationEntity` với `mutationId UUID`, account ID, entity ID, operation, payload JSON, baseVersion, createdAt, retryCount, syncState.
- WorkManager dùng constraints `CONNECTED`, exponential backoff, idempotency key server.
- Conflict handling: reject hoặc manual resolution đối với 409; **không tự động last-write-wins** cho thay đổi quan trọng nếu chưa giải thích được.
- UI có trạng thái `Pending Sync`/`Sync Failed`, không trộn lẫn local pending với trạng thái đã được server xác nhận.

---

## 8. TESTING & QUALITY

### 8.1. Android unit test tối thiểu

- Login ViewModel: loading → success/error, không double-submit.
- Nhiều request đồng thời dùng chung một lần refresh. Lỗi tạm thời giữ cache chỉ đọc, refresh bị từ chối dứt khoát kết thúc phiên, phản hồi đến muộn không cập nhật token hoặc cache sau khi đổi phiên.
- Create Task validation: title blank, invalid due date nếu có rule.
- Task Move UseCase/Repository: optimistic success, error rollback, `409` conflict reload.
- Offline repository: cached data hiển thị khi remote lỗi.
- Search/filter: input debounce, filter combinations, clear filter.

### 8.2. Backend tests tối thiểu

- Register/login thành công; invalid credential → 401.
- Refresh rotation: token cũ không thể sử dụng lại sau rotation; logout revoke.
- Workspace member cannot access another workspace → 403/404 consistent.
- Owner được tra cứu email và thêm thành viên trong workspace mình quản lý. Member và người ngoài bị từ chối. Gỡ member cập nhật phân công, phiên bản và lịch sử trong cùng giao dịch, kể cả khi có thao tác giao việc đồng thời.
- Xác minh Owner, người tạo task còn là thành viên và Member khác theo ma trận quyền xóa. Xóa task hoặc tài nguyên cha xóa các bình luận và lịch sử liên quan.
- Di chuyển trong và giữa cột. Phiên bản task hoặc thứ tự board cũ trả `409`, chỉ số ngoài khoảng trả `422`. Kiểm tra trường hợp hai người thao tác trên hai task khác nhau trong cùng board.
- Tạo task và gửi bình luận với `Idempotency-Key`. Mô phỏng mất phản hồi sau commit và hai yêu cầu đồng thời dùng cùng khóa. Cùng khóa và payload chỉ tạo một bản ghi, còn payload khác trả `409`.
- Assignee not workspace member → rejected.
- Project creation seeds 3 columns exactly once.

### 8.3. Instrumented/UI tests

- Ít nhất một Android UI test chạy được trên emulator (ví dụ login với mock HTTP hoặc test screen flow có deterministic setup).
- Có test manual checklist cho end-to-end với backend thật, hai account, trạng thái airplane mode.

### 8.4. Manual test matrix

| Case | Kỳ vọng |
|---|---|
| Login sai password | Có error message, không navigate |
| Access token hết hạn | Refresh thành công thì tiếp tục. Refresh bị từ chối dứt khoát thì về đăng nhập. |
| Refresh phiên gặp lỗi tạm thời | Đọc cache hiện tại và khóa ghi đến khi xác thực lại thành công. |
| Không mạng khi đã cache | Hiện dữ liệu cũ và offline banner |
| Không mạng khi chưa cache | Empty offline state, không crash |
| Kéo task API lỗi | UI rollback, snackbar |
| Kéo task version conflict | Board refresh, task không mất |
| Owner xóa member | Member không còn xem project sau refresh |
| User A logout rồi A hoặc B login | Phản hồi cũ về muộn không ghi lại token hoặc cache của phiên đã kết thúc. |
| Xoay màn hình ở task detail | Không crash; state được khôi phục |
| API trả 500 hoặc request timeout | Có retry; không spinning vô hạn |
| Tạo task hoặc gửi bình luận mất phản hồi sau commit | Thử lại cùng khóa và payload, chỉ có một bản ghi. |
| Background rồi resume | Không duplicate collector/navigation |

---

## 9. SECURITY, PERFORMANCE & RELIABILITY

- Password hash bằng argon2/bcrypt; không lưu plaintext, không đưa hash ra API.
- Refresh token là opaque random, lưu **hash** trong DB, có revoke/expire/rotate; access JWT ngắn hạn, check signature/expiry.
- CORS cấu hình hợp lý cho backend nếu cần, rate limiting auth cơ bản, validate dữ liệu đầu vào, không trả stack trace cho mobile client.
- SQL qua Prisma parameterized, không nối string query từ user.
- Enforce authorization mọi route server; ID enumeration không lộ tài nguyên khác workspace.
- HTTP cleartext chỉ cho **debug local** nếu thật sự cần; release HTTPS.
- Android không block main thread bằng network/DB; tránh memory leak với Fragment binding (`onDestroyView` cleanup), `repeatOnLifecycle`.
- RecyclerView dùng DiffUtil; tránh `notifyDataSetChanged()` tràn lan.
- List API có phân trang nếu lượng task lớn; default pageSize 20, max 100. Board MVP có thể trả toàn bộ task giới hạn hợp lý và tài liệu hóa giới hạn; với data lớn cân nhắc lazy load mỗi cột.
- Không log PII/token/password; debug logs có thể bật/tắt bằng build type.
- Dùng `ConnectivityManager` đúng lifecycle và ngừng observer khi không cần.

---

## 10. ACCEPTANCE CRITERIA (CÁC LUỒNG PHẢI CHẠY)

### AC-01 Auth

- Một user đăng ký, login, đóng/mở app vẫn giữ phiên hợp lệ.
- Access token hết hạn thì tự refresh. Lỗi tạm thời giữ cache chỉ đọc. Khi máy chủ xác nhận refresh token hết hạn, bị thu hồi hoặc không hợp lệ, khóa cache và về đăng nhập.
- Logout khóa phiên trên thiết bị ngay cả khi offline. Sau logout được server xác nhận, refresh token của phiên đã thu hồi bị từ chối.

### AC-02 Workspace/Project

- Owner tạo workspace và thêm tài khoản đã đăng ký qua email khớp chính xác. Member thấy workspace khi đăng nhập. Khi bị gỡ, tài khoản mất quyền truy cập và các task đang giao cho họ chuyển về Chưa giao, có ghi lịch sử.
- Tạo project tạo sẵn 3 cột: To Do, In Progress, Done.
- Gọi trực tiếp API bằng tài khoản thiếu quyền tra cứu email, quản lý member hoặc xóa tài nguyên bị từ chối.

### AC-03 Task/Kanban

- Owner/member tạo task, assign member hợp lệ, sửa title/description/priority/due date.
- Kéo task sang cột khác và đổi vị trí trong cùng cột. Tải lại vẫn thấy thứ tự chính thức. Khi người khác đổi thứ tự đích trước khi gửi move, app nhận `409`, tải lại và cho chọn lại vị trí.
- Khi move lỗi hoặc mất phản hồi, UI giữ task và phục hồi vị trí từ trạng thái đã biết hoặc đối chiếu lại board. Khi tạo task mất phản hồi sau commit, thử lại cùng khóa chỉ tạo một task và một bản ghi lịch sử tạo.

### AC-04 Comment/Audit

- Thành viên gửi và tải lại được bình luận. Nếu mất phản hồi sau commit, thử lại cùng khóa vẫn chỉ có một bình luận.
- Các thay đổi create/update/move/assign có lịch sử đúng người thực hiện và thời điểm. Xóa task hoặc tài nguyên cha xóa cả lịch sử và bình luận của task liên quan.

### AC-05 Search & Offline

- Lọc task theo priority/assignee/column; search theo title, có clear filters.
- Khi offline, board đã sync mở được từ Room; hành động write bị khóa/nhắc người dùng.
- Khi online lại, pull-to-refresh cập nhật dữ liệu và dọn cache mất quyền đã được xác nhận. Sau logout và đăng nhập lại, dữ liệu đến muộn của phiên trước bị bỏ.

### AC-06 Engineering

- Clean checkout + setup theo README có thể khởi chạy backend và Android debug.
- Backend tests, Android unit tests và ít nhất một UI test chạy được theo hướng dẫn.
- GitHub Actions build/test được với cấu hình phù hợp, không yêu cầu secret để compile test cơ bản.
- Có demo data/account và hướng dẫn rõ ràng, không commit secrets.

---

## 11. DELIVERABLES / PORTFOLIO

Repo phải có:

1. **Android source** hoạt động, màn hình XML thật, rõ các layer MVVM + Clean Architecture.
2. **Backend source** hoạt động, migrations, seed, docs API và `.env.example`.
3. **README.md bằng tiếng Anh** gồm overview, features, tech stack, architecture diagram (Mermaid/ASCII), screenshots placeholders hoặc ảnh thật khi có, setup Android/backend, cách chạy test, demo accounts, decisions/trade-offs.
4. **API documentation**: endpoint table, auth, samples và error codes (có thể OpenAPI/Swagger hoặc Markdown).
5. **Architecture diagram** cho Fragment → ViewModel → UseCase → Repository → Room/Retrofit.
6. **Tests** và lệnh chạy chính xác; không tuyên bố coverage nếu chưa đo.
7. **GitHub Actions**: lint/build/unit test tối thiểu. Instrumented UI tests có thể yêu cầu emulator runner riêng.
8. **Demo checklist/script** 2–3 phút: login → workspace → project → task → drag & drop → comment → offline cache → logout.
9. **Release APK** khi môi trường cho phép; nếu thiếu signing config, tạo debug APK và ghi rõ chưa phải release ký chính thức. Tuyệt đối không commit keystore private.
10. **Known limitations / Roadmap** gồm P1 thông báo và realtime, P2 ghi offline, lời mời và đính kèm. Tổng quan, Lịch công việc và phần trăm hoàn thành nằm sau P0.

### Gợi ý diễn đạt trên CV sau khi đã làm thật

> Built **Sprintly**, a native Android Kanban task management app with Kotlin, XML, MVVM, Clean Architecture, Retrofit, Room, Hilt, Coroutines/Flow and a REST backend. Implemented JWT refresh-token handling, optimistic task movement with conflict recovery, local offline read cache, role-based authorization, and automated tests.

**Chỉ giữ những ý đã thực sự triển khai và kiểm chứng.**

---

## 12. LỘ TRÌNH 8 TUẦN (120–160 GIỜ DỰ KIẾN)

| Tuần | Mục tiêu | Deliverable / Gate |
|---|---|---|
| 1 | BA, user flows, ERD, API contract, scaffold Android/backend, design foundation | Repo chạy được, DB migration + health endpoint, Android app mở được |
| 2 | Auth backend, register/login/refresh/logout + Android screens, session | Auth end-to-end và token refresh test |
| 3 | Workspace, members, project, board columns, navigation | Owner/member có thể tạo và xem workspace/project |
| 4 | Tasks CRUD, Kanban board, drag/drop, task detail | Luồng nghiệp vụ chính từ login tới move task |
| 5 | Room cache, repository source-of-truth, offline UI | Offline read-only hoạt động; không lộ dữ liệu account |
| 6 | Search/filter, comments, audit, state/error polish | **MVP hoàn chỉnh (FR-01 → FR-12)** |
| 7 | Unit/API/UI tests, fix edge cases, performance; P1 realtime nếu dư | App ổn định, checklist pass |
| 8 | README, diagrams, CI, demo video script, APK, interview notes | Portfolio sẵn sàng apply |

**Quy tắc scope:** Nếu đến cuối tuần 4 vẫn chưa có luồng login → create project → create/move task, bỏ P1/P2, tập trung finish P0. Nếu backend trở thành blocker, không thay bằng mock app một cách im lặng; làm backend API tối thiểu đúng contract trước.

---

## 13. IMPLEMENTATION PHASES — TRÌNH TỰ CODEX THỰC HIỆN

### Phase 0 — Inspect, plan, bootstrap

- Kiểm tra repo hiện có, môi trường Java/Gradle/Android SDK/Node/Docker, tránh ghi đè project có sẵn.
- Tạo skeleton monorepo Android XML + Backend TypeScript + PostgreSQL Compose.
- Tạo backend `GET /health`, Prisma migration đầu tiên, seed; Android main screen + theme + navigation scaffold.
- Viết README run commands. Chạy thử các lệnh khả thi và ghi kết quả.
- Output: kiến trúc được chọn, tree files, build status và checklist Phase 1.

### Phase 1 — Authentication E2E

- Backend: register, login, refresh rotation, logout, `/users/me`, auth middleware, session table, tests.
- Android: auth screens XML, Hilt, Retrofit, interceptor/Authenticator, secure token storage, ViewModel state.
- Verify: có thể login/logout trên emulator, restart app vẫn giữ session, token refresh logic có test.

### Phase 2 — Workspace/Member/Project

- Backend routes + permission checks + tests + seeded demo owner/member.
- Android workspace list/details, add member, projects list and create dialog/form.
- Verify: hai account login nhìn thấy quyền và dữ liệu tương ứng; không truy cập trái phép.

### Phase 3 — Task CRUD & Kanban

- Backend tasks, columns, di chuyển trong giao dịch, kiểm tra phiên bản task và board, bình luận và lịch sử cơ bản. Tạo task và gửi bình luận hỗ trợ thử lại cùng khóa sau timeout.
- Android board XML + RecyclerView, task form/detail, optimistic move/rollback.
- Verify: task kéo giữa 2 cột, reload giữ đúng; mock 409/500 không làm mất task.

### Phase 4 — Room & Offline Read

- Local entities/DAOs, mapping, data sync strategy, per-account separation/cleanup, offline states.
- Verify: disable mạng mở board đã xem; logout/login tài khoản khác không lộ cache.

### Phase 5 — Finish MVP

- Search/filter UI + API query, comments/audit view, validation, loading/empty/error/retry, UX polish.
- Verify: toàn bộ AC-01..05 và checklist manual chính được đánh dấu PASS/FAIL có bằng chứng lệnh/test.

### Phase 6 — Harden & Package

- Android/backend automated tests, CI, README, diagrams, API docs, seed, demo script, APK debug.
- Nếu P0 đã hoàn tất mới lựa chọn **một** P1: realtime **hoặc** FCM; realtime được ưu tiên nếu có đủ thời gian.
- Verify: clean setup không cần đọc code để đoán cách chạy; report lệnh đã chạy và kết quả.

### Template báo cáo cuối mỗi phase

```text
PHASE: <number/name>
STATUS: PASS / PARTIAL / BLOCKED
IMPLEMENTED:
- ...
FILES CHANGED:
- ...
COMMANDS RUN + RESULTS:
- ...
ACCEPTANCE CHECKS:
- PASS ...
- FAIL ...
KNOWN ISSUES / TECH DEBT:
- ...
NEXT PHASE:
- ...
```

---

## 14. INITIAL TASK — LỆNH KÍCH HOẠT CHO CODEX

**Nếu Codex chỉ đọc một file, coi phần này là câu lệnh bắt đầu:**

> Hãy triển khai project **Sprintly** dựa trên toàn bộ đặc tả trong file này. Trước tiên đọc repo hiện tại và kiểm tra môi trường, sau đó thực hiện **Phase 0** và **Phase 1** theo đúng thứ tự; không cố làm cả 8 tuần trong một đợt. Android phải là **Kotlin + XML + ViewBinding, MVVM + Clean Architecture**, không dùng Compose. Backend dùng TypeScript + PostgreSQL theo contract. Trước khi chuyển phase, chạy build/test tương ứng, báo cáo kết quả thật và những phần chưa hoàn thành. Mọi feature P0 phải kết nối với backend/Room thật, không chỉ mock UI. Duy trì README cùng hướng dẫn chạy. Sau mỗi phase hãy cho tôi biết phần nào đã triển khai và đề xuất prompt tiếp theo để tiếp tục.

### Gợi ý cách sử dụng file này

1. Đặt file tại root của repository với tên `Sprintly_Codex_Implementation_Spec.md`.
2. Mở Codex tại thư mục repo, gửi: **“Read `Sprintly_Codex_Implementation_Spec.md` completely. Implement Phase 0 and Phase 1; run validations and report results.”**
3. Review kết quả. Khi phase đạt yêu cầu, gửi: **“Continue with Phase 2 according to the spec, preserving existing conventions and tests.”**
4. Lặp lại theo phase; không đánh dấu P0 hoàn thành nếu chưa pass các acceptance criteria.

---

**KẾT THÚC ĐẶC TẢ — Sprintly v1.0**
