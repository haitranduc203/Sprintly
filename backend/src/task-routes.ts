import type { FastifyPluginAsync, FastifyRequest } from "fastify";
import { apiErrorHandler } from "./api-errors.js";
import type { PrismaClient } from "./generated/prisma/client.js";
import { readMove, readNewTask, readTaskFilters, readTaskPatch } from "./task-validation.js";
import { createTaskService } from "./tasks.js";
import { readId } from "./validation.js";

type ProjectRoute = { Params: { projectId: string } };
type TaskRoute = { Params: { taskId: string } };
type TaskRoutesOptions = {
  database: PrismaClient;
  requireAuth: (request: FastifyRequest) => Promise<void>;
};

export const registerTaskRoutes: FastifyPluginAsync<TaskRoutesOptions> = async (app, options) => {
  const service = createTaskService(options.database);
  app.addHook("onRequest", options.requireAuth);
  app.setErrorHandler(apiErrorHandler("Task request failed"));

  app.get<ProjectRoute>("/projects/:projectId/tasks", async (request) =>
    service.list(readId(request.params.projectId, "projectId"), request.authUser!.id, readTaskFilters(request.query)),
  );

  app.post<ProjectRoute>("/projects/:projectId/tasks", async (request, reply) => {
    const { input, idempotency } = readNewTask(request.body, request.headers["idempotency-key"]);
    const { status, body } = await service.create(readId(request.params.projectId, "projectId"), request.authUser!.id, input, idempotency);
    return reply.code(status).send(body);
  });

  app.get<TaskRoute>("/tasks/:taskId", async (request) => ({
    data: await service.get(readId(request.params.taskId, "taskId"), request.authUser!.id),
  }));

  app.patch<TaskRoute>("/tasks/:taskId", async (request) => ({
    data: await service.update(readId(request.params.taskId, "taskId"), request.authUser!.id, readTaskPatch(request.body)),
  }));

  app.patch<TaskRoute>("/tasks/:taskId/move", async (request) => ({
    data: await service.move(readId(request.params.taskId, "taskId"), request.authUser!.id, readMove(request.body)),
  }));

  app.delete<TaskRoute>("/tasks/:taskId", async (request, reply) => {
    await service.remove(readId(request.params.taskId, "taskId"), request.authUser!.id);
    return reply.code(204).send();
  });
};
