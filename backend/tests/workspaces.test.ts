import assert from "node:assert/strict";
import { randomBytes, randomUUID } from "node:crypto";
import { test } from "node:test";
import type { TestContext } from "node:test";
import type { HTTPMethods } from "fastify";
import { buildApp } from "../src/app.js";
import { createPrismaClient } from "../src/database.js";

const databaseUrl = process.env.TEST_DATABASE_URL;
const withPostgres = {
  skip: databaseUrl ? false : "set TEST_DATABASE_URL to a migrated test database",
  timeout: 30_000,
};

function testApp(t: TestContext) {
  const database = createPrismaClient(databaseUrl ?? "postgresql://unused@127.0.0.1:1/unused");
  const app = buildApp({
    checkDatabase: async () => { await database.$queryRaw`SELECT 1`; },
    auth: { database, accessTokenTtlSeconds: 900, jwtSecret: randomBytes(32).toString("hex") },
  });
  const emails: string[] = [];
  t.after(async () => {
    try {
      await app.close();
      if (emails.length) {
        await database.workspace.deleteMany({ where: { creator: { email: { in: emails } } } });
        await database.user.deleteMany({ where: { email: { in: emails } } });
      }
    } finally {
      await database.$disconnect();
    }
  });

  function request(method: HTTPMethods, path: string, token?: string, payload?: object) {
    return app.inject({ method, url: `/api/v1${path}`, headers: token ? { authorization: `Bearer ${token}` } : {}, payload });
  }

  async function account(displayName: string) {
    const email = `${randomUUID()}@example.test`;
    emails.push(email);
    const response = await request("POST", "/auth/register", undefined, {
      email, displayName, password: "test password 2026",
    });
    assert.equal(response.statusCode, 201);
    const { user, accessToken } = response.json<{ data: { user: User; accessToken: string } }>().data;
    return { user, accessToken };
  }

  return { app, database, request, account };
}

type User = { id: string; displayName: string; email: string };
type Workspace = { id: string; name: string; description: string | null; role: "OWNER" | "MEMBER" };
type Project = { id: string; workspaceId: string; name: string; description: string | null; boardVersion: number };
type Column = { id: string; name: string; position: number; tasks: [] };
type Board = { boardVersion: number; columns: Column[] };
type Response = Awaited<ReturnType<ReturnType<typeof testApp>["request"]>>;

function data<T>(response: Response, status = 200): T {
  assert.equal(response.statusCode, status, response.body);
  return response.json<{ data: T }>().data;
}

function error(response: Response, status: number, code: string) {
  assert.equal(response.statusCode, status, response.body);
  assert.equal(response.json().error.code, code);
}

test("workspace endpoints reuse bearer authentication", async (t) => {
  const { request } = testApp(t);
  const response = await request("GET", "/workspaces", "invalid");
  error(response, 401, "UNAUTHORIZED");
  assert.equal(response.headers["www-authenticate"], "Bearer");
  assert.equal(response.headers["cache-control"], "no-store");
});

