# Sprintly — Kiểm tra Phase 0

Kiểm tra ngày 18/11/2026 trên nhánh `chore/bootstrap`, với nền Android và backend từ `73b3381` cùng cấu hình phát triển dùng PostgreSQL trực tiếp.

**Kết quả kiểm tra local đạt.** Build, unit test, lint và UI test Android thành công. Backend build, unit test, migration, seed và kiểm tra HTTP với PostgreSQL 17 đều đạt.

## Android

Chạy từ `source/` bằng JBR 25 của Android Studio. Project dùng Kotlin/XML, ViewBinding, Material Components, `MainActivity`, `WelcomeFragment` và Navigation Component. `minSdk` là 26, compile/target SDK là 37.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest --offline --rerun-tasks --console=plain
```

Kết quả `BUILD SUCCESSFUL`, exit 0, 85 task đã thực thi. Bài kiểm thử trên thiết bị chạy với máy ảo Pixel 6a, Android 14 (API 34).

- Unit test có 1 test mẫu, 0 failure/error/skipped. Test mẫu kiểm tra phép cộng.
- Espresso chạy `BootstrapScreenTest.launchesBrandedScreen`, 1 test, 0 failure/error/skipped. Test xác nhận tên app xuất hiện khi mở Activity.
- Lint có 0 lỗi và 1 cảnh báo `AndroidGradlePluginVersion` tại `source/gradle/libs.versions.toml`. Cảnh báo nêu có AGP mới hơn bản 9.4.1 đang dùng. Build hiện tại thành công.
- APK debug được tạo tại `source/app/build/outputs/apk/debug/app-debug.apk`, kích thước 7.582.124 byte.
- Handoff ghi nhận đã thử kéo thả chéo cột. Lượt này kiểm tra màn mở đầu và Espresso test có trong repository. Cử chỉ kéo thả Kanban cần được kiểm tra lại khi có board ở Phase 3.

Các báo cáo được Gradle tạo trong `source/app/build/test-results/testDebugUnitTest/`, `source/app/build/outputs/androidTest-results/connected/debug/` và `source/app/build/reports/lint-results-debug.xml`.

## Backend và PostgreSQL

Chạy `npm.cmd ci --no-audit --no-fund --prefer-offline` từ `backend/` với Node.js 24.22.2. Lệnh cài theo lockfile thành công. `npm.cmd test` chạy 2 test, cả hai đạt. `npm.cmd run build` tạo Prisma Client 7.10.1 và biên dịch TypeScript thành công.

Dùng PostgreSQL 17.11 portable trong thư mục tạm, chỉ lắng nghe trên loopback. Tạo một role `sprintly` có quyền đăng nhập và sở hữu database thử nghiệm theo cách README hướng dẫn. Các tiến trình database và API đã dừng sau kiểm tra. [README](../README.md#run-the-backend) · [Package PostgreSQL portable](https://github.com/leinelissen/embedded-postgres)

| Kiểm tra thực tế | Kết quả |
| --- | --- |
| Áp dụng migration trên database trống | Migration `20261117000000_bootstrap` thành công với role `sprintly`, `rolsuper = false`. |
| `prisma migrate status` | Database schema up to date. |
| Chạy lại `prisma migrate deploy` | No pending migrations to apply. |
| Seed lần đầu | Có đúng 2 user, 1 workspace và 2 membership với vai trò Owner/Member. Password hash kiểm tra đúng với mật khẩu đầu vào. |
| Seed lần hai với mật khẩu đầu vào khác | Giữ nguyên số bản ghi, ID người dùng và password hash. |
| Server đã biên dịch với database chạy | `GET /health` trả `200` và `{ "data": { "status": "ok" } }`. |
| Dừng database rồi gọi lại server | `GET /health` trả `503` và mã `DATABASE_UNAVAILABLE`, thông báo `Database unavailable`. |

Script kiểm thử tạm gọi Prisma CLI và seed của dự án, kiểm tra bản ghi và phản hồi HTTP, rồi kết thúc với exit 0. Dữ liệu kiểm thử tách khỏi repository và cấu hình phát triển.

## CI và hướng dẫn chạy

- Workflow chạy khi có PR hoặc khi push lên `main`. Hai job kiểm tra Android build/unit test/lint và backend test/build, migration, seed, cùng HTTP readiness trên PostgreSQL. Kết quả CI được lưu theo commit. [Sprintly CI](https://github.com/haitranduc203/Sprintly/actions/workflows/ci.yml)
- Espresso được kiểm tra trên emulator local. README có lệnh build/test và cách chuẩn bị PostgreSQL trực tiếp. [README](../README.md)

## Bàn giao Phase 1

Sau khi PR Phase 0 được hợp nhất, bắt đầu Phase 1 trên một nhánh tách từ `main`. Checklist gồm register, login, refresh, logout và `/users/me`, cùng màn hình xác thực và lưu phiên an toàn trên Android. [Handoff](Sprintly_Handoff.md) · [Đặc tả Phase 1](Sprintly_Codex_Implementation_Spec.md#phase-1--authentication-e2e)

Git bỏ qua index CodeGraph cục bộ `/.codegraph/`, build output và dependency backend.
