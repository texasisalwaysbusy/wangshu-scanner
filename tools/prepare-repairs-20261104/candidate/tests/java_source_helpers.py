"""Dependency-free Java source-body helpers; not an Android parser/compiler."""
import re

def java_mask(source: str, *, literals: bool = True) -> str:
    """Preserve offsets/newlines while masking Java comments and optionally literals."""
    out = list(source)
    state = "code"
    escaped = False
    i = 0
    while i < len(source):
        char, pair = source[i], source[i:i + 2]
        if state == "code":
            if pair in {"//", "/*"}:
                state = "line" if pair == "//" else "block"
                out[i:i + 2] = "  "
                i += 2
                continue
            if char in {'"', "'"}:
                state = char
                escaped = False
                if literals:
                    out[i] = " "
        elif state == "line":
            if char == "\n":
                state = "code"
            else:
                out[i] = " "
        elif state == "block":
            if pair == "*/":
                out[i:i + 2] = "  "
                state = "code"
                i += 2
                continue
            if char != "\n":
                out[i] = " "
        else:
            if literals and char != "\n":
                out[i] = " "
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == state:
                state = "code"
        i += 1
    return "".join(out)


def java_body(source: str, declaration: str) -> str:
    """Extract a declaration body; braces in strings/comments cannot close it."""
    masked = java_mask(source)
    match = re.search(declaration, masked)
    if match is None:
        raise AssertionError("Java declaration missing: " + declaration)
    start = masked.index("{", match.end())
    depth = 1
    for index in range(start + 1, len(masked)):
        depth += (masked[index] == "{") - (masked[index] == "}")
        if depth == 0:
            return source[start + 1:index]
    raise AssertionError("Unclosed Java declaration: " + declaration)


def method(source: str, name: str) -> str:
    return java_body(source, rf"\b(?:void|Object|EditText|String)\s+{name}\s*\([^)]*\)")


def preparation_helper_calls(source: str) -> set[str]:
    """Reject activation of retained functional helpers, rather than banning their text."""
    forbidden = {"editAll", "candidate", "exports", "recreate", "txt", "open", "text", "crop", "refreshDoc", "click", "record", "check"}
    active = java_mask(method(source, "onStart") + method(source, "setup"))
    return {name for name in forbidden if re.search(rf"\b{name}\s*\(", active)}


