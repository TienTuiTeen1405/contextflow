"""Safe XML escaping and element rendering helpers for prompt construction."""


def escape_xml(text: str) -> str:
    """Escape XML delimiters (&, <, >) before inserting untrusted text into XML tags."""
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def escape_xml_attr(value: str) -> str:
    """Escape XML attribute values (&, <, >, quotes)."""
    return escape_xml(value).replace('"', "&quot;").replace("'", "&apos;")


def render_attrs(attrs: dict[str, str | int | bool | None]) -> str:
    """Render non-None XML attributes with escaped values."""
    parts: list[str] = []
    for key, raw in attrs.items():
        if raw is None:
            continue
        if isinstance(raw, bool):
            formatted = "true" if raw else "false"
        else:
            formatted = str(raw)
        parts.append(f'{key}="{escape_xml_attr(formatted)}"')
    return (" " + " ".join(parts)) if parts else ""


def xml_leaf(tag: str, text: str, **attrs: str | int | bool | None) -> str:
    """Render an XML leaf element preserving inner whitespace exactly."""
    return f"<{tag}{render_attrs(attrs)}>{escape_xml(text)}</{tag}>"


def xml_empty(tag: str, **attrs: str | int | bool | None) -> str:
    """Render a self-closing XML element with optional attributes."""
    return f"<{tag}{render_attrs(attrs)} />"


def xml_section(tag: str, children: list[str], **attrs: str | int | bool | None) -> str:
    """Render a container XML element wrapping child elements."""
    attr_str = render_attrs(attrs)
    if not children:
        return f"<{tag}{attr_str} />"
    body = "\n".join(children)
    return f"<{tag}{attr_str}>\n{body}\n</{tag}>"
