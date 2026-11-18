import { ApiError } from "./api-errors.js";
import { Prisma } from "./generated/prisma/client.js";

export type Transaction = Prisma.TransactionClient;

export const workspaceFields = { id: true, name: true, description: true } as const;
export const membershipFields = { role: true, workspace: { select: workspaceFields } } as const;
export const projectFields = { id: true, workspaceId: true, name: true, description: true, boardVersion: true } as const;

export function notFound(resource: string) {
  return new ApiError(404, "NOT_FOUND", `${resource} not found`);
}

export function membershipKey(workspaceId: string, userId: string) {
  return { workspaceId_userId: { workspaceId, userId } };
}

export async function requireMembership(tx: Transaction, workspaceId: string, userId: string, ownerOnly = false) {
  const membership = await tx.workspaceMember.findUnique({
    where: membershipKey(workspaceId, userId), select: membershipFields,
  });
  if (!membership) throw notFound("Workspace");
  if (ownerOnly && membership.role !== "OWNER") throw new ApiError(403, "FORBIDDEN", "Workspace owner access is required");
  return membership;
}

export async function lockWorkspace(tx: Transaction, workspaceId: string, userId: string, mode: "manage" | "collaborate") {
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

export function projectScope(userId: string) {
  return { workspace: { members: { some: { userId } } } };
}

export async function projectInScope(tx: Transaction, id: string, userId: string) {
  const project = await tx.project.findFirst({ where: { id, ...projectScope(userId) }, select: projectFields });
  if (!project) throw notFound("Project");
  return project;
}

export async function lockProject(
  tx: Transaction, { id, workspaceId }: { id: string; workspaceId: string }, userId: string, ownerOnly = false,
) {
  // Project writes share the workspace lock. Membership changes take it exclusively.
  await lockWorkspace(tx, workspaceId, userId, "collaborate");
  const { role } = await requireMembership(tx, workspaceId, userId, ownerOnly);
  const [project] = await tx.$queryRaw<{ boardVersion: number }[]>`
    SELECT board_version AS "boardVersion" FROM projects
    WHERE id = ${id}::uuid AND workspace_id = ${workspaceId}::uuid FOR UPDATE
  `;
  if (!project) throw notFound("Project");
  return { ...project, role };
}
