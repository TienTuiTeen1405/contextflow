import path from "node:path";
import { spawn } from "node:child_process";
import { ROOT } from "./local-env.mjs";
const child =
  process.platform === "win32"
    ? spawn(
        "powershell.exe",
        ["-NoProfile", "-Command", "& .\\mvnw.cmd spring-boot:run"],
        {
          cwd: path.join(ROOT, "core-backend"),
          stdio: "inherit",
        },
      )
    : spawn("./mvnw", ["spring-boot:run"], {
        cwd: path.join(ROOT, "core-backend"),
        stdio: "inherit",
      });
child.on("error", () => {
  console.error("Cannot launch Maven Wrapper; check JDK 21 and JAVA_HOME.");
  process.exitCode = 1;
});
child.on("exit", (code) => {
  process.exitCode = code ?? 1;
});
