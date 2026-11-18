import rateLimit from "@fastify/rate-limit";
import type { FastifyPluginAsync, FastifyRequest } from "fastify";
import { AuthError, createAuth } from "./auth.js";
import type { AuthOptions, PublicUser } from "./auth.js";

declare module "fastify" {
  interface FastifyRequest {
    authUser: PublicUser | null;
  }
}

const emailLocalPattern = /^[a-z0-9!#$%&'*+/=?^_`{|}~-]+(\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*$/;
const emailDomainPattern = /^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;

function invalidBody(message: string) {
  return new AuthError(400, "VALIDATION_ERROR", message);
}

function stringField(body: unknown, field: string) {
  if (typeof body !== "object" || body === null || Array.isArray(body)) {
    throw invalidBody("An application/json object is required");
  }
  const value = (body as Record<string, unknown>)[field];
  if (typeof value !== "string" || !value) throw invalidBody(`${field} is required`);
  return value;
}

function readEmail(body: unknown) {
  const email = stringField(body, "email").trim().toLowerCase();
  const [local, domain, extra] = email.split("@");
  if (email.length > 254 || local.length > 64 || !domain || extra !== undefined ||
    !emailLocalPattern.test(local) || !emailDomainPattern.test(domain)) {
    throw invalidBody("Enter a valid email address");
  }
  return email;
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
  app.setErrorHandler((error, request, reply) => {
    if (error instanceof AuthError) {
      if (error.code === "UNAUTHORIZED") reply.header("WWW-Authenticate", "Bearer");
      return reply.code(error.statusCode).send({ error: { code: error.code, message: error.message } });
    }
    if (typeof error === "object" && error && "statusCode" in error &&
      typeof error.statusCode === "number" && error.statusCode >= 400 && error.statusCode < 500) {
      return reply.code(400).send({
        error: { code: "VALIDATION_ERROR", message: "An application/json object is required" },
      });
    }

    request.log.error("An authentication request failed");
    return reply.code(500).send({ error: { code: "INTERNAL_ERROR", message: "Authentication request failed" } });
  });

  await app.register(rateLimit, {
    global: false,
    timeWindow: "1 minute",
    errorResponseBuilder: () => new AuthError(429, "RATE_LIMITED", "Too many authentication requests"),
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
};
