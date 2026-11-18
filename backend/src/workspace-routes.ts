import type { FastifyPluginAsync, FastifyRequest } from "fastify";
import { apiErrorHandler } from "./api-errors.js";
import type { PrismaClient } from "./generated/prisma/client.js";
import { bodyObject, invalidBody, readEmail, readId, readName, readNewResource, readResourcePatch } from "./validation.js";
import { createWorkspaceService } from "./workspaces.js";

type RouteId<Field extends string> = { Params: Record<Field, string> };
type WorkspaceRoute = RouteId<"workspaceId">;
type ProjectRoute = RouteId<"projectId">;
type WorkspaceRoutesOptions = {
  database: PrismaClient;
  requireAuth: (request: FastifyRequest) => Promise<void>;
};

function userId(request: FastifyRequest) {
  // The onRequest hook sets the authenticated user before any handler runs.
  return request.authUser!.id;
}

export const registerWorkspaceRoutes: FastifyPluginAsync<WorkspaceRoutesOptions> = async (app, options) => {
  const service = createWorkspaceService(options.database);
  app.addHook("onRequest", options.requireAuth);
  app.setErrorHandler(apiErrorHandler("Workspace request failed"));

  app.get("/workspaces", async (request) => ({ data: await service.listWorkspaces(userId(request)) }));

  app.post("/workspaces", async (request, reply) => {
    const data = await service.createWorkspace(userId(request), readNewResource(request.body));
    return reply.code(201).send({ data });
  });

  app.get<WorkspaceRoute>("/workspaces/:workspaceId", async (request) => ({
    data: await service.getWorkspace(readId(request.params.workspaceId, "workspaceId"), userId(request)),
  }));

  app.patch<WorkspaceRoute>("/workspaces/:workspaceId", async (request) => ({
    data: await service.updateWorkspace(readId(request.params.workspaceId, "workspaceId"), userId(request), readResourcePatch(request.body)),
  }));

  app.delete<WorkspaceRoute>("/workspaces/:workspaceId", async (request, reply) => {
    await service.deleteWorkspace(readId(request.params.workspaceId, "workspaceId"), userId(request));
    return reply.code(204).send();
  });

  app.get<WorkspaceRoute>("/workspaces/:workspaceId/members", async (request) => ({
    data: await service.listMembers(readId(request.params.workspaceId, "workspaceId"), userId(request)),
  }));

  app.get<WorkspaceRoute>("/workspaces/:workspaceId/users/search", async (request) => ({
    data: await service.searchUser(readId(request.params.workspaceId, "workspaceId"), userId(request), readEmail(request.query)),
  }));

  app.post<WorkspaceRoute>("/workspaces/:workspaceId/members", async (request, reply) => {
    const body = bodyObject(request.body);
    if (body.role !== "MEMBER") throw invalidBody("role must be MEMBER");
    const data = await service.addMember(
      readId(request.params.workspaceId, "workspaceId"), userId(request), readId(body.userId, "userId"),
    );
    return reply.code(201).send({ data });
  });

  app.delete<RouteId<"workspaceId" | "userId">>("/workspaces/:workspaceId/members/:userId", async (request, reply) => {
    await service.removeMember(
      readId(request.params.workspaceId, "workspaceId"), userId(request), readId(request.params.userId, "userId"),
    );
    return reply.code(204).send();
  });

  app.get<WorkspaceRoute>("/workspaces/:workspaceId/projects", async (request) => ({
    data: await service.listProjects(readId(request.params.workspaceId, "workspaceId"), userId(request)),
  }));

  app.post<WorkspaceRoute>("/workspaces/:workspaceId/projects", async (request, reply) => {
    const data = await service.createProject(
      readId(request.params.workspaceId, "workspaceId"), userId(request), readNewResource(request.body),
    );
    return reply.code(201).send({ data });
  });

  app.get<ProjectRoute>("/projects/:projectId", async (request) => ({
    data: await service.getProject(readId(request.params.projectId, "projectId"), userId(request)),
  }));

  app.patch<ProjectRoute>("/projects/:projectId", async (request) => ({
    data: await service.updateProject(readId(request.params.projectId, "projectId"), userId(request), readResourcePatch(request.body)),
  }));

  app.delete<ProjectRoute>("/projects/:projectId", async (request, reply) => {
    await service.deleteProject(readId(request.params.projectId, "projectId"), userId(request));
    return reply.code(204).send();
  });

  app.get<ProjectRoute>("/projects/:projectId/board", async (request) => ({
    data: await service.getBoard(readId(request.params.projectId, "projectId"), userId(request)),
  }));

  app.post<ProjectRoute>("/projects/:projectId/columns", async (request, reply) => {
    const data = await service.addColumn(readId(request.params.projectId, "projectId"), userId(request), readName(request.body));
    return reply.code(201).send({ data });
  });

  app.patch<RouteId<"columnId">>("/columns/:columnId", async (request) => ({
    data: await service.renameColumn(readId(request.params.columnId, "columnId"), userId(request), readName(request.body)),
  }));
};
