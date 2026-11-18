import assert from "node:assert/strict";
import { test } from "node:test";
import { buildApp } from "../src/app.js";

test("GET /health reports readiness after checking PostgreSQL", async (t) => {
  const checkDatabase = t.mock.fn(async () => undefined);
  const app = buildApp({ checkDatabase });
  t.after(() => app.close());

  const response = await app.inject({ method: "GET", url: "/health" });

  assert.equal(response.statusCode, 200);
  assert.deepEqual(response.json(), { data: { status: "ok" } });
  assert.equal(checkDatabase.mock.callCount(), 1);
});

test("GET /health returns a sanitized 503 when PostgreSQL is unavailable", async (t) => {
  const app = buildApp({
    checkDatabase: async () => {
      throw new Error("Private PostgreSQL connection details");
    },
  });
  t.after(() => app.close());

  const response = await app.inject({ method: "GET", url: "/health" });

  assert.equal(response.statusCode, 503);
  assert.deepEqual(response.json(), {
    error: { code: "DATABASE_UNAVAILABLE", message: "Database unavailable" },
  });
});
