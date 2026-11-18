import { ApiError } from "./api-errors.js";

const emailLocalPattern = /^[a-z0-9!#$%&'*+/=?^_`{|}~-]+(\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*$/;
const emailDomainPattern = /^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;
export const uuidPattern = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;

export function invalidBody(message: string) {
  return new ApiError(400, "VALIDATION_ERROR", message);
}

export function bodyObject(body: unknown) {
  if (typeof body !== "object" || body === null || Array.isArray(body)) {
    throw invalidBody("An application/json object is required");
  }
  return body as Record<string, unknown>;
}

export function stringField(body: unknown, field: string) {
  const value = bodyObject(body)[field];
  if (typeof value !== "string" || !value) throw invalidBody(`${field} is required`);
  return value;
}

export function readEmail(body: unknown) {
  const email = stringField(body, "email").trim().toLowerCase();
  const [local, domain, extra] = email.split("@");
  if (email.length > 254 || local.length > 64 || !domain || extra !== undefined ||
    !emailLocalPattern.test(local) || !emailDomainPattern.test(domain)) {
    throw invalidBody("Enter a valid email address");
  }
  return email;
}

export function readId(value: unknown, field: string) {
  if (typeof value !== "string" || !uuidPattern.test(value)) throw invalidBody(`${field} must be a UUID`);
  return value.toLowerCase();
}

export function readName(body: unknown) {
  const name = stringField(body, "name").trim();
  if (!name || Array.from(name).length > 100 || /[\p{Cc}\p{Cs}]/u.test(name)) {
    throw invalidBody("Name must have 1 to 100 characters");
  }
  return name;
}

function readDescription(value: unknown): string | null | undefined {
  if (value === undefined || value === null) return value;
  if (typeof value !== "string" || /[\u0000\p{Cs}]/u.test(value)) {
    throw invalidBody("Description must be text or null");
  }
  return value;
}

export function readNewResource(body: unknown) {
  return { name: readName(body), description: readDescription(bodyObject(body).description) };
}

export function readResourcePatch(body: unknown) {
  const details = bodyObject(body);
  const name = details.name === undefined ? undefined : readName(details);
  const description = readDescription(details.description);
  if (name === undefined && description === undefined) throw invalidBody("Provide a name or description to update");
  return { name, description };
}
