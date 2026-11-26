"""Export the actual FastAPI models plus the internal stream event schemas."""

import json
import sys
from pathlib import Path

from app.main import app
from app.schemas.contracts import INTERNAL_EVENTS


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
