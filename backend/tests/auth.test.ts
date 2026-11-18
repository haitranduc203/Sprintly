import assert from "node:assert/strict";
import { createHash, randomBytes, randomUUID } from "node:crypto";
import { test } from "node:test";
import type { TestContext } from "node:test";
import type { FastifyInstance } from "fastify";
import { buildApp } from "../src/app.js";
import { createPrismaClient } from "../src/database.js";

const databaseUrl = process.env.TEST_DATABASE_URL;
const password = "Valid password é";
const jwtSecret = randomBytes(32).toString("hex");

function testApp(t: TestContext, accessTokenTtlSeconds = 900) {
  const database = createPrismaClient(databaseUrl ?? "postgresql://unused@127.0.0.1:1/unused");
  const app = buildApp({
    checkDatabase: async () => { await database.$queryRaw`SELECT 1`; },
    auth: { database, accessTokenTtlSeconds, jwtSecret },
  });
  t.after(async () => {
    await app.close();
    await database.$disconnect();
  });
  return { app, database };
}

function post(app: FastifyInstance, route: string, payload: object) {
  return app.inject({ method: "POST", url: `/api/v1/auth/${route}`, payload });
}

function me(app: FastifyInstance, accessToken: string) {
  return app.inject({
    url: "/api/v1/users/me",
    headers: { authorization: `Bearer ${accessToken}` },
  });
}

test("auth rejects malformed bodies and a missing or malformed bearer header", async (t) => {
  const { app } = testApp(t);
  const inputs = [
    ["register", { email: "user@example.test", displayName: " ", password }],
    ["register", { email: "user@example.test", displayName: "Name", password: "é".repeat(37) }],
    ["register", { email: "user@example.test", displayName: "Name", password: "abc1234" }],
    ["login", { email: "not an email", password }],
    ["login", { email: 23, password }],
    ["refresh", { accessToken: "wrong field" }],
    ["logout", { refreshToken: [] }],
  ] as const;
  for (const [route, payload] of inputs) {
    const response = await post(app, route, payload);
    assert.equal(response.statusCode, 400, route);
    assert.equal(response.json().error.code, "VALIDATION_ERROR", route);
  }

  const malformedJson = await app.inject({
    method: "POST",
    url: "/api/v1/auth/login",
    headers: { "content-type": "application/json" },
    payload: '{"password":"sensitive-example"',
  });
  assert.equal(malformedJson.statusCode, 400);
  assert.equal(malformedJson.json().error.code, "VALIDATION_ERROR");
  assert.doesNotMatch(malformedJson.body, /sensitive-example|SyntaxError/);

  for (const authorization of [undefined, "Basic invalid", "Bearer invalid"]) {
    const response = await app.inject({ url: "/api/v1/users/me", headers: authorization ? { authorization } : {} });
    assert.equal(response.statusCode, 401);
    assert.equal(response.json().error.code, "UNAUTHORIZED");
  }

  const invalid = await post(app, "refresh", { refreshToken: "invalid" });
  assert.equal(invalid.statusCode, 401);
  assert.equal(invalid.json().error.code, "REFRESH_TOKEN_INVALID");
});

test("auth throttles repeated attempts by client IP and sets Retry-After", async (t) => {
  const { app } = testApp(t);
  for (let attempt = 0; attempt < 10; attempt++) {
    const response = await post(app, "login", {});
    assert.equal(response.statusCode, 400);
  }
  const limited = await post(app, "register", {});
  assert.equal(limited.statusCode, 429);
  assert.equal(limited.json().error.code, "RATE_LIMITED");
  assert.ok(Number(limited.headers["retry-after"]) > 0);

  const otherClient = await app.inject({
    method: "POST",
    url: "/api/v1/auth/login",
    remoteAddress: "192.0.2.2",
    payload: {},
  });
  assert.equal(otherClient.statusCode, 400);
});

