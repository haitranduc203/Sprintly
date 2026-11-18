import rateLimit from "@fastify/rate-limit";
import type { FastifyPluginAsync, FastifyRequest } from "fastify";
import { ApiError, apiErrorHandler } from "./api-errors.js";
import { createAuth } from "./auth.js";
import type { AuthOptions, PublicUser } from "./auth.js";
import { invalidBody, readEmail, stringField } from "./validation.js";
import { registerWorkspaceRoutes } from "./workspace-routes.js";
import { registerTaskRoutes } from "./task-routes.js";

declare module "fastify" {
  interface FastifyRequest {
    authUser: PublicUser | null;
  }
}

function readDisplayName(body: unknown) {
  const name = stringField(body, "displayName").trim();
  if (!name || Array.from(name).length > 100 || /\p{Cc}/u.test(name)) {
    throw invalidBody("Display name must have 1 to 100 characters");
  }
  return name;
}

function readPassword(body: unknown, registering = false) {
  const password = stringField(body, "password");
  if (registering && Array.from(password).length < 8) {
    throw invalidBody("Password must have at least 8 characters");
  }
  if (Buffer.byteLength(password, "utf8") > 72) {
    throw invalidBody("Password must be at most 72 UTF-8 bytes");
  }
  return password;
}

export const registerAuthRoutes: FastifyPluginAsync<AuthOptions> = async (app, options) => {
  const auth = createAuth(options);

  app.addHook("onRequest", async (_request, reply) => {
    reply.header("Cache-Control", "no-store");
  });
  app.setErrorHandler(apiErrorHandler("Authentication request failed"));

  await app.register(rateLimit, {
    global: false,
    timeWindow: "1 minute",
    errorResponseBuilder: () => new ApiError(429, "RATE_LIMITED", "Too many authentication requests"),
  });
  const credentials = { bodyLimit: 4096, onRequest: app.rateLimit({ max: 10 }) };
  const refresh = { bodyLimit: 4096, onRequest: app.rateLimit({ max: 30 }) };
  const logout = { bodyLimit: 4096, onRequest: app.rateLimit({ max: 30 }) };

  app.post("/auth/register", credentials, async (request, reply) => {
    const { body } = request;
    const data = await auth.register(readEmail(body), readDisplayName(body), readPassword(body, true));
    return reply.code(201).send({ data });
  });

  app.post("/auth/login", credentials, async ({ body }) => {
    return { data: await auth.login(readEmail(body), readPassword(body)) };
  });

  app.post("/auth/refresh", refresh, async ({ body }) => {
    return { data: await auth.refresh(stringField(body, "refreshToken")) };
  });

  app.post("/auth/logout", logout, async ({ body }, reply) => {
    await auth.logout(stringField(body, "refreshToken"));
    return reply.code(204).send();
  });

  app.decorateRequest("authUser", null);
  const requireAuth = async (request: FastifyRequest) => {
    request.authUser = await auth.authenticate(request.headers.authorization);
  };
  app.get("/users/me", { preHandler: requireAuth }, async (request) => ({ data: request.authUser }));
  await app.register(registerWorkspaceRoutes, { database: options.database, requireAuth });
  await app.register(registerTaskRoutes, { database: options.database, requireAuth });
};
