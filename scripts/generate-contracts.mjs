import { readFile, writeFile, mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import yaml from "js-yaml";
import openapiTS, { astToString } from "openapi-typescript";
import prettier from "prettier";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const check = process.argv.includes("--check");
let drift = false;
const readJson = async (p) =>
  JSON.parse(await readFile(path.join(root, p), "utf8"));
const readYaml = async (p) =>
  yaml.load(await readFile(path.join(root, p), "utf8"));
async function output(p, content, parser) {
  if (parser) content = await prettier.format(content, { parser });
  let old;
  try {
    old = await readFile(path.join(root, p), "utf8");
  } catch {
    old = "";
  }
  if (old.replaceAll("\r\n", "\n") === content) return;
  if (check) {
    console.error("Contract drift: " + p);
    drift = true;
    return;
  }
  await mkdir(path.dirname(path.join(root, p)), { recursive: true });
  await writeFile(path.join(root, p), content);
}
const publicSpec = await readYaml("contracts/public.openapi.yaml");
const errors = (await readJson("contracts/error-codes.json")).errors;
await output(
  "web/src/lib/api/generated/public.ts",
  astToString(await openapiTS(publicSpec)),
  "typescript",
);

await output(
  "web/src/lib/api/error-codes.ts",
  "// Generated from contracts/error-codes.json.\nexport const ERROR_CODES = " +
    JSON.stringify(
      Object.fromEntries(
        errors.map((e) => [
          e.code,
          { httpStatus: e.httpStatus, retryable: e.retryable, sse: e.sse },
        ]),
      ),
    ) +
    " as const;\n",
  "typescript",
);
if (drift) process.exitCode = 1;
