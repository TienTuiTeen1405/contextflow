import path from "node:path";
import { spawn } from "node:child_process";
import { ROOT, readEnv } from "./local-env.mjs";
const config = await readEnv(path.join(ROOT, "core-backend/.env"));
if (!config.CF_DB_PASSWORD)
  throw new Error("Run npm run setup:local -- --mode ide first");
const child =
  process.platform === "win32"
    ? spawn(
        "powershell.exe",
        ["-NoProfile", "-Command", "& .\\mvnw.cmd spring-boot:run"],
        {
          cwd: path.join(ROOT, "core-backend"),
          env: { ...process.env, ...config },
          stdio: "inherit",
        },
      )
    : spawn("./mvnw", ["spring-boot:run"], {
        cwd: path.join(ROOT, "core-backend"),
        env: { ...process.env, ...config },
        stdio: "inherit",
      });
child.on("error", () => {
  console.error("Cannot launch Maven Wrapper; check JDK 21 and JAVA_HOME.");
  process.exitCode = 1;
});
child.on("exit", (code) => {
  process.exitCode = code ?? 1;
});
