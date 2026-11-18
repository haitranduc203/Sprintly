# Sprintly

Sprintly is an Android Kanban app for small teams. Phase 0 sets up the Android shell, the REST service and the local PostgreSQL database.

## Repository layout

| Path | Contents |
| --- | --- |
| `source/` | Single-module Kotlin Android app using XML Views, View Binding, Material Components and Fragment navigation. |
| `backend/` | TypeScript and Fastify service, Prisma schema, migrations, seed and API tests. |
| `.github/workflows/` | Android and backend build and test jobs. |
| `docs/` and `design-system/` | Approved MVP requirements, API contract, ERD and Orbix design. |

## Run Android

Open `source/` in Android Studio. Install Android SDK Platform 37 and Android SDK Build-Tools 36.0.0 through SDK Manager. Use JDK 25 for Gradle and an emulator or device running Android 8.0 (API 26) or later. The app ID is `com.example.sprintly`. Android Studio can create the machine-specific `source/local.properties`. For a command-line build, set `ANDROID_HOME` to your SDK path if that file is absent.

From the repository root in PowerShell:

```powershell
Set-Location source
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

Connect a device or start an emulator before running `connectedDebugAndroidTest`. Run the `app` configuration in Android Studio to open the Sprintly start screen. The screen runs independently of the backend in Phase 0.

## Run the backend

Install Node.js 24.11 or later in the 24.x line and PostgreSQL 17. On Windows, the PostgreSQL installer includes pgAdmin. Keep PostgreSQL running on port `5432`. [PostgreSQL Windows installer](https://www.postgresql.org/download/windows/)

In pgAdmin, connect to the local PostgreSQL server as `postgres` and open the Query Tool for the `postgres` database. Replace the example password with your own, then run each statement separately. [CREATE ROLE](https://www.postgresql.org/docs/17/sql-createrole.html) · [CREATE DATABASE](https://www.postgresql.org/docs/17/sql-createdatabase.html)

```sql
CREATE ROLE sprintly LOGIN PASSWORD 'replace_me';
CREATE DATABASE sprintly OWNER sprintly;
```

From the repository root, copy the template. Edit `backend/.env` before running the backend commands. Replace the password in `DATABASE_URL` with the password for the `sprintly` role. URL-encode any special characters in the URL password. Set `DEMO_OWNER_PASSWORD` and `DEMO_MEMBER_PASSWORD` for the app's demo accounts. Each must contain at least 8 characters and fit within 72 UTF-8 bytes.

```powershell
Copy-Item backend/.env.example backend/.env
# Edit backend/.env before running the commands below.
Set-Location backend
npm.cmd ci
npm.cmd run db:migrate
npm.cmd run db:seed
npm.cmd run dev
```

Stop the backend with `Ctrl+C`. If you already have a PostgreSQL instance, create a dedicated development database and point `DATABASE_URL` to it. GitHub Actions provisions its own PostgreSQL test service.

From another terminal, check database readiness:

```powershell
Invoke-RestMethod http://127.0.0.1:3000/health
```

`GET /health` returns HTTP `200` with `{"data":{"status":"ok"}}` when PostgreSQL responds and HTTP `503` with `DATABASE_UNAVAILABLE` when it cannot be reached.

Run backend checks from `backend/`:

```powershell
npm.cmd test
npm.cmd run build
```

After building, `npm.cmd start` runs the compiled service.

The seed provisions `owner@sprintly.demo` and `member@sprintly.demo` in a sample workspace. It sets the passwords from the local environment on first creation and preserves existing password hashes on subsequent runs. Phase 1 adds the routes and Android screens to sign in with these accounts.

## Phase 1 checklist

- Add session persistence and implement register, login, refresh rotation, logout and `/users/me` in the REST API.
- Add Android registration, login and session routing with secure token storage.
- Verify the full authentication flow on an emulator and cover definitive refresh rejection, temporary failure and late responses from a closed session.

The phase gates, test results and acceptance criteria are recorded here. [Implementation specification](docs/Sprintly_Codex_Implementation_Spec.md) · [Phase 0 verification](docs/Sprintly_Phase_0_Verification.md) · [Handoff](docs/Sprintly_Handoff.md)
