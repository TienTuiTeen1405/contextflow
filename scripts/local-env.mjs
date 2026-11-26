import { readFile, writeFile, mkdir } from "node:fs/promises";
import { randomBytes } from "node:crypto";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const ROOT = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
export async function readEnv(file) {
  let text;
  try {
    text = await readFile(file, "utf8");
  } catch (error) {
    if (error.code === "ENOENT") return {};
    throw error;
  }
  const result = {};
  for (const line of text.split(/\r?\n/)) {
    if (!line.trim() || line.trimStart().startsWith("#")) continue;
    const match = line.match(/^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$/);
    if (!match)
      throw new Error("Invalid environment line in " + path.basename(file));
    let value = match[2].trim();
    if (value.startsWith('"')) value = JSON.parse(value);
    else if (value.startsWith("'") && value.endsWith("'"))
      value = value.slice(1, -1);
    else value = value.replace(/\s+#.*$/, "").trimEnd();
    result[match[1]] = value;
  }
  return result;
}
async function writeEnv(file, values) {
  await mkdir(path.dirname(file), { recursive: true });
  const content =
    "# Generated local configuration. Never commit this file.\n" +
    Object.entries(values)
      .map(
        ([key, value]) =>
          key +
          "=" +
          (/^[A-Za-z0-9_./:,@=+\-]+$/.test(value)
            ? value
            : JSON.stringify(value)),
      )
      .join("\n") +
    "\n";
  await writeFile(file, content, { mode: 0o600 });
}
const placeholder = (s) => !s || s.startsWith("replace-with-");
export async function setupLocal(root, mode = "docker") {
  if (!["docker", "ide"].includes(mode))
    throw new Error("Mode must be docker or ide");
  const infraFile = path.join(root, "infra/.env");
  let infra = await readEnv(infraFile);
  if (Object.keys(infra).length === 0) {
    infra = {
      POSTGRES_DB: "contextflow",
      POSTGRES_USER: "contextflow",
      POSTGRES_PASSWORD: randomBytes(24).toString("hex"),
      POSTGRES_PORT: "15432",
      CF_INTERNAL_SERVICE_TOKEN: randomBytes(32).toString("hex"),
      CF_WEB_ALLOWED_ORIGINS: "http://localhost:3000,http://127.0.0.1:3000",
    };
    await writeEnv(infraFile, infra);
  }
  if (
    placeholder(infra.POSTGRES_PASSWORD) ||
    placeholder(infra.CF_INTERNAL_SERVICE_TOKEN) ||
    infra.CF_INTERNAL_SERVICE_TOKEN.length < 24
  ) {
    throw new Error(
      "Set existing infra/.env credentials to match your DB; setup never rotates them.",
    );
  }
  const port = Number(infra.POSTGRES_PORT ?? "15432");
  if (!Number.isInteger(port) || port < 1 || port > 65535)
    throw new Error("Invalid POSTGRES_PORT");
  const coreFile = path.join(root, "core-backend/.env");
  const core = await readEnv(coreFile);
  const kek = placeholder(core.CF_CREDENTIAL_KEK_V1)
    ? randomBytes(32).toString("base64")
    : core.CF_CREDENTIAL_KEK_V1;
  await writeEnv(coreFile, {
    ...core,
    CF_DB_URL:
      "jdbc:postgresql://127.0.0.1:" +
      port +
      "/" +
      encodeURIComponent(infra.POSTGRES_DB ?? "contextflow"),
    CF_DB_USERNAME: infra.POSTGRES_USER ?? "contextflow",
    CF_DB_PASSWORD: infra.POSTGRES_PASSWORD,
    CF_CORE_PORT: "18080",
    CF_AI_BASE_URL: "http://127.0.0.1:18000",
    CF_WEB_ALLOWED_ORIGINS:
      infra.CF_WEB_ALLOWED_ORIGINS ??
      "http://localhost:3000,http://127.0.0.1:3000",
    CF_INTERNAL_SERVICE_TOKEN: infra.CF_INTERNAL_SERVICE_TOKEN,
    CF_CREDENTIAL_KEK_V1: kek,
  });
  const aiFile = path.join(root, "ai-service/.env");
  const ai = await readEnv(aiFile);
  if (Object.keys(ai).some((k) => /DB_|DATABASE|KEK/.test(k))) {
    throw new Error("Remove database credentials/KEK from ai-service/.env");
  }
  await writeEnv(aiFile, {
    ...ai,
    CF_PROVIDER_MODE: "MOCK",
    CF_INTERNAL_SERVICE_TOKEN: infra.CF_INTERNAL_SERVICE_TOKEN,
  });
  const webFile = path.join(root, "web/.env.local");
  await writeEnv(webFile, {
    ...(await readEnv(webFile)),
    NEXT_PUBLIC_CORE_API_URL:
      "http://localhost:" + (mode === "ide" ? "18080" : "8080"),
  });
  return {
    mode,
    coreUrl: "http://localhost:" + (mode === "ide" ? "18080" : "8080"),
  };
}
