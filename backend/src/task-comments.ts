import { publicUserFields } from "./auth.js";
import type { Prisma, PrismaClient } from "./generated/prisma/client.js";
import { createOnce } from "./idempotency.js";
import type { Idempotency } from "./idempotency.js";
import { lockTaskProject, taskInScope } from "./task-data.js";
import type { readPagination } from "./task-validation.js";

type Page = ReturnType<typeof readPagination>;

const commentFields = {
  id: true, author: { select: publicUserFields }, body: true, createdAt: true,
} as const satisfies Prisma.CommentSelect;
const activityFields = {
  id: true, actor: { select: publicUserFields }, action: true, payload: true, createdAt: true,
} as const satisfies Prisma.TaskActivitySelect;

function timestamped<Entry extends { createdAt: Date }>({ createdAt, ...entry }: Entry) {
  return { ...entry, createdAt: createdAt.toISOString() };
}

export function createTaskCommentService(database: PrismaClient) {
  return {
    addComment: (taskId: string, userId: string, body: string, idempotency: Idempotency) =>
      database.$transaction(async (tx) => {
        await lockTaskProject(tx, taskId, userId);
        return createOnce(tx, userId, { operation: "CREATE_COMMENT", taskId }, idempotency, async () => {
          const comment = await tx.comment.create({ data: { taskId, authorId: userId, body }, select: commentFields });
          return timestamped(comment);
        });
      }, { isolationLevel: "ReadCommitted" }),

    listComments: (taskId: string, userId: string, { page, pageSize }: Page) =>
      database.$transaction(async (tx) => {
        await taskInScope(tx, taskId, userId);
        const where = { taskId };
        const total = await tx.comment.count({ where });
        const skip = (page - 1) * pageSize;
        const comments = skip >= total ? [] : await tx.comment.findMany({
          where, select: commentFields, orderBy: [{ createdAt: "asc" }, { id: "asc" }], skip, take: pageSize,
        });
        return { data: comments.map(timestamped), meta: { page, pageSize, total } };
      }, { isolationLevel: "RepeatableRead" }),

    listActivities: (taskId: string, userId: string, { page, pageSize }: Page) =>
      database.$transaction(async (tx) => {
        await taskInScope(tx, taskId, userId);
        const where = { taskId };
        const total = await tx.taskActivity.count({ where });
        const skip = (page - 1) * pageSize;
        const activities = skip >= total ? [] : await tx.taskActivity.findMany({
          where, select: activityFields, orderBy: [{ createdAt: "desc" }, { id: "desc" }], skip, take: pageSize,
        });
        return { data: activities.map(timestamped), meta: { page, pageSize, total } };
      }, { isolationLevel: "RepeatableRead" }),
  };
}
