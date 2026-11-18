import type { FastifyPluginAsync } from "fastify";
import type { PrismaClient } from "./generated/prisma/client.js";
import { createTaskCommentService } from "./task-comments.js";
import { readIdempotency, readPagination, readTaskText } from "./task-validation.js";
import { bodyObject, readId } from "./validation.js";

type TaskRoute = { Params: { taskId: string } };

export const registerTaskCommentRoutes: FastifyPluginAsync<{ database: PrismaClient }> = async (app, { database }) => {
  const service = createTaskCommentService(database);

  app.get<TaskRoute>("/tasks/:taskId/comments", async (request) =>
    service.listComments(readId(request.params.taskId, "taskId"), request.authUser!.id, readPagination(request.query)),
  );

  app.post<TaskRoute>("/tasks/:taskId/comments", async (request, reply) => {
    const payload = bodyObject(request.body);
    const body = readTaskText(payload.body, "Comment", 2000);
    const idempotency = readIdempotency(request.headers["idempotency-key"], payload, ["body"]);
    const result = await service.addComment(readId(request.params.taskId, "taskId"), request.authUser!.id, body, idempotency);
    return reply.code(result.status).send(result.body);
  });

  app.get<TaskRoute>("/tasks/:taskId/activities", async (request) =>
    service.listActivities(readId(request.params.taskId, "taskId"), request.authUser!.id, readPagination(request.query)),
  );
};
