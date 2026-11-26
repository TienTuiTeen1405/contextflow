import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, mkdir, writeFile, readFile, rm } from "node:fs/promises";
import path from "node:path";
import os from "node:os";
import { readEnv, setupLocal } from "../local-env.mjs";

async function workspace(t) {
  const root = await mkdtemp(path.join(os.tmpdir(), "contextflow-env-test-"));
  t.after(async () => {
    if (
      !path.basename(root).startsWith("contextflow-env-test-") ||
      path.dirname(root) !== os.tmpdir()
    )
      throw new Error("Refusing cleanup outside test directory");
    await rm(root, { recursive: true, force: true });
  });
  return root;
}
test("fresh clone gets shared local secrets without leaking them to Web/AI", async (t) => {
  const root = await workspace(t);
  await setupLocal(root);
  const infra = await readEnv(path.join(root, "infra/.env"));
  const core = await readEnv(path.join(root, "core-backend/.env"));
  const ai = await readEnv(path.join(root, "ai-service/.env"));
  const web = await readEnv(path.join(root, "web/.env.local"));
  assert.equal(core.CF_DB_PASSWORD, infra.POSTGRES_PASSWORD);
  assert.equal(ai.CF_INTERNAL_SERVICE_TOKEN, infra.CF_INTERNAL_SERVICE_TOKEN);
  assert.ok(infra.CF_INTERNAL_SERVICE_TOKEN.length >= 24);
  assert.deepEqual(Object.keys(ai).sort(), [
    "CF_INTERNAL_SERVICE_TOKEN",
    "CF_PROVIDER_MODE",
  ]);
  assert.deepEqual(web, { NEXT_PUBLIC_CORE_API_URL: "http://localhost:18080" });
  assert.equal(Buffer.from(core.CF_CREDENTIAL_KEK_V1, "base64").length, 32);
});
test("switching to IDE preserves DB credentials and custom settings", async (t) => {
  const root = await workspace(t);
  await mkdir(path.join(root, "infra"), { recursive: true });
  await writeFile(
    path.join(root, "infra/.env"),
    "POSTGRES_PASSWORD='existing secret # value'\nPOSTGRES_PORT=25432\nCF_INTERNAL_SERVICE_TOKEN=existing-service-token-of-32-characters\n",
  );
  await mkdir(path.join(root, "web"), { recursive: true });
  await writeFile(path.join(root, "web/.env.local"), "CUSTOM_SETTING=keep\n");
  await setupLocal(root, "ide");
  const infra = await readEnv(path.join(root, "infra/.env"));
  const core = await readEnv(path.join(root, "core-backend/.env"));
  assert.equal(core.CF_DB_PASSWORD, "existing secret # value");
  assert.equal(core.CF_DB_PASSWORD, infra.POSTGRES_PASSWORD);
  assert.match(
    await readFile(path.join(root, "core-backend/.env"), "utf8"),
    /CF_DB_PASSWORD=existing\\u0020secret\\u0020\\u0023\\u0020value/,
  );
  assert.equal(core.CF_DB_URL, "jdbc:postgresql://127.0.0.1:25432/contextflow");
  assert.equal(core.CF_AI_BASE_URL, "http://127.0.0.1:18000");
  assert.deepEqual(await readEnv(path.join(root, "web/.env.local")), {
    CUSTOM_SETTING: "keep",
    NEXT_PUBLIC_CORE_API_URL: "http://localhost:18080",
  });
  const kek = core.CF_CREDENTIAL_KEK_V1;
  await setupLocal(root, "docker");
  assert.equal(
    (await readEnv(path.join(root, "core-backend/.env"))).CF_CREDENTIAL_KEK_V1,
    kek,
  );
});
test("existing placeholder credentials are rejected instead of silently rotating a DB password", async (t) => {
  const root = await workspace(t);
  await mkdir(path.join(root, "infra"), { recursive: true });
  await writeFile(
    path.join(root, "infra/.env"),
    "POSTGRES_PASSWORD=replace-with-your-local-password\n",
  );
  await assert.rejects(setupLocal(root, "ide"), /never rotates/);
});

test("setup removes the legacy demo profile while preserving credentials and other profiles", async (t) => {
  const root = await workspace(t);
  await setupLocal(root, "ide");
  const previous = await readEnv(path.join(root, "core-backend/.env"));
  await writeFile(
    path.join(root, "core-backend/.env"),
    "SPRING_PROFILES_ACTIVE=dev,demo\nCF_CREDENTIAL_KEK_V1=" +
      previous.CF_CREDENTIAL_KEK_V1 +
      "\n",
  );
  await setupLocal(root, "docker");
  const core = await readEnv(path.join(root, "core-backend/.env"));
  assert.equal(core.SPRING_PROFILES_ACTIVE, "dev");
  assert.equal(core.CF_DB_PASSWORD, previous.CF_DB_PASSWORD);
  assert.equal(core.CF_CREDENTIAL_KEK_V1, previous.CF_CREDENTIAL_KEK_V1);
  assert.equal(
    (await readEnv(path.join(root, "web/.env.local"))).NEXT_PUBLIC_CORE_API_URL,
    "http://localhost:8080",
  );
});