test("workspace visibility, exact directory lookup, and membership management follow workspace roles", withPostgres, async (t) => {
  const { account, request } = testApp(t);
  const { user: owner, accessToken: ownerToken } = await account("Owner");
  const { user: member, accessToken: memberToken } = await account("Member");
  const { user: outsider, accessToken: outsiderToken } = await account("Outsider");

  const workspace = data<Workspace>(await request("POST", "/workspaces", ownerToken, {
    name: "  Product Team  ", description: "Shipping the app",
  }), 201);
  assert.deepEqual(workspace, { id: workspace.id, name: "Product Team", description: "Shipping the app", role: "OWNER" });
  const path = `/workspaces/${workspace.id}`;
  const directory = (email: string) => `${path}/users/search?email=${encodeURIComponent(email)}`;
  const membership = (userId: string) => ({ userId, role: "MEMBER" });

  assert.deepEqual(data(await request("GET", "/workspaces", ownerToken)), [workspace]);
  assert.deepEqual(data(await request("GET", `${path}/members`, ownerToken)), [{ ...owner, role: "OWNER" }]);
  assert.deepEqual(data(await request("GET", "/workspaces", memberToken)), []);
  error(await request("GET", path, outsiderToken), 404, "NOT_FOUND");
  error(await request("GET", directory(member.email), outsiderToken), 404, "NOT_FOUND");
  assert.deepEqual(data(await request("GET", directory(` ${member.email.toUpperCase()} `), ownerToken)), member);
  assert.equal(data(await request("GET", directory(`${member.email}.invalid`), ownerToken)), null);
  error(await request("GET", directory("member"), ownerToken), 400, "VALIDATION_ERROR");
  error(await request("POST", `${path}/members`, ownerToken, { userId: member.id, role: "OWNER" }), 400, "VALIDATION_ERROR");
  error(await request("POST", `${path}/members`, ownerToken, membership(randomUUID())), 404, "NOT_FOUND");

  const duplicate = await Promise.all([
    request("POST", `${path}/members`, ownerToken, membership(member.id)),
    request("POST", `${path}/members`, ownerToken, membership(member.id)),
  ]);
  assert.deepEqual(duplicate.map((response) => response.statusCode).sort(), [201, 409]);
  assert.deepEqual(data(duplicate.find((response) => response.statusCode === 201)!, 201), { ...member, role: "MEMBER" });
  error(duplicate.find((response) => response.statusCode === 409)!, 409, "MEMBER_ALREADY_EXISTS");
  assert.deepEqual(data(await request("GET", "/workspaces", memberToken)), [{ ...workspace, role: "MEMBER" }]);
  assert.deepEqual(data(await request("GET", path, memberToken)), { ...workspace, role: "MEMBER" });
  const members = data<(User & { role: string })[]>(await request("GET", `${path}/members`, memberToken));
  assert.deepEqual(new Set(members), new Set([{ ...owner, role: "OWNER" }, { ...member, role: "MEMBER" }]));

  error(await request("GET", directory(outsider.email), memberToken), 403, "FORBIDDEN");
  error(await request("POST", `${path}/members`, memberToken, membership(outsider.id)), 403, "FORBIDDEN");
  error(await request("DELETE", `${path}/members/${owner.id}`, memberToken), 403, "FORBIDDEN");
  error(await request("PATCH", path, memberToken, { name: "Denied" }), 403, "FORBIDDEN");
  error(await request("DELETE", path, memberToken), 403, "FORBIDDEN");
  error(await request("DELETE", `${path}/members/${owner.id}`, ownerToken), 409, "LAST_WORKSPACE_OWNER");

  assert.deepEqual(data(await request("PATCH", path, ownerToken, { name: "Renamed" })), { ...workspace, name: "Renamed" });
  assert.deepEqual(data(await request("PATCH", path, ownerToken, { description: null })), {
    ...workspace, name: "Renamed", description: null,
  });
  const removed = await request("DELETE", `${path}/members/${member.id}`, ownerToken);
  assert.equal(removed.statusCode, 204);
  assert.equal(removed.body, "");
  error(await request("GET", path, memberToken), 404, "NOT_FOUND");
  assert.deepEqual(data(await request("GET", "/workspaces", memberToken)), []);
  assert.deepEqual(data(await request("GET", "/users/me", memberToken)), member);
  assert.deepEqual(data(await request("GET", directory(member.email), ownerToken)), member);
  assert.equal((await request("DELETE", path, ownerToken)).statusCode, 204);
  error(await request("GET", path, ownerToken), 404, "NOT_FOUND");
});

test("concurrent owner removals preserve an owner and enforce current membership", withPostgres, async (t) => {
  const { account, database, request } = testApp(t);
  const first = await account("First owner");
  const second = await account("Second owner");
  const workspace = data<Workspace>(await request("POST", "/workspaces", first.accessToken, { name: "Shared" }), 201);
  await database.workspaceMember.create({
    data: { workspaceId: workspace.id, userId: second.user.id, role: "OWNER" },
  });
  const members = `/workspaces/${workspace.id}/members`;
  const responses = await Promise.all([
    request("DELETE", `${members}/${second.user.id}`, first.accessToken),
    request("DELETE", `${members}/${first.user.id}`, second.accessToken),
  ]);
  assert.deepEqual(responses.map((response) => response.statusCode).sort(), [204, 404]);
  const remaining = responses[0].statusCode === 204 ? first : second;
  assert.deepEqual(data(await request("GET", members, remaining.accessToken)), [{ ...remaining.user, role: "OWNER" }]);
  error(await request("DELETE", `${members}/${remaining.user.id}`, remaining.accessToken), 409, "LAST_WORKSPACE_OWNER");
});

