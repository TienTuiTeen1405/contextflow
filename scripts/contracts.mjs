import { spawnSync } from "node:child_process";
import path from "node:path";
import { ROOT } from "./local-env.mjs";

const check = process.argv.includes("--check");
const exported = spawnSync(
  "uv",
  [
    "run",
    "--frozen",
    "python",
    "-m",
    "app.export_contract",
    ...(check ? ["--check"] : []),
  ],
  {
    cwd: path.join(ROOT, "ai-service"),
    stdio: "inherit",
  },
);
if (exported.status !== 0) process.exit(exported.status ?? 1);
const generated = spawnSync(
  process.execPath,
  ["scripts/generate-contracts.mjs", ...(check ? ["--check"] : [])],
  {
    cwd: ROOT,
    stdio: "inherit",
  },
);
process.exitCode = generated.status ?? 1;
