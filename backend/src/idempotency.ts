import { createHash } from "node:crypto";
import { ApiError } from "./api-errors.js";
import type { Prisma } from "./generated/prisma/client.js";
import { notFound } from "./workspace-access.js";
import type { Transaction } from "./workspace-access.js";

type Scope =
  | { operation: "CREATE_TASK"; projectId: string; taskId?: never }
  | { operation: "CREATE_COMMENT"; taskId: string; projectId?: never };

export type Idempotency = { idempotencyKey: string; requestHash: string };

export function hashRequest(payload: Record<string, unknown>) {
  // The supported creation payloads have only flat, validated fields.
  return createHash("sha256").update(JSON.stringify(payload, Object.keys(payload).sort())).digest("hex");
}

export async function createOnce<Result extends Prisma.InputJsonObject & { id: string }>(
  tx: Transaction,
  accountId: string,
  scope: Scope,
  { idempotencyKey, requestHash }: Idempotency,
  create: () => Promise<Result>,
) {
  // Callers hold the parent project's lock and have rechecked membership.
  const where = { accountId, ...scope, idempotencyKey };
  const previous = await tx.idempotencyRequest.findFirst({
    where, select: { requestHash: true, responseStatus: true, responseBody: true },
  });
  if (previous) {
    if (previous.requestHash !== requestHash) {
      throw new ApiError(409, "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key has already been used with a different request");
    }
    if (previous.responseBody === null) throw notFound("Task");
    return { status: previous.responseStatus, body: previous.responseBody };
  }

  const data = await create();
  const status = 201;
  const body = { data };
  await tx.idempotencyRequest.create({
    data: { ...where, requestHash, resultId: data.id, responseStatus: status, responseBody: body },
    select: { id: true },
  });
  return { status, body };
}
