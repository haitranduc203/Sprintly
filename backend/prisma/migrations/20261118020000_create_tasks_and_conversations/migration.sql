BEGIN;

CREATE TYPE "task_priority" AS ENUM ('LOW', 'MEDIUM', 'HIGH', 'URGENT');
CREATE TYPE "idempotency_operation" AS ENUM ('CREATE_TASK', 'CREATE_COMMENT');

CREATE UNIQUE INDEX "board_columns_project_id_id_key" ON "board_columns"("project_id", "id");

CREATE TABLE "tasks" (
    "id" UUID NOT NULL,
    "project_id" UUID NOT NULL,
    "column_id" UUID NOT NULL,
    "title" TEXT NOT NULL,
    "description" TEXT,
    "priority" "task_priority" NOT NULL,
    "assignee_id" UUID,
    "due_at" TIMESTAMPTZ(3),
    "position" INTEGER NOT NULL,
    "version" INTEGER NOT NULL DEFAULT 1,
    "created_by" UUID NOT NULL,
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(3) NOT NULL,

    CONSTRAINT "tasks_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "tasks_title_length" CHECK (char_length("title") BETWEEN 1 AND 150),
    CONSTRAINT "tasks_nonnegative_position" CHECK ("position" >= 0),
    CONSTRAINT "tasks_positive_version" CHECK ("version" >= 1),
    -- Move and delete compact position ranges before the final ordering is committed.
    CONSTRAINT "tasks_column_id_position_key" UNIQUE ("column_id", "position") DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT "tasks_project_id_fkey"
        FOREIGN KEY ("project_id") REFERENCES "projects"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "tasks_project_id_column_id_fkey"
        FOREIGN KEY ("project_id", "column_id") REFERENCES "board_columns"("project_id", "id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "tasks_assignee_id_fkey"
        FOREIGN KEY ("assignee_id") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "tasks_created_by_fkey"
        FOREIGN KEY ("created_by") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE
);

CREATE TABLE "comments" (
    "id" UUID NOT NULL,
    "task_id" UUID NOT NULL,
    "author_id" UUID NOT NULL,
    "body" TEXT NOT NULL,
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(3) NOT NULL,

    CONSTRAINT "comments_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "comments_body_length" CHECK (char_length("body") BETWEEN 1 AND 2000),
    CONSTRAINT "comments_task_id_fkey"
        FOREIGN KEY ("task_id") REFERENCES "tasks"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "comments_author_id_fkey"
        FOREIGN KEY ("author_id") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE
);

CREATE TABLE "task_activities" (
    "id" UUID NOT NULL,
    "task_id" UUID NOT NULL,
    "actor_id" UUID NOT NULL,
    "action" TEXT NOT NULL,
    "payload" JSONB NOT NULL,
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "task_activities_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "task_activities_task_id_fkey"
        FOREIGN KEY ("task_id") REFERENCES "tasks"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "task_activities_actor_id_fkey"
        FOREIGN KEY ("actor_id") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE
);

CREATE TABLE "idempotency_requests" (
    "id" UUID NOT NULL,
    "account_id" UUID NOT NULL,
    "operation" "idempotency_operation" NOT NULL,
    "project_id" UUID,
    "task_id" UUID,
    "idempotency_key" UUID NOT NULL,
    "request_hash" TEXT NOT NULL,
    "result_id" UUID NOT NULL,
    "response_status" SMALLINT NOT NULL,
    "response_body" JSONB,
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "idempotency_requests_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "idempotency_requests_operation_scope" CHECK (
        ("operation" = 'CREATE_TASK' AND "project_id" IS NOT NULL AND "task_id" IS NULL)
        OR ("operation" = 'CREATE_COMMENT' AND "task_id" IS NOT NULL AND "project_id" IS NULL AND "response_body" IS NOT NULL)
    ),
    CONSTRAINT "idempotency_requests_success_status" CHECK ("response_status" BETWEEN 200 AND 299),
    CONSTRAINT "idempotency_requests_account_id_fkey"
        FOREIGN KEY ("account_id") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "idempotency_requests_project_id_fkey"
        FOREIGN KEY ("project_id") REFERENCES "projects"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "idempotency_requests_task_id_fkey"
        FOREIGN KEY ("task_id") REFERENCES "tasks"("id") ON DELETE CASCADE ON UPDATE CASCADE
);

CREATE INDEX "tasks_project_id_column_id_position_idx" ON "tasks"("project_id", "column_id", "position");
CREATE INDEX "tasks_assignee_id_due_at_idx" ON "tasks"("assignee_id", "due_at");
CREATE INDEX "comments_task_id_created_at_id_idx" ON "comments"("task_id", "created_at", "id");
CREATE INDEX "task_activities_task_id_created_at_id_idx" ON "task_activities"("task_id", "created_at", "id");
CREATE UNIQUE INDEX "idempotency_requests_project_key"
    ON "idempotency_requests"("account_id", "operation", "project_id", "idempotency_key");
CREATE UNIQUE INDEX "idempotency_requests_task_key"
    ON "idempotency_requests"("account_id", "operation", "task_id", "idempotency_key");
CREATE INDEX "idempotency_requests_project_id_result_id_idx" ON "idempotency_requests"("project_id", "result_id");

COMMIT;
