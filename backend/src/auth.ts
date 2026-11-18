import { createHash, randomBytes, randomUUID } from "node:crypto";
import { compare, hash } from "bcryptjs";
import { errors, jwtVerify, SignJWT } from "jose";
import { Prisma } from "./generated/prisma/client.js";
import type { PrismaClient } from "./generated/prisma/client.js";

const publicUserFields = { id: true, email: true, displayName: true } as const;
const sessionLifetimeMs = 30 * 24 * 60 * 60 * 1000;
const tokenPattern = /^([A-Za-z0-9_-]{43})\.[A-Za-z0-9_-]{43}$/;
const uuidPattern = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
const issuer = "sprintly";
const audience = "sprintly-api";

// Match bcrypt's normal work factor even when an email has no account.
const dummyPasswordHash = "$2b$12$d5PQwboCJ2PkDkMggZssN.if.P0Ez4tyiJgP6iFQLbvc06C21T2Gy";

export interface AuthOptions {
  database: PrismaClient;
  jwtSecret: string;
  accessTokenTtlSeconds: number;
}

export type PublicUser = Prisma.UserGetPayload<{ select: typeof publicUserFields }>;

type SessionIdentity = { id: string; userId: string };

export class AuthError extends Error {
  constructor(readonly statusCode: number, readonly code: string, message: string) {
    super(message);
  }
}

function invalidRefreshToken() {
  return new AuthError(401, "REFRESH_TOKEN_INVALID", "The refresh token is invalid or expired");
}

function unauthorized() {
  return new AuthError(401, "UNAUTHORIZED", "A valid access token is required");
}

function tokenHash(token: string) {
  return createHash("sha256").update(token).digest("hex");
}

function tokenSecret() {
  return randomBytes(32).toString("base64url");
}

function makeRefreshToken(logoutSecret = tokenSecret()) {
  const value = `${logoutSecret}.${tokenSecret()}`;
  return { value, refreshTokenHash: tokenHash(value), logoutTokenHash: tokenHash(logoutSecret) };
}

function logoutSecretFrom(refreshToken: string) {
  const match = tokenPattern.exec(refreshToken);
  if (!match) throw invalidRefreshToken();
  return match[1];
}

export function createAuth({ database, jwtSecret, accessTokenTtlSeconds }: AuthOptions) {
  const signingKey = Buffer.from(jwtSecret, "utf8");

  async function issueTokens({ id, userId }: SessionIdentity, refreshToken: string) {
    const issuedAt = Math.floor(Date.now() / 1000);
    const accessToken = await new SignJWT({ sid: id })
      .setProtectedHeader({ alg: "HS256", typ: "JWT" })
      .setIssuer(issuer)
      .setAudience(audience)
      .setSubject(userId)
      .setIssuedAt(issuedAt)
      .setExpirationTime(issuedAt + accessTokenTtlSeconds)
      .setJti(randomUUID())
      .sign(signingKey);
    return { accessToken, refreshToken, expiresIn: accessTokenTtlSeconds };
  }

  function newSession() {
    const { value, refreshTokenHash, logoutTokenHash } = makeRefreshToken();
    const expiresAt = new Date(Date.now() + sessionLifetimeMs);
    return { refreshToken: value, data: { refreshTokenHash, logoutTokenHash, expiresAt } };
  }

  async function register(email: string, displayName: string, password: string) {
    const passwordHash = await hash(password, 12);
    const { refreshToken, data } = newSession();
    try {
      const { sessions, ...user } = await database.user.create({
        data: { email, displayName, passwordHash, sessions: { create: data } },
        select: { ...publicUserFields, sessions: { select: { id: true, userId: true } } },
      });
      return { user, ...await issueTokens(sessions[0], refreshToken) };
    } catch (error) {
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === "P2002") {
        throw new AuthError(409, "EMAIL_ALREADY_REGISTERED", "This email is already registered");
      }
      throw error;
    }
  }

  async function login(email: string, password: string) {
    const account = await database.user.findUnique({
      where: { email },
      select: { ...publicUserFields, passwordHash: true },
    });
    const passwordMatches = await compare(password, account?.passwordHash ?? dummyPasswordHash);
    if (!account || !passwordMatches) {
      throw new AuthError(401, "INVALID_CREDENTIALS", "Email or password is incorrect");
    }

    const { id, email: accountEmail, displayName } = account;
    const { refreshToken, data } = newSession();
    const session = await database.session.create({ data: { ...data, userId: id } });
    return { user: { id, email: accountEmail, displayName }, ...await issueTokens(session, refreshToken) };
  }

  async function refresh(refreshToken: string) {
    const { value, refreshTokenHash } = makeRefreshToken(logoutSecretFrom(refreshToken));
    // PostgreSQL locks the row and rechecks the current hash and revocation predicate after a concurrent update.
    const [session] = await database.$queryRaw<SessionIdentity[]>`
      UPDATE "sessions"
      SET "refresh_token_hash" = ${refreshTokenHash}
      WHERE "refresh_token_hash" = ${tokenHash(refreshToken)}
        AND "revoked_at" IS NULL AND "expires_at" > clock_timestamp()
      RETURNING "id", "user_id" AS "userId"
    `;
    if (!session) throw invalidRefreshToken();
    return issueTokens(session, value);
  }

  async function logout(refreshToken: string) {
    // The stable secret authorizes revocation even when a concurrent refresh has changed the current token.
    const [session] = await database.$queryRaw<{ id: string }[]>`
      UPDATE "sessions"
      SET "revoked_at" = COALESCE("revoked_at", clock_timestamp())
      WHERE "logout_token_hash" = ${tokenHash(logoutSecretFrom(refreshToken))}
        AND ("revoked_at" IS NOT NULL OR "expires_at" > clock_timestamp())
      RETURNING "id"
    `;
    if (!session) throw invalidRefreshToken();
  }

  async function authenticate(authorization: string | undefined): Promise<PublicUser> {
    const token = /^Bearer +([^\s,]+)$/i.exec(authorization ?? "")?.[1];
    if (!token || token.length > 1024) throw unauthorized();

    const { payload } = await jwtVerify(token, signingKey, {
      algorithms: ["HS256"],
      typ: "JWT",
      issuer,
      audience,
      requiredClaims: ["sub", "sid", "exp"],
    }).catch((error: unknown) => {
      if (error instanceof errors.JOSEError) throw unauthorized();
      throw error;
    });
    const { sub, sid } = payload;
    if (!sub || !uuidPattern.test(sub) || typeof sid !== "string" || !uuidPattern.test(sid)) {
      throw unauthorized();
    }

    const session = await database.session.findUnique({
      where: { id: sid, userId: sub, revokedAt: null, expiresAt: { gt: new Date() } },
      select: { user: { select: publicUserFields } },
    });
    if (!session) throw unauthorized();
    return session.user;
  }

  return { register, login, refresh, logout, authenticate };
}
