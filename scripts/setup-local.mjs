import { ROOT, setupLocal } from "./local-env.mjs";
const at = process.argv.indexOf("--mode");
try {
  const result = await setupLocal(
    ROOT,
    at < 0 ? "docker" : process.argv[at + 1],
    { demo: process.argv.includes("--demo") },
  );
  console.log("Local mode: " + result.mode + "; Web -> Core " + result.coreUrl);
  console.log(
    "Core profile: " +
      (result.demo ? "demo (fixed local user)" : "normal (closed API)"),
  );
  console.log(
    "Local secrets are synchronized; values were not printed. Restart Web after switching mode.",
  );
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
