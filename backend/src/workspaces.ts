import { ApiError } from "./api-errors.js";
import { publicUserFields } from "./auth.js";
import type { PrismaClient } from "./generated/prisma/client.js";
import { taskActivity, taskData, taskFields, taskOrder } from "./task-data.js";
import type { Task } from "./task-data.js";
import {
  lockProject, lockWorkspace, membershipFields, membershipKey, notFound, projectFields, projectInScope,
  requireMembership, workspaceFields,
} from "./workspace-access.js";
import type { Transaction } from "./workspace-access.js";

const columnFields = {
  id: true, name: true, position: true, tasks: { select: taskFields, orderBy: taskOrder },
} as const;
export const defaultBoardColumns = ["To Do", "In Progress", "Done"].map((name, position) => ({ name, position }));

type ResourceDetails = { name: string; description?: string | null };
type BoardColumn = { id: string; name: string; position: number; tasks: Task[] };

function boardColumn({ tasks, ...column }: BoardColumn) {
  return { ...column, tasks: tasks.map((task) => taskData(task)) };
}

export function createWorkspaceService(database: PrismaClient) {
  function read<T>(operation: (tx: Transaction) => Promise<T>) {
    return database.$transaction(operation, { isolationLevel: "RepeatableRead" });
  }

  function write<T>(operation: (tx: Transaction) => Promise<T>) {
    return database.$transaction(operation, { isolationLevel: "ReadCommitted" });
  }

  function manageWorkspace<T>(workspaceId: string, userId: string, operation: (tx: Transaction) => Promise<T>) {
    return write(async (tx) => {
      await lockWorkspace(tx, workspaceId, userId, "manage");
      await requireMembership(tx, workspaceId, userId, true);
      return operation(tx);
    });
  }

  return {
    listWorkspaces: (userId: string) => read(async (tx) => {
      const memberships = await tx.workspaceMember.findMany({
        where: { userId }, select: membershipFields,
        orderBy: [{ workspace: { createdAt: "asc" } }, { workspaceId: "asc" }],
      });
      return memberships.map(({ workspace, role }) => ({ ...workspace, role }));
    }),

    async createWorkspace(userId: string, details: ResourceDetails) {
      const workspace = await database.workspace.create({
        data: { ...details, createdBy: userId, members: { create: { userId, role: "OWNER" } } },
        select: workspaceFields,
      });
      return { ...workspace, role: "OWNER" as const };
    },

    getWorkspace: (workspaceId: string, userId: string) => read(async (tx) => {
      const { workspace, role } = await requireMembership(tx, workspaceId, userId);
      return { ...workspace, role };
    }),

    updateWorkspace: (workspaceId: string, userId: string, details: Partial<ResourceDetails>) =>
      manageWorkspace(workspaceId, userId, async (tx) => {
        const workspace = await tx.workspace.update({ where: { id: workspaceId }, data: details, select: workspaceFields });
        return { ...workspace, role: "OWNER" as const };
      }),

    deleteWorkspace: (workspaceId: string, userId: string) => manageWorkspace(workspaceId, userId, async (tx) => {
      await tx.workspace.delete({ where: { id: workspaceId }, select: { id: true } });
    }),

    listMembers: (workspaceId: string, userId: string) => read(async (tx) => {
      await requireMembership(tx, workspaceId, userId);
      const memberships = await tx.workspaceMember.findMany({
        where: { workspaceId },
        select: { role: true, user: { select: publicUserFields } },
        orderBy: [{ joinedAt: "asc" }, { userId: "asc" }],
      });
      return memberships.map(({ user, role }) => ({ ...user, role }));
    }),

    searchUser: (workspaceId: string, userId: string, email: string) => read(async (tx) => {
      await requireMembership(tx, workspaceId, userId, true);
      return tx.user.findUnique({ where: { email }, select: publicUserFields });
    }),

    addMember: (workspaceId: string, userId: string, addedUserId: string) => manageWorkspace(workspaceId, userId, async (tx) => {
      const user = await tx.user.findUnique({ where: { id: addedUserId }, select: publicUserFields });
      if (!user) throw notFound("User");
      const result = await tx.workspaceMember.createMany({
        data: [{ workspaceId, userId: addedUserId, role: "MEMBER" }],
        skipDuplicates: true,
      });
      if (result.count === 0) throw new ApiError(409, "MEMBER_ALREADY_EXISTS", "This user already belongs to the workspace");
      return { ...user, role: "MEMBER" as const };
    }),

    removeMember: (workspaceId: string, userId: string, removedUserId: string) => manageWorkspace(workspaceId, userId, async (tx) => {
      const where = membershipKey(workspaceId, removedUserId);
      const membership = await tx.workspaceMember.findUnique({ where, select: { role: true } });
      if (!membership) throw notFound("Member");
      if (membership.role === "OWNER") {
        const anotherOwner = await tx.workspaceMember.findFirst({
          where: { workspaceId, role: "OWNER", userId: { not: removedUserId } }, select: { userId: true },
        });
        if (!anotherOwner) throw new ApiError(409, "LAST_WORKSPACE_OWNER", "The workspace must keep an owner");
      }
      const unassigned = await tx.task.updateManyAndReturn({
        where: { assigneeId: removedUserId, project: { workspaceId } },
        data: { assigneeId: null, version: { increment: 1 } },
        select: { id: true },
      });
      if (unassigned.length) {
        await tx.taskActivity.createMany({
          data: unassigned.map(({ id }) => taskActivity(id, userId, "ASSIGNEE_CHANGED", { fromAssigneeId: removedUserId, toAssigneeId: null })),
        });
      }
      await tx.workspaceMember.delete({ where });
    }),

    listProjects: (workspaceId: string, userId: string) => read(async (tx) => {
      await requireMembership(tx, workspaceId, userId);
      return tx.project.findMany({ where: { workspaceId }, select: projectFields, orderBy: [{ createdAt: "asc" }, { id: "asc" }] });
    }),

    createProject: (workspaceId: string, userId: string, details: ResourceDetails) => write(async (tx) => {
      await lockWorkspace(tx, workspaceId, userId, "collaborate");
      await requireMembership(tx, workspaceId, userId);
      return tx.project.create({
        data: { ...details, workspaceId, columns: { create: defaultBoardColumns } },
        select: projectFields,
      });
    }),

    getProject: (id: string, userId: string) => read((tx) => projectInScope(tx, id, userId)),

    updateProject: (id: string, userId: string, details: Partial<ResourceDetails>) => write(async (tx) => {
      const project = await projectInScope(tx, id, userId);
      await lockProject(tx, project, userId);
      return tx.project.update({ where: { id }, data: details, select: projectFields });
    }),

    deleteProject: (id: string, userId: string) => write(async (tx) => {
      const project = await projectInScope(tx, id, userId);
      await lockProject(tx, project, userId, true);
      await tx.project.delete({ where: { id }, select: { id: true } });
    }),

    getBoard: (id: string, userId: string) => read(async (tx) => {
      const { boardVersion } = await projectInScope(tx, id, userId);
      const columns = await tx.boardColumn.findMany({
        where: { projectId: id }, select: columnFields, orderBy: { position: "asc" },
      });
      return { boardVersion, columns: columns.map(boardColumn) };
    }),

    addColumn: (projectId: string, userId: string, name: string) => write(async (tx) => {
      const project = await projectInScope(tx, projectId, userId);
      await lockProject(tx, project, userId);
      const last = await tx.boardColumn.findFirst({ where: { projectId }, select: { position: true }, orderBy: { position: "desc" } });
      const column = await tx.boardColumn.create({
        data: { projectId, name, position: (last?.position ?? -1) + 1 }, select: columnFields,
      });
      await tx.project.update({ where: { id: projectId }, data: { boardVersion: { increment: 1 } }, select: { id: true } });
      return boardColumn(column);
    }),

    renameColumn: (id: string, userId: string, name: string) => write(async (tx) => {
      const column = await tx.boardColumn.findFirst({
        where: { id, project: { workspace: { members: { some: { userId } } } } },
        select: { project: { select: { id: true, workspaceId: true } } },
      });
      if (!column) throw notFound("Column");
      await lockProject(tx, column.project, userId);
      return boardColumn(await tx.boardColumn.update({ where: { id }, data: { name }, select: columnFields }));
    }),
  };
}
