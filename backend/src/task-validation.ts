import { hashRequest } from "./idempotency.js";
import { bodyObject, invalidBody, readDescription, readId } from "./validation.js";
import { TaskPriority } from "./generated/prisma/enums.js";

const priorities: readonly string[] = Object.values(TaskPriority);
const maxInteger = 2_147_483_647;
const maxTaskTitleLength = 150;

export function readTaskText(value: unknown, field: string, maxLength: number) {
  if (typeof value !== "string" || /[\u0000\p{Cs}]/u.test(value)) {
    throw invalidBody(`${field} must have 1 to ${maxLength} characters`);
  }
  const trimmed = value.trim();
  if (!trimmed || Array.from(trimmed).length > maxLength) {
    throw invalidBody(`${field} must have 1 to ${maxLength} characters`);
  }
  return trimmed;
}

function readPriority(value: unknown) {
  if (typeof value !== "string" || !priorities.includes(value)) throw invalidBody("priority must be LOW, MEDIUM, HIGH, or URGENT");
  return value as TaskPriority;
}

function readDueAt(value: unknown) {
  if (value === undefined || value === null) return value;
  const parts = typeof value === "string" && value.match(
    /^(\d{4})-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d+)?(?:Z|[+-](?:[01]\d|2[0-3]):[0-5]\d)$/,
  );
  if (!parts) throw invalidBody("dueAt must be an ISO-8601 timestamp with a time zone or null");
  const [year, month, day] = parts.slice(1, 4).map(Number);
  const parsed = new Date(parts[0]);
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  if (day > days[month - 1] || !Number.isFinite(parsed.getTime())) throw invalidBody("dueAt must be a valid date");
  return parsed;
}

function readAssignee(value: unknown) {
  return value === null || value === undefined ? value : readId(value, "assigneeId");
}

function readVersion(value: unknown, field: string) {
  if (typeof value !== "number" || !Number.isInteger(value) || value < 1 || value > maxInteger) {
    throw invalidBody(`${field} must be a positive integer`);
  }
  return value;
}

export function readIdempotency(key: unknown, payload: Record<string, unknown>, fields: readonly string[]) {
  if (Object.keys(payload).some((field) => !fields.includes(field))) throw invalidBody("Unsupported request field");
  return { idempotencyKey: readId(key, "Idempotency-Key"), requestHash: hashRequest(payload) };
}

const creationFields = ["columnId", "title", "description", "priority", "assigneeId", "dueAt"];

export function readNewTask(body: unknown, key: unknown) {
  const payload = bodyObject(body);
  return {
    input: {
      columnId: readId(payload.columnId, "columnId"),
      title: readTaskText(payload.title, "Title", maxTaskTitleLength),
      description: readDescription(payload.description),
      priority: readPriority(payload.priority),
      assigneeId: readAssignee(payload.assigneeId),
      dueAt: readDueAt(payload.dueAt),
    },
    idempotency: readIdempotency(key, payload, creationFields),
  };
}

export function readTaskPatch(body: unknown) {
  const payload = bodyObject(body);
  const changes = {
    columnId: payload.columnId === undefined ? undefined : readId(payload.columnId, "columnId"),
    title: payload.title === undefined ? undefined : readTaskText(payload.title, "Title", maxTaskTitleLength),
    description: readDescription(payload.description),
    priority: payload.priority === undefined ? undefined : readPriority(payload.priority),
    assigneeId: readAssignee(payload.assigneeId),
    dueAt: readDueAt(payload.dueAt),
  };
  if (Object.values(changes).every((value) => value === undefined)) throw invalidBody("Provide at least one task field to update");
  return {
    expectedVersion: readVersion(payload.expectedVersion, "expectedVersion"),
    expectedBoardVersion: payload.expectedBoardVersion === undefined ? undefined : readVersion(payload.expectedBoardVersion, "expectedBoardVersion"),
    ...changes,
  };
}

export function readMove(body: unknown) {
  const payload = bodyObject(body);
  const { targetIndex } = payload;
  if (typeof targetIndex !== "number" || !Number.isInteger(targetIndex)) throw invalidBody("targetIndex must be an integer");
  return {
    targetColumnId: readId(payload.targetColumnId, "targetColumnId"),
    targetIndex,
    expectedVersion: readVersion(payload.expectedVersion, "expectedVersion"),
    expectedBoardVersion: readVersion(payload.expectedBoardVersion, "expectedBoardVersion"),
  };
}

function readPageNumber(value: unknown, field: string, defaultValue: number, max: number) {
  if (value === undefined) return defaultValue;
  if (typeof value !== "string" || !/^[0-9]+$/.test(value)) throw invalidBody(`${field} must be a positive integer`);
  const number = Number(value);
  if (number < 1 || number > max) throw invalidBody(`${field} must be between 1 and ${max}`);
  return number;
}

export function readPagination(query: unknown) {
  const payload = bodyObject(query);
  return {
    page: readPageNumber(payload.page, "page", 1, maxInteger),
    pageSize: readPageNumber(payload.pageSize, "pageSize", 20, 100),
  };
}

export function readTaskFilters(query: unknown) {
  const payload = bodyObject(query);
  if (payload.q !== undefined && (typeof payload.q !== "string" || /[\u0000\p{Cs}]/u.test(payload.q))) {
    throw invalidBody("q must be text");
  }
  const q = payload.q?.trim();
  if (q && Array.from(q).length > maxTaskTitleLength) {
    throw invalidBody(`q must have at most ${maxTaskTitleLength} characters`);
  }
  return {
    ...readPagination(query),
    q,
    columnId: payload.columnId === undefined ? undefined : readId(payload.columnId, "columnId"),
    assigneeId: payload.assigneeId === undefined ? undefined : readId(payload.assigneeId, "assigneeId"),
    priority: payload.priority === undefined ? undefined : readPriority(payload.priority),
  };
}
