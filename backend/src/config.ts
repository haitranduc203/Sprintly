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

export function readAuthOptions() {
  const jwtSecret = process.env.AUTH_JWT_SECRET;
  if (!jwtSecret || !jwtSecret.trim() || Buffer.byteLength(jwtSecret, "utf8") < 32) {
    throw new Error("Set AUTH_JWT_SECRET to a random secret of at least 32 UTF-8 bytes.");
  }

  const accessTokenTtlSeconds = Number(process.env.ACCESS_TOKEN_TTL_SECONDS ?? 900);
  if (!Number.isInteger(accessTokenTtlSeconds) || accessTokenTtlSeconds < 1 || accessTokenTtlSeconds > 900) {
    throw new Error("Set ACCESS_TOKEN_TTL_SECONDS to an integer between 1 and 900.");
  }

  return { jwtSecret, accessTokenTtlSeconds };
}
