import { buildApp } from "./app.js";
import { readDatabaseUrl, readListenOptions } from "./config.js";
import { createPrismaClient } from "./database.js";

async function startServer() {
  const listenOptions = readListenOptions();
  const database = createPrismaClient(readDatabaseUrl());
  const app = buildApp({
    logger: true,
    checkDatabase: async () => {
      await database.$queryRaw`SELECT 1`;
    },
  });
  app.addHook("onClose", () => database.$disconnect());

  try {
    await app.listen(listenOptions);
  } catch (error) {
    await app.close();
    throw error;
  }

  const stopServer = async () => {
    try {
      await app.close();
    } catch {
      app.log.error("Failed to close the Sprintly API");
      process.exitCode = 1;
    }
  };
  process.once("SIGINT", stopServer);
  process.once("SIGTERM", stopServer);
}

try {
  await startServer();
} catch (error) {
  console.error(error instanceof Error ? error.message : "Failed to start the Sprintly API");
  process.exitCode = 1;
}
