import assert from "node:assert/strict";
import { randomBytes, randomUUID } from "node:crypto";
import type { HTTPMethods, LightMyRequestResponse } from "fastify";
import type { TestContext } from "node:test";
import { buildApp } from "../src/app.js";
import { createPrismaClient } from "../src/database.js";

const databaseUrl = process.env.TEST_DATABASE_URL;
export const withPostgres = { skip: databaseUrl ? false : "set TEST_DATABASE_URL to a migrated test database", timeout: 30_000 };

type User = { id: string; displayName: string; email: string };
type Board = { boardVersion: number; columns: { id: string; tasks: Task[] }[] };
export type Task = {
  id: string;
  projectId: string;
  columnId: string;
  title: string;
  description: string | null;
  priority: string;
  assigneeId: string | null;
  assignee: User | null;
  dueAt: string | null;
  position: number;
  version: number;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  boardVersion: number;
};

export async function taskFixture(t: TestContext) {
  const database = createPrismaClient(databaseUrl!);
  const app = buildApp({
    checkDatabase: async () => {},
    auth: { database, accessTokenTtlSeconds: 900, jwtSecret: randomBytes(32).toString("hex") },
  });
  const emails: string[] = [];
  t.after(async () => {
    try {
      await app.close();
      await database.workspace.deleteMany({ where: { creator: { email: { in: emails } } } });
      await database.user.deleteMany({ where: { email: { in: emails } } });
    } finally {
      await database.$disconnect();
    }
  });

  function request(method: HTTPMethods, path: string, accessToken?: string, payload?: object, key?: string) {
    return app.inject({
      method, url: "/api/v1" + path, payload,
      headers: {
        ...(accessToken && { authorization: "Bearer " + accessToken }),
        ...(key && { "Idempotency-Key": key }),
      },
    });
  }
  async function account(displayName: string) {
    const email = randomUUID() + "@example.test";
    emails.push(email);
    return data<{ user: User; accessToken: string }>(await request("POST", "/auth/register", undefined, {
      email, displayName, password: "test password 2026",
    }), 201);
  }
  const owner = await account("Owner");
  const member = await account("Member");
  const outsider = await account("Outsider");
  const { id: workspaceId } = data<{ id: string }>(
    await request("POST", "/workspaces", owner.accessToken, { name: "Team" }), 201,
  );
  const workspace = "/workspaces/" + workspaceId;
  const members = workspace + "/members";
  data(await request("POST", members, owner.accessToken, { userId: member.user.id, role: "MEMBER" }), 201);
  async function createProject() {
    const { id } = data<{ id: string }>(await request("POST", workspace + "/projects", owner.accessToken, { name: "Task board" }), 201);
    const project = "/projects/" + id;
    const board = async () => data<Board>(await request("GET", project + "/board", owner.accessToken));
    const columns = (await board()).columns.map((column) => column.id);
    const tasks = project + "/tasks";
    const create = async (title: string, options: object = {}, accessToken = owner.accessToken) =>
      data<Task>(await request("POST", tasks, accessToken, { title, columnId: columns[0], priority: "MEDIUM", ...options }, randomUUID()), 201);
    return { id, tasks, columns, board, create };
  }
  return { database, owner, member, outsider, members, request, createProject };
}

export function data<T>(response: LightMyRequestResponse, status = 200): T {
  assert.equal(response.statusCode, status, response.body);
  return response.json<{ data: T }>().data;
}

export function error(response: LightMyRequestResponse, status: number, code: string) {
  assert.equal(response.statusCode, status, response.body);
  assert.equal(response.json().error.code, code);
}
