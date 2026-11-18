import { ApiError } from "./api-errors.js";
import { Prisma } from "./generated/prisma/client.js";
import type { PrismaClient } from "./generated/prisma/client.js";
import { createOnce } from "./idempotency.js";
import type { Idempotency } from "./idempotency.js";
import { lockTaskProject, taskActivity, taskData, taskFields, taskInScope, taskOrder } from "./task-data.js";
import type { Task } from "./task-data.js";
import type { readMove, readNewTask, readTaskFilters, readTaskPatch } from "./task-validation.js";
import { lockProject, membershipKey, projectInScope } from "./workspace-access.js";
import type { Transaction } from "./workspace-access.js";
import { invalidBody } from "./validation.js";

type NewTask = ReturnType<typeof readNewTask>["input"];
type TaskPatch = ReturnType<typeof readTaskPatch>;
type Move = ReturnType<typeof readMove>;
type Filters = ReturnType<typeof readTaskFilters>;

function requireTaskVersion(task: Task, expectedVersion: number) {
  if (task.version !== expectedVersion) throw new ApiError(409, "TASK_VERSION_CONFLICT", "Task has changed. Reload and retry.");
}

function requireBoardVersion(boardVersion: number, expectedVersion: number) {
  if (boardVersion !== expectedVersion) throw new ApiError(409, "BOARD_VERSION_CONFLICT", "Board has changed. Reload and retry.");
}

async function requireAssignee(tx: Transaction, workspaceId: string, assigneeId?: string | null) {
  if (assigneeId && !await tx.workspaceMember.findUnique({
    where: membershipKey(workspaceId, assigneeId), select: { userId: true },
  })) {
    throw new ApiError(422, "INVALID_ASSIGNEE", "Assignee must belong to the workspace");
  }
}

async function countInColumn(tx: Transaction, projectId: string, columnId: string) {
  const column = await tx.boardColumn.findUnique({
    where: { projectId_id: { projectId, id: columnId } }, select: { _count: { select: { tasks: true } } },
  });
  if (!column) throw new ApiError(422, "INVALID_TARGET_COLUMN", "Column must belong to the task's project");
  return column._count.tasks;
}

async function incrementBoardVersion(tx: Transaction, projectId: string) {
  const { boardVersion } = await tx.project.update({
    where: { id: projectId }, data: { boardVersion: { increment: 1 } }, select: { boardVersion: true },
  });
  return boardVersion;
}

function shiftPositions(tx: Transaction, columnId: string, offset: number, first: number, last?: number) {
  // Leave neighboring tasks' versions and timestamps alone. PostgreSQL checks the final positions at commit.
  const end = last === undefined ? Prisma.empty : Prisma.sql`AND position <= ${last}`;
  return tx.$executeRaw`
    UPDATE tasks SET position = position + ${offset}::int
    WHERE column_id = ${columnId}::uuid AND position >= ${first} ${end}
  `;
}

async function makeRoom(tx: Transaction, task: Task, columnId: string, position: number) {
  if (columnId !== task.columnId) {
    await shiftPositions(tx, task.columnId, -1, task.position + 1);
    await shiftPositions(tx, columnId, 1, position);
  } else if (position < task.position) {
    await shiftPositions(tx, columnId, 1, position, task.position - 1);
  } else if (position > task.position) {
    await shiftPositions(tx, columnId, -1, task.position + 1, position);
  }
}

function movement(before: Task, after: Task, actorId: string) {
  return taskActivity(before.id, actorId, "MOVED", {
    fromColumnId: before.columnId, toColumnId: after.columnId,
    fromPosition: before.position, toPosition: after.position,
  });
}

function editActivities(before: Task, after: Task, actorId: string) {
  const changes: Record<string, { from: string | null; to: string | null }> = {};
  for (const field of ["title", "description", "priority", "dueAt"] as const) {
    const from = field === "dueAt" ? before.dueAt?.toISOString() ?? null : before[field];
    const to = field === "dueAt" ? after.dueAt?.toISOString() ?? null : after[field];
    if (from !== to) changes[field] = { from, to };
  }
  const activities = [];
  if (before.columnId !== after.columnId) activities.push(movement(before, after, actorId));
  if (before.assigneeId !== after.assigneeId) {
    activities.push(taskActivity(before.id, actorId, "ASSIGNEE_CHANGED", {
      fromAssigneeId: before.assigneeId, toAssigneeId: after.assigneeId,
    }));
  }
  if (!activities.length || Object.keys(changes).length) {
    activities.push(taskActivity(before.id, actorId, "UPDATED", { changes }));
  }
  return activities;
}

