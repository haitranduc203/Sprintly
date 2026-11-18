import { hash } from "bcryptjs";
import { readDatabaseUrl } from "../src/config.js";
import { createPrismaClient } from "../src/database.js";

const demoWorkspaceId = "24e4a192-955c-4708-89e7-d03ee38cff6e";
const demoAccounts = [
  {
    email: "owner@sprintly.demo",
    displayName: "Demo Owner",
    passwordVariable: "DEMO_OWNER_PASSWORD",
    role: "OWNER",
  },
  {
    email: "member@sprintly.demo",
    displayName: "Demo Member",
    passwordVariable: "DEMO_MEMBER_PASSWORD",
    role: "MEMBER",
  },
] as const;

function readDemoPassword(name: string): string {
  const password = process.env[name];
  if (!password || Array.from(password).length < 8 || Buffer.byteLength(password, "utf8") > 72) {
    throw new Error(`Set ${name} to a password with at least 8 characters and at most 72 UTF-8 bytes.`);
  }
  return password;
}

async function seedDemo() {
  if (process.env.NODE_ENV === "production") {
    throw new Error("Run the demo seed in a development or test environment.");
  }

  const databaseUrl = readDatabaseUrl();
  const [owner, member] = await Promise.all(demoAccounts.map(async (account) => ({
    email: account.email,
    displayName: account.displayName,
    passwordHash: await hash(readDemoPassword(account.passwordVariable), 12),
    role: account.role,
  })));
  const database = createPrismaClient(databaseUrl);

  try {
    await database.$transaction(async (tx) => {
      const users = [];
      for (const { role, ...account } of [owner, member]) {
        const user = await tx.user.upsert({
          where: { email: account.email },
          create: account,
          update: {},
        });
        users.push({ id: user.id, role });
      }

      const workspace = await tx.workspace.upsert({
        where: { id: demoWorkspaceId },
        create: { id: demoWorkspaceId, name: "Sprintly Demo", createdBy: users[0].id },
        update: {},
      });

      for (const { id: userId, role } of users) {
        const key = { workspaceId: workspace.id, userId };
        await tx.workspaceMember.upsert({
          where: { workspaceId_userId: key },
          create: { ...key, role },
          update: { role },
        });
      }
    });
  } catch {
    throw new Error("Could not seed demo data. Check PostgreSQL connectivity and run npm run db:migrate.");
  } finally {
    await database.$disconnect();
  }

  console.log("Demo accounts and workspace seeded.");
}

try {
  await seedDemo();
} catch (error) {
  console.error(error instanceof Error ? error.message : "Could not seed demo data.");
  process.exitCode = 1;
}
