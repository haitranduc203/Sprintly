import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { test } from "node:test";
import { data, error, taskFixture, withPostgres } from "./task-fixture.js";
import type { Task } from "./task-fixture.js";

type Person = { id: string; displayName: string; email: string };
type Comment = { id: string; author: Person; body: string; createdAt: string };
type Activity = { id: string; actor: Person; action: string; payload: object; createdAt: string };

function chronological(left: Comment | Activity, right: Comment | Activity) {
  return left.createdAt.localeCompare(right.createdAt) || left.id.localeCompare(right.id);
}

test("comments replay by task and account, page in chronological order, and preserve authors after removal", withPostgres, async (t) => {
  const { owner, member, outsider, members, request, createProject, database } = await taskFixture(t);
  const project = await createProject();
  const task = await project.create("Discuss the release");
  const other = await project.create("Another discussion");
  const taskPath = "/tasks/" + task.id;
  const path = taskPath + "/comments";
  const key = randomUUID();
  const payload = { body: "  Confirmed 🚀  " };
  const post = (accessToken = member.accessToken, values = payload, idempotencyKey: string = key) =>
    request("POST", path, accessToken, values, idempotencyKey);

  error(await request("GET", path), 401, "UNAUTHORIZED");
  error(await post(outsider.accessToken), 404, "NOT_FOUND");
  error(await request("GET", path, outsider.accessToken), 404, "NOT_FOUND");
  error(await request("POST", path, member.accessToken, payload), 400, "VALIDATION_ERROR");
  error(await post(member.accessToken, { body: "   " }), 400, "VALIDATION_ERROR");
  error(await post(member.accessToken, { body: "😀".repeat(2001) }), 400, "VALIDATION_ERROR");
  error(await post(member.accessToken, payload, "invalid"), 400, "VALIDATION_ERROR");

  const [comment, replay] = (await Promise.all([post(), post()])).map((response) => data<Comment>(response, 201));
  assert.deepEqual(replay, comment);
  assert.equal(comment.body, "Confirmed 🚀");
  assert.deepEqual(comment.author, member.user);
  error(await post(member.accessToken, { body: "Changed text" }), 409, "IDEMPOTENCY_KEY_REUSED");

  const ownerComment = data<Comment>(await post(owner.accessToken), 201);
  assert.notEqual(ownerComment.id, comment.id);
  const another = data<Comment>(
    await request("POST", "/tasks/" + other.id + "/comments", member.accessToken, payload, key), 201,
  );
  assert.notEqual(another.id, comment.id);
  const chronologicalComments = [comment, ownerComment].sort(chronological);
  const response = await request("GET", path + "?page=2&pageSize=1", owner.accessToken);
  assert.deepEqual(data<Comment[]>(response), [chronologicalComments[1]]);
  assert.deepEqual(response.json().meta, { page: 2, pageSize: 1, total: 2 });
  error(await request("GET", path + "?pageSize=101", owner.accessToken), 400, "VALIDATION_ERROR");
  const after = data<Task>(await request("GET", taskPath, owner.accessToken));
  assert.equal(after.version, task.version);
  assert.equal(after.boardVersion, (await project.board()).boardVersion);

  assert.equal((await request("DELETE", members + "/" + member.user.id, owner.accessToken)).statusCode, 204);
  error(await post(), 404, "NOT_FOUND");
  error(await request("GET", path, member.accessToken), 404, "NOT_FOUND");
  assert.deepEqual(data(await request("GET", path, owner.accessToken)), chronologicalComments);

  assert.equal((await request("DELETE", taskPath, owner.accessToken)).statusCode, 204);
  error(await post(owner.accessToken), 404, "NOT_FOUND");
  error(await request("GET", path, owner.accessToken), 404, "NOT_FOUND");
  assert.equal(await database.comment.count({ where: { taskId: task.id } }), 0);
  assert.equal(await database.idempotencyRequest.count({ where: { taskId: task.id } }), 0);
  assert.equal(await database.comment.count({ where: { taskId: other.id } }), 1);
});

test("activities expose server-recorded actors and changes with newest-first pagination", withPostgres, async (t) => {
  const { owner, member, outsider, members, request, createProject, database } = await taskFixture(t);
  const project = await createProject();
  const task = await project.create("Original", { assigneeId: member.user.id }, member.accessToken);
  const taskPath = "/tasks/" + task.id;
  const path = taskPath + "/activities";
  error(await request("GET", path, outsider.accessToken), 404, "NOT_FOUND");

  const edited = data<Task>(await request("PATCH", taskPath, member.accessToken, { title: "Edited", expectedVersion: task.version }));
  error(await request("PATCH", taskPath, member.accessToken, { title: "Stale edit", expectedVersion: task.version }), 409, "TASK_VERSION_CONFLICT");
  const moved = data<Task>(await request("PATCH", taskPath + "/move", owner.accessToken, {
    targetColumnId: project.columns[1], targetIndex: 0, expectedVersion: edited.version, expectedBoardVersion: edited.boardVersion,
  }));
  assert.equal((await request("DELETE", members + "/" + member.user.id, owner.accessToken)).statusCode, 204);
  error(await request("GET", path, member.accessToken), 404, "NOT_FOUND");

  const response = await request("GET", path + "?page=1&pageSize=2", owner.accessToken);
  assert.deepEqual(response.json().meta, { page: 1, pageSize: 2, total: 4 });
  const pages = [
    ...data<Activity[]>(response),
    ...data<Activity[]>(await request("GET", path + "?page=2&pageSize=2", owner.accessToken)),
  ];
  assert.deepEqual(pages, [...pages].sort((left, right) => chronological(right, left)));
  const events = Object.fromEntries(pages.map((event) => [event.action, event]));
  assert.deepEqual(Object.keys(events).sort(), ["ASSIGNEE_CHANGED", "CREATED", "MOVED", "UPDATED"]);
  assert.deepEqual(events.ASSIGNEE_CHANGED.actor, owner.user);
  assert.deepEqual(events.MOVED.actor, owner.user);
  assert.deepEqual(events.MOVED.payload, {
    fromColumnId: task.columnId, toColumnId: moved.columnId, fromPosition: task.position, toPosition: moved.position,
  });
  assert.deepEqual(events.UPDATED.actor, member.user);
  assert.deepEqual(events.CREATED.actor, member.user);
  assert.deepEqual(events.UPDATED.payload, { changes: { title: { from: "Original", to: "Edited" } } });
  assert.equal((await request("DELETE", "/projects/" + project.id, owner.accessToken)).statusCode, 204);
  assert.equal(await database.taskActivity.count({ where: { taskId: task.id } }), 0);
  error(await request("GET", path, owner.accessToken), 404, "NOT_FOUND");
});