export function createTaskService(database: PrismaClient) {
  return {
    list: (projectId: string, userId: string, { q, columnId, assigneeId, priority, page, pageSize }: Filters) =>
      database.$transaction(async (tx) => {
        await projectInScope(tx, projectId, userId);
        const where: Prisma.TaskWhereInput = {
          projectId, columnId, assigneeId, priority,
          title: q ? { contains: q.replace(/[\\%_]/g, "\\$&"), mode: "insensitive" } : undefined,
        };
        const total = await tx.task.count({ where });
        const skip = (page - 1) * pageSize;
        const tasks = skip >= total ? [] : await tx.task.findMany({
          where, select: taskFields, orderBy: [{ column: { position: "asc" } }, ...taskOrder], skip, take: pageSize,
        });
        return { data: tasks.map((task) => taskData(task)), meta: { page, pageSize, total } };
      }, { isolationLevel: "RepeatableRead" }),

    get: (taskId: string, userId: string) => database.$transaction(async (tx) => {
      const { task, project } = await taskInScope(tx, taskId, userId);
      return taskData(task, project.boardVersion);
    }, { isolationLevel: "RepeatableRead" }),

    create: (projectId: string, userId: string, input: NewTask, idempotency: Idempotency) =>
      database.$transaction(async (tx) => {
        const project = await projectInScope(tx, projectId, userId);
        await lockProject(tx, project, userId);
        return createOnce(tx, userId, { operation: "CREATE_TASK", projectId }, idempotency, async () => {
          const position = await countInColumn(tx, projectId, input.columnId);
          await requireAssignee(tx, project.workspaceId, input.assigneeId);
          const task = await tx.task.create({
            data: { ...input, projectId, position, createdBy: userId },
            select: taskFields,
          });
          await tx.taskActivity.create({
            data: taskActivity(task.id, userId, "CREATED", { columnId: task.columnId, assigneeId: task.assigneeId }),
            select: { id: true },
          });
          return taskData(task, await incrementBoardVersion(tx, projectId));
        });
      }, { isolationLevel: "ReadCommitted" }),

    update: (taskId: string, userId: string, { expectedVersion, expectedBoardVersion, columnId, ...changes }: TaskPatch) =>
      database.$transaction(async (tx) => {
        const { task, boardVersion, workspaceId } = await lockTaskProject(tx, taskId, userId);
        requireTaskVersion(task, expectedVersion);
        const newColumn = columnId !== undefined && columnId !== task.columnId;
        if (newColumn) {
          if (expectedBoardVersion === undefined) throw invalidBody("expectedBoardVersion is required when changing columns");
          requireBoardVersion(boardVersion, expectedBoardVersion);
        }
        await requireAssignee(tx, workspaceId, changes.assigneeId);
        const position = newColumn ? await countInColumn(tx, task.projectId, columnId) : task.position;
        if (newColumn) await makeRoom(tx, task, columnId, position);
        const updated = await tx.task.update({
          where: { id: taskId },
          data: { ...changes, columnId, position, version: { increment: 1 } },
          select: taskFields,
        });
        await tx.taskActivity.createMany({ data: editActivities(task, updated, userId) });
        return taskData(updated, newColumn ? await incrementBoardVersion(tx, task.projectId) : boardVersion);
      }, { isolationLevel: "ReadCommitted" }),

    move: (taskId: string, userId: string, { targetColumnId, targetIndex, expectedVersion, expectedBoardVersion }: Move) =>
      database.$transaction(async (tx) => {
        const { task, boardVersion } = await lockTaskProject(tx, taskId, userId);
        requireTaskVersion(task, expectedVersion);
        requireBoardVersion(boardVersion, expectedBoardVersion);
        const count = await countInColumn(tx, task.projectId, targetColumnId);
        const sameColumn = task.columnId === targetColumnId;
        if (targetIndex < 0 || targetIndex > count - Number(sameColumn)) {
          throw new ApiError(422, "INVALID_TARGET_INDEX", "targetIndex is outside the destination column");
        }
        if (sameColumn && task.position === targetIndex) return taskData(task, boardVersion);
        await makeRoom(tx, task, targetColumnId, targetIndex);
        const moved = await tx.task.update({
          where: { id: taskId },
          data: { columnId: targetColumnId, position: targetIndex, version: { increment: 1 } },
          select: taskFields,
        });
        await tx.taskActivity.create({ data: movement(task, moved, userId), select: { id: true } });
        return taskData(moved, await incrementBoardVersion(tx, task.projectId));
      }, { isolationLevel: "ReadCommitted" }),

    remove: (taskId: string, userId: string) => database.$transaction(async (tx) => {
      const { task, role } = await lockTaskProject(tx, taskId, userId);
      if (role !== "OWNER" && task.createdBy !== userId) {
        throw new ApiError(403, "FORBIDDEN", "Workspace owner or task creator access is required");
      }
      await tx.idempotencyRequest.updateMany({
        where: { projectId: task.projectId, operation: "CREATE_TASK", resultId: taskId },
        data: { responseBody: Prisma.DbNull },
      });
      await tx.task.delete({ where: { id: taskId }, select: { id: true } });
      await shiftPositions(tx, task.columnId, -1, task.position + 1);
      await incrementBoardVersion(tx, task.projectId);
    }, { isolationLevel: "ReadCommitted" }),
  };
}
