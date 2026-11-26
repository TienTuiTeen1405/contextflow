import path from "node:path";
import { ROOT, readEnv } from "./local-env.mjs";
const web = await readEnv(path.join(ROOT, "web/.env.local"));
const base = web.NEXT_PUBLIC_CORE_API_URL ?? "http://localhost:8080";
const allowed = new Set(["http://localhost:8080", "http://localhost:18080"]);
if (!allowed.has(base))
  throw new Error("Select the docker or ide local preset first");
const health = await fetch(base + "/actuator/health/readiness", {
  signal: AbortSignal.timeout(10000),
});
if (!health.ok || (await health.json()).status !== "UP")
  throw new Error("Core is not ready");
const business = await fetch(base + "/api/v1/projects", {
  headers: { Origin: "http://localhost:3000" },
  signal: AbortSignal.timeout(10000),
});
const body = await business.json();
const demo = business.headers.get("X-ContextFlow-Mode") === "DEMO";
if (process.argv.includes("--demo") && !demo)
  throw new Error(
    "Demo profile is not active; restart Core with the demo preset",
  );
if (
  (demo
    ? business.status !== 200 || !Array.isArray(body.items)
    : business.status !== 401 || body.error?.code !== "UNAUTHENTICATED") ||
  business.headers.get("access-control-allow-origin") !==
    "http://localhost:3000"
) {
  throw new Error("Core auth/CORS baseline does not match Web configuration");
}
console.log(
  "PASS Web configuration -> Core " +
    base +
    (demo
      ? ": readiness, demo workspace and CORS"
      : ": readiness, auth envelope and CORS"),
);
if (demo) {
  const profile = await fetch(base + "/api/v1/me", {
    signal: AbortSignal.timeout(10000),
  });
  const user = await profile.json();
  if (
    !profile.ok ||
    user.id !== "d0000000-0000-4000-8000-000000000001" ||
    user.role !== "USER" ||
    user.hasLocalPassword !== false
  )
    throw new Error("Unexpected demo identity");
  console.log("PASS fixed DemoUser without JWT");
}
if (base.endsWith(":18080")) {
  const ai = await readEnv(path.join(ROOT, "ai-service/.env"));
  const response = await fetch("http://127.0.0.1:18000/internal/v1/ai/status", {
    headers: { Authorization: "Bearer " + ai.CF_INTERNAL_SERVICE_TOKEN },
    signal: AbortSignal.timeout(10000),
  });
  const status = await response.json();
  if (
    !response.ok ||
    status.mode !== "MOCK" ||
    status.external_calls_enabled !== false
  )
    throw new Error("IDE AI mock is unavailable");
  console.log("PASS local AI service identity and MOCK mode");
}
