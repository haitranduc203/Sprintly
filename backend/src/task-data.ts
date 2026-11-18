import { publicUserFields } from "./auth.js";
import type { Prisma } from "./generated/prisma/client.js";
import { lockProject, notFound, projectScope } from "./workspace-access.js";
import type { Transaction } from "./workspace-access.js";

export const taskFields = {
  id: true,
  projectId: true,
  columnId: true,
  title: true,
  description: true,
  priority: true,
  assigneeId: true,
  assignee: { select: publicUserFields },
  dueAt: true,
  position: true,
  version: true,
  createdBy: true,
  createdAt: true,
  updatedAt: true,
} as const satisfies Prisma.TaskSelect;

export const taskOrder = [{ position: "asc" }, { id: "asc" }] satisfies Prisma.TaskOrderByWithRelationInput[];

export type Task = Prisma.TaskGetPayload<{ select: typeof taskFields }>;

export function taskData(task: Task, boardVersion?: number) {
  return {
    ...task,
    dueAt: task.dueAt?.toISOString() ?? null,
    createdAt: task.createdAt.toISOString(),
    updatedAt: task.updatedAt.toISOString(),
    ...(boardVersion === undefined ? {} : { boardVersion }),
  };
}

export async function taskInScope(tx: Transaction, id: string, userId: string) {
  const task = await tx.task.findFirst({
    where: { id, project: projectScope(userId) },
    select: { ...taskFields, project: { select: { id: true, workspaceId: true, boardVersion: true } } },
  });
  if (!task) throw notFound("Task");
  const { project, ...fields } = task;
  return { project, task: fields };
}

export async function lockTaskProject(tx: Transaction, id: string, userId: string) {
  const { project } = await taskInScope(tx, id, userId);
  const locked = await lockProject(tx, project, userId);
  const task = await tx.task.findUnique({ where: { id }, select: taskFields });
  if (!task) throw notFound("Task");
  return { ...locked, task, workspaceId: project.workspaceId };
}

export function taskActivity(
  taskId: string, actorId: string, action: "CREATED" | "UPDATED" | "MOVED" | "ASSIGNEE_CHANGED", payload: Prisma.InputJsonObject,
) {
  return { taskId, actorId, action, payload };
}