test("project boards store default and appended columns, enforce project scope, and cascade on delete", withPostgres, async (t) => {
  const { account, database, request } = testApp(t);
  const owner = await account("Project owner");
  const member = await account("Project collaborator");
  const workspace = data<Workspace>(await request("POST", "/workspaces", owner.accessToken, { name: "Team" }), 201);
  const otherWorkspace = data<Workspace>(await request("POST", "/workspaces", member.accessToken, { name: "Personal" }), 201);
  const projects = `/workspaces/${workspace.id}/projects`;
  error(await request("POST", projects, member.accessToken, { name: "Denied" }), 404, "NOT_FOUND");
  data(await request("POST", `/workspaces/${workspace.id}/members`, owner.accessToken, { userId: member.user.id, role: "MEMBER" }), 201);
  assert.deepEqual(data(await request("GET", projects, member.accessToken)), []);

  const project = data<Project>(await request("POST", projects, member.accessToken, { name: "Release", description: "QA" }), 201);
  assert.deepEqual(project, {
    id: project.id, workspaceId: workspace.id, name: "Release", description: "QA", boardVersion: 1,
  });
  const path = `/projects/${project.id}`;
  assert.deepEqual(data(await request("GET", projects, owner.accessToken)), [project]);
  assert.deepEqual(data(await request("GET", path, owner.accessToken)), project);
  assert.deepEqual(data(await request("GET", `/workspaces/${otherWorkspace.id}/projects`, member.accessToken)), []);
  assert.deepEqual(data(await request("PATCH", path, member.accessToken, { name: "Next release", description: null })), {
    ...project, name: "Next release", description: null,
  });
  error(await request("DELETE", path, member.accessToken), 403, "FORBIDDEN");

  const initial = data<Board>(await request("GET", `${path}/board`, owner.accessToken));
  assert.equal(initial.boardVersion, 1);
  assert.deepEqual(initial.columns.map(({ name, position, tasks }) => ({ name, position, tasks })), [
    { name: "To Do", position: 0, tasks: [] },
    { name: "In Progress", position: 1, tasks: [] },
    { name: "Done", position: 2, tasks: [] },
  ]);
  assert.equal(new Set(initial.columns.map(({ id }) => id)).size, 3);

  const column = data<Column>(await request("POST", `${path}/columns`, member.accessToken, { name: "  QA  " }), 201);
  assert.deepEqual(column, { id: column.id, name: "QA", position: 3, tasks: [] });
  const renamed = data<Column>(await request("PATCH", `/columns/${column.id}`, member.accessToken, { name: "Review" }));
  assert.deepEqual(renamed, { ...column, name: "Review" });
  const board = data<Board>(await request("GET", `${path}/board`, member.accessToken));
  assert.deepEqual(board, { boardVersion: 2, columns: [...initial.columns, renamed] });

  const additions = await Promise.all(["Staging", "Verify", "Approved", "Published"].map(async (name) =>
    data<Column>(await request("POST", `${path}/columns`, owner.accessToken, { name }), 201)
  ));
  const expected = [...board.columns, ...additions.sort((left, right) => left.position - right.position)];
  assert.deepEqual(expected.map(({ position }) => position), [0, 1, 2, 3, 4, 5, 6, 7]);
  assert.deepEqual(data(await request("GET", `${path}/board`, member.accessToken)), { boardVersion: 6, columns: expected });

  assert.equal((await request("DELETE", `/workspaces/${workspace.id}/members/${member.user.id}`, owner.accessToken)).statusCode, 204);
  error(await request("GET", path, member.accessToken), 404, "NOT_FOUND");
  error(await request("GET", `${path}/board`, member.accessToken), 404, "NOT_FOUND");
  error(await request("PATCH", `/columns/${column.id}`, member.accessToken, { name: "Denied" }), 404, "NOT_FOUND");
  assert.equal((await request("DELETE", path, owner.accessToken)).statusCode, 204);
  assert.equal((await database.$queryRaw<{ count: number }[]>`SELECT COUNT(*)::int AS count FROM board_columns WHERE project_id = ${project.id}::uuid`)[0].count, 0);
  assert.deepEqual(data(await request("GET", projects, owner.accessToken)), []);

  const another = data<Project>(await request("POST", projects, owner.accessToken, { name: "Another" }), 201);
  assert.equal((await request("DELETE", `/workspaces/${workspace.id}`, owner.accessToken)).statusCode, 204);
  error(await request("GET", `/projects/${another.id}`, owner.accessToken), 404, "NOT_FOUND");
  assert.equal((await database.$queryRaw<{ count: number }[]>`SELECT COUNT(*)::int AS count FROM projects WHERE id = ${another.id}::uuid`)[0].count, 0);
});

test("invalid workspace inputs return sanitized validation errors", withPostgres, async (t) => {
  const { app, account, request } = testApp(t);
  const { accessToken } = await account("Validation");
  for (const payload of [{ name: "   " }, { name: "😀".repeat(101) }, { name: "Valid", description: 1 }]) {
    error(await request("POST", "/workspaces", accessToken, payload), 400, "VALIDATION_ERROR");
  }
  error(await request("GET", "/workspaces/invalid-uuid", accessToken), 400, "VALIDATION_ERROR");
  const malformed = await app.inject({
    method: "POST", url: "/api/v1/workspaces", headers: { authorization: `Bearer ${accessToken}`, "content-type": "application/json" },
    payload: '{"name":"sensitive-example"',
  });
  error(malformed, 400, "VALIDATION_ERROR");
  assert.doesNotMatch(malformed.body, /sensitive-example|SyntaxError/);
  const workspace = data<Workspace>(await request("POST", "/workspaces", accessToken, { name: "😀".repeat(100) }), 201);
  error(await request("PATCH", `/workspaces/${workspace.id}`, accessToken, {}), 400, "VALIDATION_ERROR");
  error(await request("PATCH", `/workspaces/${workspace.id}`, accessToken, { description: "invalid\u0000text" }), 400, "VALIDATION_ERROR");
});
