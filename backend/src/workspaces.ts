import { ApiError } from "./api-errors.js";
import { publicUserFields } from "./auth.js";
import { Prisma } from "./generated/prisma/client.js";
import type { PrismaClient } from "./generated/prisma/client.js";

const workspaceFields = { id: true, name: true, description: true } as const;
const membershipFields = { role: true, workspace: { select: workspaceFields } } as const;
const projectFields = { id: true, workspaceId: true, name: true, description: true, boardVersion: true } as const;
const columnFields = { id: true, name: true, position: true } as const;
export const defaultBoardColumns = ["To Do", "In Progress", "Done"].map((name, position) => ({ name, position }));

type Transaction = Prisma.TransactionClient;
type ResourceDetails = { name: string; description?: string | null };
type BoardColumn = Prisma.BoardColumnGetPayload<{ select: typeof columnFields }>;

function notFound(resource: string) {
  return new ApiError(404, "NOT_FOUND", `${resource} not found`);
}

function membershipKey(workspaceId: string, userId: string) {
  return { workspaceId_userId: { workspaceId, userId } };
}

async function requireMembership(tx: Transaction, workspaceId: string, userId: string, ownerOnly = false) {
  const membership = await tx.workspaceMember.findUnique({
    where: membershipKey(workspaceId, userId), select: membershipFields,
  });
  if (!membership) throw notFound("Workspace");
  if (ownerOnly && membership.role !== "OWNER") throw new ApiError(403, "FORBIDDEN", "Workspace owner access is required");
  return membership;
}

async function lockWorkspace(tx: Transaction, workspaceId: string, userId: string, mode: "manage" | "collaborate") {
  const lock = mode === "manage" ? Prisma.sql`FOR UPDATE` : Prisma.sql`FOR SHARE`;
  // Membership may change while waiting to acquire the lock, so callers recheck it after this statement.
  const rows = await tx.$queryRaw<{ id: string }[]>`
    SELECT w.id FROM workspaces w WHERE w.id = ${workspaceId}::uuid
    AND EXISTS (
      SELECT 1 FROM workspace_members m WHERE m.workspace_id = w.id AND m.user_id = ${userId}::uuid
    ) ${lock}
  `;
  if (!rows.length) throw notFound("Workspace");
}

function projectScope(id: string, userId: string) {
  return { id, workspace: { members: { some: { userId } } } };
}

async function projectInScope(tx: Transaction, id: string, userId: string) {
  const project = await tx.project.findFirst({ where: projectScope(id, userId), select: projectFields });
  if (!project) throw notFound("Project");
  return project;
}

async function lockProject(tx: Transaction, { id, workspaceId }: { id: string; workspaceId: string }, userId: string, ownerOnly = false) {
  // Use workspace-then-project lock order. Project writes can proceed in parallel across projects,
  // while membership changes acquire the workspace lock exclusively.
  await lockWorkspace(tx, workspaceId, userId, "collaborate");
  await requireMembership(tx, workspaceId, userId, ownerOnly);
  const rows = await tx.$queryRaw<{ id: string }[]>`
    SELECT id FROM projects WHERE id = ${id}::uuid AND workspace_id = ${workspaceId}::uuid FOR UPDATE
  `;
  if (!rows.length) throw notFound("Project");
}

function boardColumn(column: BoardColumn) {
  // Task storage and reassignment arrive in Phase 3.
  return { ...column, tasks: [] };
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
      // Phase 3 will clear task assignments and write activity here under the same workspace lock.
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
