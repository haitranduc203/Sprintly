# Sprintly

Sprintly is an Android Kanban app for small teams. The app supports account authentication and management of workspaces, members, projects and Kanban columns through a PostgreSQL-backed REST API.

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

Start the backend before signing in or creating an account. The debug app uses `http://10.0.2.2:3000/api/v1/`, which reaches the host from the Android emulator. Override the URL with `sprintlyApiBaseUrl` in `source/local.properties` or with the Gradle property `-PsprintlyApiBaseUrl`. Cleartext HTTP is limited to emulator and loopback addresses in debug builds. Release sign-in requires a configured HTTPS endpoint.

## Run the backend

Install Node.js 24.11 or later in the 24.x line and PostgreSQL 17. On Windows, the PostgreSQL installer includes pgAdmin. Keep PostgreSQL running on port `5432`. [PostgreSQL Windows installer](https://www.postgresql.org/download/windows/)

In pgAdmin, connect to the local PostgreSQL server as `postgres` and open the Query Tool for the `postgres` database. Replace the example password with your own, then run each statement separately. In the SQL string, double any apostrophe in the password, as in `'my''password'`. [CREATE ROLE](https://www.postgresql.org/docs/17/sql-createrole.html) · [CREATE DATABASE](https://www.postgresql.org/docs/17/sql-createdatabase.html)

```sql
CREATE ROLE sprintly LOGIN PASSWORD 'replace_me';
CREATE DATABASE sprintly OWNER sprintly ENCODING 'UTF8' LC_COLLATE 'C' LC_CTYPE 'C' TEMPLATE template0;
```

From the repository root, create `backend/.env` if it is missing.

```powershell
if (-not (Test-Path backend/.env)) { Copy-Item backend/.env.example backend/.env }
```

Replace the password in `DATABASE_URL` with the password for the `sprintly` role. URL-encode any special characters in the URL password. Set `DEMO_OWNER_PASSWORD` and `DEMO_MEMBER_PASSWORD` for the app's demo accounts. Each must contain at least 8 characters and fit within 72 UTF-8 bytes.

Quote demo passwords containing `#` in `.env`, for example `DEMO_OWNER_PASSWORD='example#password'`.

Set `AUTH_JWT_SECRET` in `backend/.env` to a random signing secret for this environment. Copy the output of this command into the value.

```powershell
node -p "require('node:crypto').randomBytes(32).toString('hex')"
```

```powershell
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

The seed provisions `owner@sprintly.demo` and `member@sprintly.demo` in the Sprintly Demo workspace, with a Sprintly Launch project and three default columns. It sets passwords from the local environment on first creation. Subsequent runs preserve existing password hashes and edits to an existing demo project. Use the configured demo credentials to sign in or create your own account in the app.

## Phase 1 authentication

The API under `/api/v1` supports `POST /auth/register`, `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout` and bearer-authenticated `GET /users/me`. Access tokens expire after 15 minutes by default. Refresh tokens rotate on use within a 30-day session. After rotation the previous token can still sign out that session, which lets sign-out revoke the session if it races with a refresh. Android encrypts the saved session with a key in Android Keystore.

Sign-in and registration share a limit of 10 requests per minute per connection IP. When a reverse proxy connects to Fastify on behalf of multiple clients, those requests share the proxy's limit with the current server configuration.

## Phase 2 workspace and project permissions

Any signed-in user can create a workspace and becomes its Owner in the same transaction. Owners add existing accounts as Members by looking up an exact email address. A removed member loses access to that workspace and its projects.

| Operation inside a workspace | Owner | Member |
| --- | --- | --- |
| View the workspace, members, projects and boards | Yes | Yes |
| Create or edit a project and add or rename a column | Yes | Yes |
| Edit or delete the workspace | Yes | |
| Look up an account by email, add or remove a member | Yes | |
| Delete a project | Yes | |

New projects start with To Do, In Progress and Done in that order.

## Phase 3 tasks and Kanban

Open a project to create a task with a title, column and priority. You can add a description, due date or workspace assignee. Drag the handle on a card to reorder it or move it into another column. The **Di chuyển** action lets you choose a column and insertion position. Open a card to edit it, add comments and see its activity history.

Workspace members can create, edit, move and comment on tasks. A workspace Owner or the task creator can delete the task while they are still a member. Removing a member clears their assignments in that workspace and keeps their earlier comments and activity.

Task moves validate the versions of the task and board. Android restores the previous order and reloads the board if the save fails. If task or comment creation cannot be confirmed, **Thử lại** sends the same payload and request key. [Implementation specification](docs/Sprintly_Codex_Implementation_Spec.md#phase-3--task-crud--kanban)

## Phase 4 offline reading

Open a workspace, its project or member list, and a Kanban board while connected. Android saves each successful response in Room. The board snapshot includes its columns, tasks and version. Airplane mode still lets you open the saved workspace, project, board and task details. The banner indicates that the screen is showing saved data and editing is disabled. If a screen has no saved data, it shows a retry action. Comments and activity history load from the API.

Android refreshes on return to a screen, when connectivity returns and when the user chooses **Thử lại**. A successful refresh replaces the saved data. Confirmed removal of workspace access clears the affected snapshots. Each login has a separate cache namespace that survives an app restart for that login. Signing out or receiving a definitive refresh rejection closes access to that namespace and clears its snapshots.

## Verify the end-to-end flows

For PostgreSQL integration tests, apply the migrations to a dedicated test database. Set `TEST_DATABASE_URL` to that database before running `npm.cmd test`. The PostgreSQL suite is skipped when the variable is absent. GitHub Actions points it to the test service.

With the backend running on port `3000` and Sprintly signed out on the emulator, run the full authentication UI test from `source/`.

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.sprintly.AuthApiFlowTest -Pandroid.testInstrumentationRunnerArguments.sprintlyAuthE2e=true
```

Run the two-account workspace flow on a development database. The test creates two accounts, verifies the Owner and Member views and removes the workspace it creates.

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.sprintly.WorkspaceApiFlowTest -Pandroid.testInstrumentationRunnerArguments.sprintlyWorkspaceE2e=true
```

Run the Kanban flow against a dedicated development database. The test creates its own account and tasks, drags a card into an occupied column, verifies the order after refreshing, and checks that edits, a comment and move history survive reopening the task.

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.sprintly.KanbanApiFlowTest -Pandroid.testInstrumentationRunnerArguments.sprintlyTaskE2e=true
```

Run the offline flow with the emulator signed out and connected. It creates two test accounts, verifies the saved board and task while in airplane mode, and checks that the second login cannot see the first account's workspace. The test restores the starting network settings.

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.sprintly.OfflineApiFlowTest' '-Pandroid.testInstrumentationRunnerArguments.sprintlyOfflineE2e=true'
```

Run the search flow on the emulator with Sprintly signed out and connected. It creates a dedicated project, checks title matching and all three filters together, moves a task from the results and verifies the updated list when returning. It also checks that Activity recreation keeps the criteria and clearing filters keeps the keyword. The test uses airplane mode to verify the return to the cached board and restores the initial network settings.

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.sprintly.TaskSearchApiFlowTest' '-Pandroid.testInstrumentationRunnerArguments.sprintlySearchE2e=true'
```

The phase gates, test results and acceptance criteria are recorded here. [Implementation specification](docs/Sprintly_Codex_Implementation_Spec.md) · [Phase 0 verification](docs/Sprintly_Phase_0_Verification.md) · [Handoff](docs/Sprintly_Handoff.md)
