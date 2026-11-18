import "dotenv/config";

export function readDatabaseUrl(): string {
  const databaseUrl = process.env.DATABASE_URL;
  if (!databaseUrl || !/^postgres(?:ql)?:\/\//i.test(databaseUrl)) {
    throw new Error("Set DATABASE_URL to a PostgreSQL connection URL.");
  }
  return databaseUrl;
}

export function readListenOptions() {
  const host = process.env.HOST ?? "127.0.0.1";
  const port = Number(process.env.PORT ?? 3000);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error("Set PORT to an integer between 1 and 65535.");
  }
  return { host, port };
}
