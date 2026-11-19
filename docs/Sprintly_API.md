# Sprintly API reference

The development API starts at `http://127.0.0.1:3000`. The Android emulator reaches the same service at `http://10.0.2.2:3000`. Protected routes and authentication routes use the `/api/v1` prefix. The readiness endpoint is `/health`. Follow the repository setup before running the examples. [Run the backend](../README.md#run-the-backend)

## Readiness and response formats

`GET /health` requires no credentials and checks whether PostgreSQL can answer a query. A ready service returns HTTP `200` with `{"data":{"status":"ok"}}`. An unavailable database returns HTTP `503` with `DATABASE_UNAVAILABLE`.

API responses with a body use JSON. Successful reads and writes return `data`, which may be an object, a list, or `null` for an exact user lookup with no match. A successful `204` has an empty body. Handled API errors return an `error` object with a machine-readable `code` and a human-readable `message`.

```json
{
  "error": {
    "code": "BOARD_VERSION_CONFLICT",
    "message": "Board has changed. Reload and retry."
  }
}
```

Use `Content-Type: application/json` with a JSON request body. Resource IDs and idempotency keys use UUIDs. Response timestamps use UTC ISO 8601 strings. Paths in the following tables include `/api/v1`.

## Authentication and sessions

| Method and path | Request | Success |
| --- | --- | --- |
| `POST /api/v1/auth/register` | `email`, `displayName`, `password` | `201`. `data` contains `user`, `accessToken`, `refreshToken`, `expiresIn`. |
| `POST /api/v1/auth/login` | `email`, `password` | `200`. Same fields as registration. |
| `POST /api/v1/auth/refresh` | `refreshToken` | `200`. `data` contains `accessToken`, `refreshToken`, `expiresIn`. |
| `POST /api/v1/auth/logout` | `refreshToken` | `204`. Revokes this session. |
| `GET /api/v1/users/me` | Bearer access token | `200`. `data` is the authenticated user. |

User objects contain `id`, `email`, and `displayName`. Registration accepts a trimmed display name of 1 to 100 characters and a password of at least 8 characters and at most 72 UTF-8 bytes. Emails are trimmed and lowercased.

Login and registration start independent sessions. Send `Authorization: Bearer <accessToken>` on protected requests. `expiresIn` describes the validity of the access token in seconds and defaults to 900. The session expires 30 days after creation. Refresh rotates its token within that session and the client should replace the stored refresh token after a successful response. Serialize refresh requests per session. If a refresh response is lost after rotation, the previous refresh token may have been consumed. Sign in again if refresh returns `REFRESH_TOKEN_INVALID`. Logout accepts a previously rotated refresh token from the same session, then that session's credentials lose access.

Registration and login share a limit of 10 requests per minute per client IP. The refresh and logout endpoints are each configured for up to 30 requests per minute per client IP. On HTTP `429`, wait for the `Retry-After` interval.

### Sign in and read the profile

Use a seeded demo account and the demo password configured for your local database.

```powershell
$base = 'http://127.0.0.1:3000/api/v1'
$demoPassword = Read-Host 'Local owner demo password'
$login = Invoke-RestMethod -Method Post -Uri "$base/auth/login" -ContentType 'application/json' -Body (@{
    email = 'owner@sprintly.demo'
    password = $demoPassword
} | ConvertTo-Json -Compress)

$headers = @{ Authorization = "Bearer $($login.data.accessToken)" }
(Invoke-RestMethod -Uri "$base/users/me" -Headers $headers).data
```

Refresh and logout both accept a JSON body of the following shape. Use the refresh token from your own session.

```json
{
  "refreshToken": "<refreshToken>"
}
```

## Workspaces, members, projects, and columns

`Signed-in user` means a valid session. `Workspace member` includes either `MEMBER` or `OWNER` of the relevant workspace. Each workspace object includes the caller's `role`. The caller who creates a workspace becomes its `OWNER`.

| Method and path | Access | Request or result | Success |
| --- | --- | --- | --- |
| `GET /api/v1/workspaces` | Signed-in user | Workspaces available to the caller | `200` |
| `POST /api/v1/workspaces` | Signed-in user | `name` and optional `description` | `201` |
| `GET /api/v1/workspaces/:workspaceId` | Workspace member | Workspace and caller's role | `200` |
| `PATCH /api/v1/workspaces/:workspaceId` | Owner | `name`, `description`, or both | `200` |
| `DELETE /api/v1/workspaces/:workspaceId` | Owner | Deletes the workspace and its projects | `204` |
| `GET /api/v1/workspaces/:workspaceId/members` | Workspace member | Members as user objects with a `role` | `200` |
| `GET /api/v1/workspaces/:workspaceId/users/search` | Owner | Query `email`. Returns the exact account or `null`. | `200` |
| `POST /api/v1/workspaces/:workspaceId/members` | Owner | `userId` and `role: "MEMBER"` | `201` |
| `DELETE /api/v1/workspaces/:workspaceId/members/:userId` | Owner | Removes membership and clears affected task assignments | `204` |
| `GET /api/v1/workspaces/:workspaceId/projects` | Workspace member | Projects in the workspace | `200` |
| `POST /api/v1/workspaces/:workspaceId/projects` | Workspace member | `name` and optional `description` | `201` |
| `GET /api/v1/projects/:projectId` | Workspace member | Project | `200` |
| `PATCH /api/v1/projects/:projectId` | Workspace member | `name`, `description`, or both | `200` |
| `DELETE /api/v1/projects/:projectId` | Owner | Deletes the project and its tasks | `204` |
| `GET /api/v1/projects/:projectId/board` | Workspace member | Board with its ordered columns and tasks | `200` |
| `POST /api/v1/projects/:projectId/columns` | Workspace member | `name` for a column appended to the board | `201` |
| `PATCH /api/v1/columns/:columnId` | Workspace member | `name` for the renamed column | `200` |

Workspace and project names are trimmed and accept 1 to 100 characters. Use `description: null` to clear the description on an update. Every new project starts with `To Do`, `In Progress`, and `Done`. The project has `id`, `workspaceId`, `name`, `description`, and `boardVersion`. A board response has `boardVersion` and `columns`, with each column containing `id`, `name`, `position`, and `tasks`. Positions start at zero. A workspace must retain at least one owner. Removing a member clears their assignments in the workspace, increments the affected task versions, and records the assignment changes. Their prior comments and activity remain available to current members.

## Tasks, comments, and activity

| Method and path | Access | Request or result | Success |
| --- | --- | --- | --- |
| `GET /api/v1/projects/:projectId/tasks` | Workspace member | Paged task search with optional filters | `200` |
| `POST /api/v1/projects/:projectId/tasks` | Workspace member | Task fields and `Idempotency-Key` | `201` |
| `GET /api/v1/tasks/:taskId` | Workspace member | Task details with `boardVersion` | `200` |
| `PATCH /api/v1/tasks/:taskId` | Workspace member | Task changes and expected versions | `200` |
| `PATCH /api/v1/tasks/:taskId/move` | Workspace member | `targetColumnId`, `targetIndex`, `expectedVersion`, `expectedBoardVersion` | `200` |
| `DELETE /api/v1/tasks/:taskId` | Owner or creator with current membership | Deletes the task with its comments and activity | `204` |
| `GET /api/v1/tasks/:taskId/comments` | Workspace member | Paged comments, oldest first | `200` |
| `POST /api/v1/tasks/:taskId/comments` | Workspace member | `body` and `Idempotency-Key` | `201` |
| `GET /api/v1/tasks/:taskId/activities` | Workspace member | Paged activity, newest first | `200` |

Creating a task requires `columnId`, `title`, and a `priority` of `LOW`, `MEDIUM`, `HIGH`, or `URGENT`. The `description`, `assigneeId`, and `dueAt` fields are optional and nullable. The assignee must be a current workspace member. A due date must include a time and zone, for example `2026-12-04T17:00:00+07:00`. Titles and comments are trimmed and accept 1 to 150 and 1 to 2,000 characters respectively.

A task contains its resource IDs, content fields, `assignee` as a user object or `null`, `position`, `version`, `createdBy`, `createdAt`, and `updatedAt`. Task creation, update, move, and detail responses also return `boardVersion` on the task. Board and search lists return the task versions, and a board response carries the board version at the top level of `data`. A comment contains `id`, `author`, `body`, and `createdAt`. An activity entry contains `id`, `actor`, `action`, `payload`, and `createdAt`. The action is `CREATED`, `UPDATED`, `MOVED`, or `ASSIGNEE_CHANGED`. The payload describes that action, including prior and new positions for a move.

### Search and pagination

Task search, comments, and activity accept `page` and `pageSize`. Pages start at 1. The default page size is 20 and the maximum is 100. `meta.total` is the count across all pages for the same query. A page beyond the matching results has an empty `data` list.

```json
{
  "data": [],
  "meta": {
    "page": 2,
    "pageSize": 20,
    "total": 5
  }
}
```

Task search combines all supplied filters. `q` is a literal, case-insensitive title substring. The API trims surrounding whitespace and rejects queries longer than 150 characters after trimming. `columnId` and `assigneeId` use UUIDs, and `priority` uses the same values as task creation. Search results are ordered by column position, then task position and ID.

```http
GET /api/v1/projects/<projectId>/tasks?q=release%20notes&priority=HIGH&page=1&pageSize=20
Authorization: Bearer <accessToken>
```

### Create a task or comment with an idempotency key

Replace placeholders with values returned by the API. Use a new UUID for each intended creation. When retrying after a timeout or lost response, send the same key and the same JSON fields and values. The API scopes the key to the authenticated user, operation, and parent project or task. The same request returns the original `201` and resource. Reusing the key with a changed payload returns `409 IDEMPOTENCY_KEY_REUSED`. A replay of a deleted task returns `404`.

```http
POST /api/v1/projects/<projectId>/tasks
Authorization: Bearer <accessToken>
Content-Type: application/json
Idempotency-Key: <new-uuid>

{
  "columnId": "<columnId>",
  "title": "Review release notes",
  "priority": "HIGH",
  "assigneeId": null
}
```

```http
POST /api/v1/tasks/<taskId>/comments
Authorization: Bearer <accessToken>
Content-Type: application/json
Idempotency-Key: <different-new-uuid>

{"body":"Reviewed on the Android emulator."}
```

### Edit or move a task

A task edit always supplies `expectedVersion` and at least one editable field. Moving via the edit route to a different `columnId` additionally requires `expectedBoardVersion` and appends to the target column. A direct move requires both versions from the current board. Use the destination list with the moving task removed when calculating a same-column move. `targetIndex: 0` selects the front, and the destination length selects the end. On HTTP `409`, reload the board and recompute the request against the latest data.

```http
PATCH /api/v1/tasks/<taskId>/move
Authorization: Bearer <accessToken>
Content-Type: application/json

{
  "targetColumnId": "<destinationColumnId>",
  "targetIndex": 0,
  "expectedVersion": 1,
  "expectedBoardVersion": 2
}
```

## Error codes

| HTTP | Code | Meaning |
| --- | --- | --- |
| `400` | `VALIDATION_ERROR` | A body field, header, path parameter, or query value is invalid. |
| `401` | `INVALID_CREDENTIALS` | Login rejected the email and password combination. |
| `401` | `UNAUTHORIZED` | The bearer token or its session is invalid or expired. The response includes `WWW-Authenticate: Bearer`. |
| `401` | `REFRESH_TOKEN_INVALID` | The supplied refresh token is invalid for the operation. |
| `403` | `FORBIDDEN` | The caller's current role or creator status cannot perform the operation. |
| `404` | `NOT_FOUND` | The resource is missing or outside the caller's membership scope. |
| `409` | `EMAIL_ALREADY_REGISTERED` | An account already uses the email. |
| `409` | `MEMBER_ALREADY_EXISTS` | The user is already a workspace member. |
| `409` | `LAST_WORKSPACE_OWNER` | Removing that member would leave no owner. |
| `409` | `TASK_VERSION_CONFLICT`, `BOARD_VERSION_CONFLICT` | The supplied task or board version is stale. |
| `409` | `IDEMPOTENCY_KEY_REUSED` | A different payload already used the key in the same scope. |
| `422` | `INVALID_ASSIGNEE` | The assignee is outside the workspace. |
| `422` | `INVALID_TARGET_COLUMN` | The destination column is outside the project. |
| `422` | `INVALID_TARGET_INDEX` | The insertion index is outside the destination list. |
| `429` | `RATE_LIMITED` | The client reached its authentication request limit. |
| `500` | `INTERNAL_ERROR` | The service could not complete the request. |
| `503` | `DATABASE_UNAVAILABLE` | The readiness check could not reach PostgreSQL. |
