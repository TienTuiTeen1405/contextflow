import path from "node:path";
import { ROOT, readEnv } from "./local-env.mjs";
const web = await readEnv(path.join(ROOT, "web/.env.local"));
const base = web.NEXT_PUBLIC_CORE_API_URL ?? "http://localhost:18080";
if (!["http://localhost:8080", "http://localhost:18080"].includes(base))
  throw new Error("Select the docker or ide local preset first");
const health = await fetch(base + "/actuator/health/readiness", {
  signal: AbortSignal.timeout(10000),
});
if (!health.ok || (await health.json()).status !== "UP")
  throw new Error("Core is not ready");
const response = await fetch(base + "/v3/api-docs", {
  headers: { Origin: "http://localhost:3000" },
  signal: AbortSignal.timeout(10000),
});
const spec = await response.json();
if (
  !response.ok ||
  !spec.openapi ||
  !spec.paths ||
  response.headers.get("access-control-allow-origin") !==
    "http://localhost:3000"
)
  throw new Error("Core Swagger/CORS does not match local configuration");
console.log("PASS Core " + base + ": readiness, runtime OpenAPI and CORS");
if (base.endsWith(":18080")) {
  const ai = await readEnv(path.join(ROOT, "ai-service/.env"));
  const result = await fetch("http://127.0.0.1:18000/internal/v1/ai/status", {
    headers: { Authorization: "Bearer " + ai.CF_INTERNAL_SERVICE_TOKEN },
    signal: AbortSignal.timeout(10000),
  });
  const status = await result.json();
  if (
    !result.ok ||
    status.mode !== "MOCK" ||
    status.external_calls_enabled !== false
  )
    throw new Error("Local AI mock is unavailable");
  console.log("PASS local AI service identity and MOCK mode");
}
