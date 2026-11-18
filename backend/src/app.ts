import Fastify, { LogController } from "fastify";
import { registerAuthRoutes } from "./auth-routes.js";
import type { AuthOptions } from "./auth.js";

interface AppOptions {
  checkDatabase: () => Promise<void>;
  logger?: boolean;
  auth?: AuthOptions;
}

export function buildApp({ checkDatabase, logger = false, auth }: AppOptions) {
  const app = Fastify({ logger, logController: new LogController({ disableRequestLogging: true }) });

  app.get("/health", async (request, reply) => {
    try {
      await checkDatabase();
      return { data: { status: "ok" } };
    } catch {
      request.log.warn("PostgreSQL readiness check failed");
      return reply.code(503).send({
        error: { code: "DATABASE_UNAVAILABLE", message: "Database unavailable" },
      });
    }
  });

  if (auth) {
    app.register(registerAuthRoutes, { ...auth, prefix: "/api/v1" });
  }

  return app;
}
