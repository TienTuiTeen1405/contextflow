import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { ROOT } from "./local-env.mjs";

const policy = JSON.parse(
  await readFile(path.join(ROOT, ".github/ownership.json"), "utf8"),
);
const { readdir } = await import("node:fs/promises");
const migrationDir = path.join(
  ROOT,
  "core-backend/src/main/resources/db/migration",
);
const tables = new Set();
for (const file of await readdir(migrationDir)) {
  if (!file.endsWith(".sql")) continue;
  const sql = await readFile(path.join(migrationDir, file), "utf8");
  for (const match of sql.matchAll(/CREATE TABLE\s+(\w+)/g))
    tables.add(match[1]);
}
if (tables.size !== 43 || policy.tables.length !== tables.size)
  throw new Error("Expected ownership for 43 business tables");
const owned = new Set();
for (const row of policy.tables) {
  if (!tables.has(row.table) || owned.has(row.table))
    throw new Error("Missing/duplicate table owner: " + row.table);
  owned.add(row.table);
  if (!policy.roles[row.ownerRole]) throw new Error("Unknown table owner role");
  if (row.javaPackage !== "com.contextflow." + row.domain)
    throw new Error("Package/domain ownership mismatch");
}
for (const rule of policy.pathRules)
  for (const role of rule.requiredReviewRoles) {
    if (!policy.roles[role]) throw new Error("Unknown review role: " + role);
  }
for (const role of Object.values(policy.roles))
  for (const handle of role.github) {
    if (
      !/^@[A-Za-z0-9][A-Za-z0-9-]*(\/[A-Za-z0-9][A-Za-z0-9_-]*)?$/.test(handle)
    )
      throw new Error("Invalid GitHub owner binding");
  }
const pending = Object.entries(policy.roles)
  .filter(([_id, role]) => role.github.length === 0)
  .map(([id]) => id);
if (process.argv.includes("--generate")) {
  if (pending.length)
    throw new Error(
      "Assign GitHub members to roles first: " + pending.join(", "),
    );
  const lines = policy.pathRules.map(
    (rule) =>
      rule.pattern +
      " " +
      [
        ...new Set(
          rule.requiredReviewRoles.flatMap((role) => policy.roles[role].github),
        ),
      ].join(" "),
  );
  await writeFile(
    path.join(ROOT, ".github/CODEOWNERS"),
    "# Generated from ownership.json. GitHub CODEOWNERS matches the LAST rule.\n" +
      lines.join("\n") +
      "\n",
  );
  console.log(
    "Generated .github/CODEOWNERS; configure repository review rules separately.",
  );
} else {
  console.log("PASS ownership: 43 tables, domains and shared review roles");
  if (pending.length)
    console.log(
      "GitHub reviewer assignment pending for " +
        pending.length +
        " roles; no named reviewer is enforced yet.",
    );
}
