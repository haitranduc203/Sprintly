import Fastify from "fastify";

interface AppOptions {
  checkDatabase: () => Promise<void>;
  logger?: boolean;
}

export function buildApp({ checkDatabase, logger = false }: AppOptions) {
  const app = Fastify({ logger });

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

  return app;
}
