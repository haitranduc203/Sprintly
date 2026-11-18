import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { test } from "node:test";
import { data, error, taskFixture, withPostgres } from "./task-fixture.js";
import type { Task } from "./task-fixture.js";

test("task creation is scoped and idempotent, including concurrent replay and deletion", withPostgres, async (t) => {
  const { owner, member, outsider, members, request, createProject, database } = await taskFixture(t);
  const project = await createProject();
  const other = await createProject();
  const key = randomUUID();
  const payload = { title: "Draft 🚀", columnId: project.columns[0], priority: "HIGH", assigneeId: member.user.id };
  const post = (accessToken = member.accessToken, values = payload, idempotencyKey = key) =>
    request("POST", project.tasks, accessToken, values, idempotencyKey);
  error(await post(outsider.accessToken), 404, "NOT_FOUND");
  error(await post(member.accessToken, { ...payload, columnId: other.columns[0] }), 422, "INVALID_TARGET_COLUMN");
  error(await post(member.accessToken, { ...payload, assigneeId: outsider.user.id }), 422, "INVALID_ASSIGNEE");
  error(await post(member.accessToken, { ...payload, title: "😀".repeat(151) }), 400, "VALIDATION_ERROR");
  error(await request("POST", project.tasks, member.accessToken, payload), 400, "VALIDATION_ERROR");
  const [created, replay] = (await Promise.all([post(), post()])).map((response) => data<Task>(response, 201));
  assert.deepEqual(replay, created);
  assert.equal(created.createdBy, member.user.id);
  assert.deepEqual(created.assignee, member.user);
  assert.equal(created.version, 1);
  assert.equal(created.position, 0);
  assert.equal(created.boardVersion, 2);
  assert.equal(await database.taskActivity.count({ where: { taskId: created.id, action: "CREATED" } }), 1);
  assert.deepEqual(data(await post(member.accessToken, {
    priority: payload.priority, assigneeId: payload.assigneeId, title: payload.title, columnId: payload.columnId,
  }), 201), created);
  error(await post(member.accessToken, { ...payload, title: "Changed payload" }), 409, "IDEMPOTENCY_KEY_REUSED");
  error(await request("GET", "/tasks/" + created.id, outsider.accessToken), 404, "NOT_FOUND");
  const own = data<Task>(await post(owner.accessToken), 201);
  error(await request("DELETE", "/tasks/" + own.id, member.accessToken), 403, "FORBIDDEN");
  assert.equal((await request("DELETE", "/tasks/" + created.id, member.accessToken)).statusCode, 204);
  assert.equal((await project.board()).columns[0].tasks[0].position, 0);
  assert.equal(await database.taskActivity.count({ where: { taskId: created.id } }), 0);
  error(await post(), 404, "NOT_FOUND");
  error(await post(member.accessToken, { ...payload, title: "Changed payload" }), 409, "IDEMPOTENCY_KEY_REUSED");

  assert.equal((await request("DELETE", members + "/" + member.user.id, owner.accessToken)).statusCode, 204);
  error(await post(), 404, "NOT_FOUND");
  const unassigned = data<Task>(await request("GET", "/tasks/" + own.id, owner.accessToken));
  assert.equal(unassigned.assignee, null);
  assert.equal(unassigned.assigneeId, null);
  assert.equal(unassigned.version, 2);
  assert.equal(unassigned.boardVersion, 4);
  const audit = await database.taskActivity.findFirstOrThrow({ where: { taskId: own.id, action: "ASSIGNEE_CHANGED" } });
  assert.equal(audit.actorId, owner.user.id);
  assert.deepEqual(audit.payload, { fromAssigneeId: member.user.id, toAssigneeId: null });
  assert.equal((await request("DELETE", "/projects/" + project.id, owner.accessToken)).statusCode, 204);
  assert.equal(await database.idempotencyRequest.count({ where: { projectId: project.id } }), 0);
});

