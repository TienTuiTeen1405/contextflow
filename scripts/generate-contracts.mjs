import { readFile, writeFile, mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import openapiTS, { astToString } from "openapi-typescript";
import prettier from "prettier";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const check = process.argv.includes("--check");
let drift = false;
const readJson = async (p) =>
  JSON.parse(await readFile(path.join(root, p), "utf8"));
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
const publicSpec = await readJson("contracts/public.openapi.json");
const internalSpec = await readJson("contracts/internal.openapi.json");
const errors = (await readJson("contracts/error-codes.json")).errors;
await output(
  "web/src/lib/api/generated/public.ts",
  astToString(await openapiTS(publicSpec)),
  "typescript",
);

const pascal = (s) =>
  s.replace(/(^|[_-])([a-z])/g, (_, _p, c) => c.toUpperCase());
const camel = (s) => s.replace(/_([a-z])/g, (_, c) => c.toUpperCase());
const refName = (s) => s.$ref?.split("/").at(-1);
const nonNull = (s) => s.anyOf?.filter((a) => a.type !== "null");
const nullable = (s) =>
  s.type === "null" ||
  s.anyOf?.some((a) => a.type === "null") ||
  (Array.isArray(s.type) && s.type.includes("null"));
function javaModule(spec, name) {
  const schemas = structuredClone(spec.components.schemas);
  const implementsMap = {};
  for (const [n, s] of Object.entries(schemas)) {
    if (s.oneOf && s.discriminator) {
      for (const t of s.oneOf) (implementsMap[refName(t)] ??= []).push(n);
    }
  }
  const inlineEnums = {};
  function base(s) {
    const choices = nonNull(s);
    if (choices?.length === 1) return base(choices[0]);
    return s;
  }
  function type(s, hint) {
    s = base(s);
    const ref = refName(s);
    if (ref) return ref === "JsonValue" ? "JsonNode" : ref;
    if (s.enum && s.type === "string") {
      inlineEnums[hint] = s.enum;
      return hint;
    }
    if (s.anyOf || s.oneOf) return "JsonNode";
    if (s.type === "array")
      return "List<" + type(s.items ?? {}, hint + "Item") + ">";
    if (s.type === "object" || !s.type) return "JsonNode";
    if (s.type === "null") return "Void";
    if (s.type === "integer") return "Long";
    if (s.type === "number") return "Double";
    if (s.type === "boolean") return "Boolean";
    if (s.format === "uuid") return "UUID";
    if (s.format === "date-time") return "Instant";
    return "String";
  }
  function annotations(p, required, prop) {
    const b = base(p),
      a = [
        '@JsonProperty(value = "' + prop + '", required = ' + required + ")",
      ];
    if (required && !nullable(p)) a.push("@NotNull");
    if (!required) a.push("@JsonInclude(JsonInclude.Include.NON_NULL)");
    if (b.minimum !== undefined) a.push("@Min(" + b.minimum + ")");
    if (b.maximum !== undefined) a.push("@Max(" + b.maximum + ")");
    if (typeof b.const === "number")
      a.push("@Min(" + b.const + ") @Max(" + b.const + ")");
    if (b.type === "boolean" && b.const !== undefined)
      a.push(b.const ? "@AssertTrue" : "@AssertFalse");
    if (
      b.minLength !== undefined ||
      b.maxLength !== undefined ||
      b.minItems !== undefined ||
      b.maxItems !== undefined
    ) {
      if (b.type === "array") {
        a.push(
          "@Size(min = " +
            (b.minLength ?? b.minItems ?? 0) +
            ", max = " +
            (b.maxLength ?? b.maxItems ?? 2147483647) +
            ")",
        );
      } else if (b.type === "string")
        a.push(
          "@CodePointSize(min = " +
            (b.minLength ?? 0) +
            ", max = " +
            (b.maxLength ?? 2147483647) +
            ")",
        );
    }
    if (b.pattern && !["uuid", "date-time"].includes(b.format))
      a.push("@Pattern(regexp = " + JSON.stringify(b.pattern) + ")");
    if (typeof b.const === "string")
      a.push("@Pattern(regexp = " + JSON.stringify("^" + b.const + "$") + ")");
    if (b.$ref || b.type === "array") a.push("@Valid");
    return a.join(" ");
  }
  let code =
    "// Generated from contracts/*.openapi.json. Do not edit.\n" +
    "package com.contextflow.contracts.generated;\n\n" +
    "import com.fasterxml.jackson.annotation.*;\n" +
    "import com.fasterxml.jackson.databind.JsonNode;\n" +
    "import jakarta.annotation.Generated;\n" +
    "import jakarta.validation.Valid;\n" +
    "import jakarta.validation.constraints.*;\n" +
    "import com.contextflow.contracts.CodePointSize;\n" +
    "import java.time.Instant;\nimport java.util.List;\nimport java.util.UUID;\n\n" +
    '@Generated("scripts/generate-contracts.mjs")\npublic final class ' +
    name +
    " {\n" +
    "    private " +
    name +
    "() {}\n\n";
  for (const [n, s] of Object.entries(schemas)) {
    if (n === "JsonValue") continue;
    if (s.enum) {
      inlineEnums[n] = s.enum;
      continue;
    }
    if (s.oneOf && s.discriminator) {
      const d = s.discriminator;
      code +=
        '    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "' +
        d.propertyName +
        '", visible = true)\n    @JsonSubTypes({\n' +
        Object.entries(d.mapping)
          .map(
            ([tag, ref]) =>
              "        @JsonSubTypes.Type(value = " +
              ref.split("/").at(-1) +
              '.class, name = "' +
              tag +
              '")',
          )
          .join(",\n") +
        "\n    })\n    public sealed interface " +
        n +
        " permits " +
        s.oneOf.map(refName).join(", ") +
        " {}\n\n";
      continue;
    }
    if (s.type !== "object" || !s.properties) continue;
    const props = Object.entries(s.properties);
    const fields = props.map(
      ([p, v]) =>
        "        " +
        annotations(v, (s.required ?? []).includes(p), p) +
        "\n        " +
        type(v, n + pascal(p)) +
        " " +
        camel(p),
    );
    code +=
      "    public record " +
      n +
      "(\n" +
      fields.join(",\n") +
      "\n    )" +
      (implementsMap[n] ? " implements " + implementsMap[n].join(", ") : "");
    const defaults = props.filter(
      ([_p, v]) => Array.isArray(v.default) && v.default.length === 0,
    );
    if (defaults.length) {
      code +=
        " {\n        public " +
        n +
        " {\n" +
        defaults
          .map(
            ([p]) =>
              "            " +
              camel(p) +
              " = " +
              camel(p) +
              " == null ? List.of() : List.copyOf(" +
              camel(p) +
              ");",
          )
          .join("\n") +
        "\n        }\n    }\n\n";
    } else code += " {}\n\n";
  }
  for (const [n, values] of Object.entries(inlineEnums)) {
    code += "    public enum " + n + " {\n";
    if (n === "ErrorCode") {
      code +=
        errors
          .map(
            (e) =>
              "        " +
              e.code +
              "(" +
              (e.httpStatus ?? "null") +
              ", " +
              e.retryable +
              ")",
          )
          .join(",\n") +
        ";\n        private final Integer httpStatus;\n        private final boolean retryable;\n" +
        "        ErrorCode(Integer httpStatus, boolean retryable) { this.httpStatus = httpStatus; this.retryable = retryable; }\n" +
        "        public Integer httpStatus() { return httpStatus; }\n" +
        "        public boolean retryable() { return retryable; }\n";
    } else {
      code +=
        values
          .map(
            (v) =>
              '        @JsonProperty("' +
              v +
              '") ' +
              String(v).replaceAll(/[^A-Za-z0-9_]/g, "_"),
          )
          .join(",\n") + "\n";
    }
    code += "    }\n\n";
  }
  return code + "}\n";
}
for (const [spec, name] of [
  [publicSpec, "PublicApi"],
  [internalSpec, "InternalAi"],
]) {
  await output(
    "core-backend/src/main/java/com/contextflow/contracts/generated/" +
      name +
      ".java",
    javaModule(spec, name),
  );
}
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