test("auth lifecycle against PostgreSQL", {
  skip: databaseUrl ? false : "set TEST_DATABASE_URL to a migrated test database",
  timeout: 30_000,
}, async (t) => {
  const { app, database } = testApp(t);
  const email = `${randomUUID()}@example.test`;
  await t.test("register normalizes the profile and persists only password and token hashes", async () => {
    const response = await post(app, "register", { email: ` ${email.toUpperCase()} `, displayName: " Mây ", password });
    assert.equal(response.statusCode, 201);
    const { user, accessToken, refreshToken, expiresIn } = response.json().data;
    assert.deepEqual(user, { id: user.id, email, displayName: "Mây" });
    assert.equal(expiresIn, 900);
    assert.ok(accessToken);
    assert.ok(refreshToken);
    assert.doesNotMatch(response.body, /passwordHash|refreshTokenHash|logoutTokenHash/);

    try {
      const stored = await database.user.findUniqueOrThrow({ where: { email }, include: { sessions: true } });
      assert.match(stored.passwordHash, /^\$2[aby]\$/);
      assert.equal(stored.sessions.length, 1);
      assert.equal(stored.sessions[0].refreshTokenHash, createHash("sha256").update(refreshToken).digest("hex"));

      const profile = await me(app, accessToken);
      assert.equal(profile.statusCode, 200);
      assert.deepEqual(profile.json(), { data: user });

      const duplicate = await post(app, "register", { email, displayName: "Another user", password });
      assert.equal(duplicate.statusCode, 409);
      assert.equal(duplicate.json().error.code, "EMAIL_ALREADY_REGISTERED");
    } catch (error) {
      await database.user.deleteMany({ where: { email } });
      throw error;
    }
  });

  try {
    await t.test("login rejects wrong credentials and issues an independent session", async () => {
      const wrong = await post(app, "login", { email, password: "wrong" });
      const unknown = await post(app, "login", { email: `another-${email}`, password });
      assert.equal(wrong.statusCode, 401);
      assert.equal(unknown.statusCode, 401);
      assert.deepEqual(wrong.json(), unknown.json());
      assert.equal(wrong.json().error.code, "INVALID_CREDENTIALS");

      const response = await post(app, "login", { email: ` ${email.toUpperCase()} `, password });
      assert.equal(response.statusCode, 200);
      assert.equal(response.json().data.user.email, email);
      assert.equal(await database.session.count({ where: { user: { email } } }), 2);
    });

    await t.test("only one concurrent refresh can consume the current token", async () => {
      const login = await post(app, "login", { email, password });
      assert.equal(login.statusCode, 200);
      const original = login.json().data;
      const payload = { refreshToken: original.refreshToken };
      const results = await Promise.all([post(app, "refresh", payload), post(app, "refresh", payload)]);
      assert.deepEqual(results.map((response) => response.statusCode).sort(), [200, 401]);
      assert.equal(results.find((response) => response.statusCode === 401)!.json().error.code, "REFRESH_TOKEN_INVALID");
      const rotated = results.find((response) => response.statusCode === 200)!.json().data;
      assert.notEqual(rotated.refreshToken, original.refreshToken);
      assert.notEqual(rotated.accessToken, original.accessToken);
      assert.equal(rotated.expiresIn, original.expiresIn);
      assert.equal((await me(app, rotated.accessToken)).statusCode, 200);

      const logout = await post(app, "logout", { refreshToken: original.refreshToken });
      assert.equal(logout.statusCode, 204);
      assert.equal(logout.body, "");
      assert.equal((await post(app, "logout", { refreshToken: rotated.refreshToken })).statusCode, 204);
      const afterLogout = await post(app, "refresh", { refreshToken: rotated.refreshToken });
      assert.equal(afterLogout.statusCode, 401);
      assert.equal(afterLogout.json().error.code, "REFRESH_TOKEN_INVALID");
      assert.equal((await me(app, rotated.accessToken)).statusCode, 401);
    });

    await t.test("logout wins eventual revocation when concurrent with rotation", async () => {
      const login = await post(app, "login", { email, password });
      assert.equal(login.statusCode, 200);
      const { refreshToken } = login.json().data;
      const [rotation, logout] = await Promise.all([
        post(app, "refresh", { refreshToken }),
        post(app, "logout", { refreshToken }),
      ]);
      assert.equal(logout.statusCode, 204);
      assert.ok([200, 401].includes(rotation.statusCode));
      const lastRefreshToken = rotation.statusCode === 200 ? rotation.json().data.refreshToken : refreshToken;
      assert.equal((await post(app, "refresh", { refreshToken: lastRefreshToken })).statusCode, 401);
      assert.equal(await database.session.count({ where: { user: { email }, revokedAt: null } }), 2);
    });

    await t.test("rejects tampered and expired access tokens and expired refresh tokens", async (subtest) => {
      const { app: shortLivedApp } = testApp(subtest, 2);
      const login = await post(shortLivedApp, "login", { email, password });
      assert.equal(login.statusCode, 200);
      const { accessToken, refreshToken, expiresIn } = login.json().data;
      assert.equal(expiresIn, 2);
      const [header, claims, signature] = accessToken.split(".");
      const tamperedSignature = `${signature[0] === "a" ? "b" : "a"}${signature.slice(1)}`;
      assert.equal((await me(shortLivedApp, `${header}.${claims}.${tamperedSignature}`)).statusCode, 401);

      const { exp } = JSON.parse(Buffer.from(claims, "base64url").toString());
      await new Promise((resolve) => setTimeout(resolve, Math.max(0, exp * 1000 - Date.now()) + 10));
      assert.equal((await me(shortLivedApp, accessToken)).statusCode, 401);

      await database.session.update({
        where: { refreshTokenHash: createHash("sha256").update(refreshToken).digest("hex") },
        data: { expiresAt: new Date(Date.now() - 10_000) },
      });
      const expired = await post(shortLivedApp, "refresh", { refreshToken });
      assert.equal(expired.statusCode, 401);
      assert.equal(expired.json().error.code, "REFRESH_TOKEN_INVALID");
    });
  } finally {
    await database.user.deleteMany({ where: { email } });
  }
});