test("task moves check task and board versions and preserve contiguous order", withPostgres, async (t) => {
  const { owner, request, createProject } = await taskFixture(t);
  const project = await createProject();
  const other = await createProject();
  const [first, second, third] = await Promise.all(["First", "Second", "Third"].map((title) => project.create(title)));
  const last = await project.create("Other column", { columnId: project.columns[1] });
  const read = () => request("GET", "/tasks/" + first.id, owner.accessToken);
  const latest = data<Task>(await read());
  const move = (targetColumnId: string, targetIndex: number, expectedVersion = latest.version, expectedBoardVersion = latest.boardVersion) =>
    request("PATCH", "/tasks/" + first.id + "/move", owner.accessToken, { targetColumnId, targetIndex, expectedVersion, expectedBoardVersion });
  error(await move(project.columns[0], -1, 99), 409, "TASK_VERSION_CONFLICT");
  error(await move(project.columns[0], -1, 1, 1), 409, "BOARD_VERSION_CONFLICT");
  error(await move(project.columns[0], -1), 422, "INVALID_TARGET_INDEX");
  error(await move(project.columns[0], 3), 422, "INVALID_TARGET_INDEX");
  error(await move(other.columns[0], 0), 422, "INVALID_TARGET_COLUMN");

  const within = data<Task>(await move(project.columns[0], (latest.position + 1) % 3));
  assert.equal(within.version, 2);
  assert.equal(within.boardVersion, latest.boardVersion + 1);
  const crossing = data<Task>(await move(project.columns[1], 0, within.version, within.boardVersion));
  assert.equal(crossing.position, 0);
  assert.equal(crossing.version, 3);
  assert.equal(crossing.boardVersion, within.boardVersion + 1);
  const board = await project.board();
  assert.equal(board.boardVersion, crossing.boardVersion);
  assert.deepEqual(board.columns[1].tasks.map(({ id, position, version }) => ({ id, position, version })), [
    { id: first.id, position: 0, version: 3 }, { id: last.id, position: 1, version: 1 },
  ]);
  assert.deepEqual(board.columns[0].tasks.map((task) => task.position), [0, 1]);
  assert.deepEqual(new Set(board.columns[0].tasks.map((task) => task.id)), new Set([second.id, third.id]));
  assert.ok(board.columns[0].tasks.every((task) => task.version === 1));

  const edit = (values: object) => request("PATCH", "/tasks/" + first.id, owner.accessToken, {
    expectedVersion: crossing.version, ...values,
  });
  error(await edit({ columnId: project.columns[0] }), 400, "VALIDATION_ERROR");
  error(await edit({ columnId: project.columns[0], expectedBoardVersion: within.boardVersion }), 409, "BOARD_VERSION_CONFLICT");
  const patched = data<Task>(await edit({
    columnId: project.columns[0], expectedBoardVersion: crossing.boardVersion,
    title: "Updated", description: "Add details", dueAt: "2026-12-03T15:00:00+07:00", priority: "URGENT",
  }));
  assert.equal(patched.position, 2);
  assert.equal(patched.columnId, project.columns[0]);
  assert.equal(patched.priority, "URGENT");
  assert.equal(patched.title, "Updated");
  assert.equal(patched.dueAt, "2026-12-03T08:00:00.000Z");
  assert.equal(patched.version, crossing.version + 1);
  assert.equal(patched.boardVersion, crossing.boardVersion + 1);
  assert.deepEqual((await project.board()).columns[1].tasks.map((task) => task.position), [0]);

  const racingMoves = await Promise.all([second, third].map((task) => request(
    "PATCH", "/tasks/" + task.id + "/move", owner.accessToken,
    { targetColumnId: project.columns[2], targetIndex: 0, expectedVersion: 1, expectedBoardVersion: patched.boardVersion },
  )));
  assert.deepEqual(racingMoves.map((response) => response.statusCode).sort(), [200, 409]);
  error(racingMoves.find((response) => response.statusCode === 409)!, 409, "BOARD_VERSION_CONFLICT");
});

test("project task filters combine in scope and report stable pagination", withPostgres, async (t) => {
  const { owner, member, outsider, request, createProject } = await taskFixture(t);
  const project = await createProject();
  const matched = await project.create("Draft 100%", { priority: "HIGH", assigneeId: member.user.id });
  await project.create("Draft 100x", { priority: "HIGH", assigneeId: member.user.id });
  await project.create("Draft 100% two", { priority: "LOW", assigneeId: member.user.id });
  await project.create("Draft 100% three", { priority: "HIGH", assigneeId: member.user.id, columnId: project.columns[1] });
  await project.create("Draft 100% four", { priority: "HIGH" });
  const url = project.tasks + "?q=draft%20100%25&columnId=" + project.columns[0] + "&priority=HIGH&assigneeId=" + member.user.id;
  error(await request("GET", url, outsider.accessToken), 404, "NOT_FOUND");
  const response = await request("GET", url + "&page=1&pageSize=1", owner.accessToken);
  assert.deepEqual(data<Task[]>(response).map((task) => task.id), [matched.id]);
  assert.deepEqual(response.json().meta, { page: 1, pageSize: 1, total: 1 });
  assert.deepEqual(data(await request("GET", url + "&page=2&pageSize=1", member.accessToken)), []);
  error(await request("GET", project.tasks + "?page=0", owner.accessToken), 400, "VALIDATION_ERROR");
});
