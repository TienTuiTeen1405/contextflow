"""Export the actual FastAPI models plus the internal stream event schemas."""

import json
import sys
from pathlib import Path

from pydantic import RootModel

from app.main import app
from app.schemas.contracts import INTERNAL_EVENTS, ContentBlock


def contract() -> dict[str, object]:
    spec = app.openapi()
    spec["info"]["title"] = "ContextFlow internal AI — BranOS-04-6"
    spec["info"]["description"] = (
        "Request-scoped MOCK task endpoints; no real provider integration."
    )
    schemas = spec.setdefault("components", {}).setdefault("schemas", {})
    for model in INTERNAL_EVENTS.values():
        schema = model.model_json_schema(ref_template="#/components/schemas/{model}")
        schemas.update(schema.pop("$defs", {}))
        schemas[model.__name__] = schema
    block_schema = RootModel[ContentBlock].model_json_schema(
        ref_template="#/components/schemas/{model}"
    )
    schemas.update(block_schema.pop("$defs", {}))
    block_schema["title"] = "ContentBlock"

    def named_blocks(value: object) -> object:
        if isinstance(value, dict):
            discriminator = value.get("discriminator", {})
            if discriminator.get("propertyName") == "type" and set(
                discriminator.get("mapping", {})
            ) == {"TEXT", "QUOTE_REF"}:
                return {"$ref": "#/components/schemas/ContentBlock"}
            return {key: named_blocks(item) for key, item in value.items()}
        if isinstance(value, list):
            return [named_blocks(item) for item in value]
        return value

    for name, schema in list(schemas.items()):
        schemas[name] = named_blocks(schema)
    schemas["ContentBlock"] = block_schema
    spec["x-sse-events"] = {event: model.__name__ for event, model in INTERNAL_EVENTS.items()}
    spec["x-sources"] = [{"pageId": "7536753", "version": 2}]
    return spec


if __name__ == "__main__":
    destination = Path(__file__).resolve().parents[2] / "contracts" / "internal.openapi.json"
    content = json.dumps(contract(), ensure_ascii=False, indent=2) + "\n"
    if "--check" in sys.argv:
        if (
            not destination.exists()
            or json.loads(destination.read_text(encoding="utf-8")) != contract()
        ):
            raise SystemExit("Internal OpenAPI drift: run npm run contracts:generate")
    else:
        destination.write_text(content, encoding="utf-8", newline="\n")
