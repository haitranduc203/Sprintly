import type { FastifyError, FastifyReply, FastifyRequest } from "fastify";

export class ApiError extends Error {
  constructor(readonly statusCode: number, readonly code: string, message: string) {
    super(message);
  }
}

export function apiErrorHandler(internalErrorMessage: string) {
  return (error: FastifyError | ApiError, request: FastifyRequest, reply: FastifyReply) => {
    if (error instanceof ApiError) {
      if (error.code === "UNAUTHORIZED") reply.header("WWW-Authenticate", "Bearer");
      return reply.code(error.statusCode).send({ error: { code: error.code, message: error.message } });
    }
    if (typeof error.statusCode === "number" && error.statusCode >= 400 && error.statusCode < 500) {
      return reply.code(400).send({
        error: { code: "VALIDATION_ERROR", message: "An application/json object is required" },
      });
    }

    request.log.error(internalErrorMessage);
    return reply.code(500).send({ error: { code: "INTERNAL_ERROR", message: internalErrorMessage } });
  };
}
