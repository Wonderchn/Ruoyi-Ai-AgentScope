"""Shared parsing helpers for the legacy ruoyi-ai MySQL DDL.

Both the unified-DDL converter (mysql-to-postgres.py, which emits V9) and the legacy
row-data generator (generate-legacy-ai-copy.py, which emits V11 plus the staging DDL)
have to read the same MySQL install script. Keeping the parser in one module means the
two generated artifacts cannot disagree about what a legacy table looks like.

The helpers are deliberately strict: anything that cannot be mapped is appended to
WARNINGS instead of being dropped, so a reviewer sees exactly which MySQL features did
not survive the conversion.
"""
import re

TYPE_MAP = [
    (re.compile(r"^bigint(\(\d+\))?$", re.I), "bigint"),
    (re.compile(r"^int(\(\d+\))?$", re.I), "integer"),
    (re.compile(r"^tinyint(\(\d+\))?$", re.I), "smallint"),
    (re.compile(r"^smallint(\(\d+\))?$", re.I), "smallint"),
    (re.compile(r"^mediumint(\(\d+\))?$", re.I), "integer"),
    (re.compile(r"^(long|medium)?text$", re.I), "text"),
    (re.compile(r"^(long|medium|tiny)?blob$", re.I), "bytea"),
    (re.compile(r"^datetime(\(\d+\))?$", re.I), "timestamp"),
    (re.compile(r"^timestamp(\(\d+\))?$", re.I), "timestamp"),
    (re.compile(r"^double(\(\d+(,\d+)?\))?$", re.I), "double precision"),
    (re.compile(r"^float(\(\d+(,\d+)?\))?$", re.I), "real"),
    (re.compile(r"^json$", re.I), "jsonb"),
    (re.compile(r"^bit(\(\d+\))?$", re.I), "smallint"),
    (re.compile(r"^decimal", re.I), "numeric"),
    (re.compile(r"^date$", re.I), "date"),
    (re.compile(r"^time$", re.I), "time"),
    (re.compile(r"^char\((\d+)\)$", re.I), None),      # keep as char(n)
    (re.compile(r"^varchar\((\d+)\)$", re.I), None),    # keep as varchar(n)
]

WARNINGS = []


def warn(msg):
    WARNINGS.append(msg)


def map_type(mysql_type):
    t = mysql_type.strip()
    # enum/set become text with the original option list preserved as a CHECK-able comment
    m = re.match(r"^(enum|set)\s*\((.*)\)$", t, re.I | re.S)
    if m:
        warn("enum/set converted to varchar(64): %s" % t[:80])
        return "varchar(64)"
    for pattern, replacement in TYPE_MAP:
        if pattern.match(t):
            return replacement if replacement is not None else t.lower()
    warn("unmapped type kept verbatim: %s" % t)
    return t


def split_defs(body):
    """Split on top-level commas, ignoring parentheses AND quoted strings.

    MySQL column comments carry commas and JSON braces (COMMENT 'type=chat, category=chat'),
    so a parenthesis-only splitter tears those definitions apart.
    """
    parts, depth, cur, in_quote = [], 0, [], False
    i = 0
    while i < len(body):
        ch = body[i]
        if in_quote:
            cur.append(ch)
            if ch == "'":
                if i + 1 < len(body) and body[i + 1] == "'":
                    cur.append("'")
                    i += 2
                    continue
                in_quote = False
            elif ch == "\\":
                if i + 1 < len(body):
                    cur.append(body[i + 1])
                    i += 2
                    continue
        else:
            if ch == "'":
                in_quote = True
                cur.append(ch)
            elif ch == "(":
                depth += 1
                cur.append(ch)
            elif ch == ")":
                depth -= 1
                cur.append(ch)
            elif ch == "," and depth == 0:
                parts.append("".join(cur))
                cur = []
            else:
                cur.append(ch)
        i += 1
    if cur:
        parts.append("".join(cur))
    return [p.strip() for p in parts if p.strip()]


def find_body(src, start):
    depth = 0
    for i in range(start, len(src)):
        if src[i] == "(":
            depth += 1
        elif src[i] == ")":
            depth -= 1
            if depth == 0:
                return src[start + 1:i]
    return ""


def clean_index_cols(raw_cols):
    """Drop MySQL index modifiers (ASC/DESC, prefix lengths, USING BTREE) from column lists.

    Implemented as token filtering rather than regex substitution so the modifier handling
    does not depend on escape sequences that are easy to corrupt when the file is patched
    by tooling.
    """
    out = []
    for chunk in raw_cols.split(","):
        kept = []
        for token in chunk.split():
            if token.upper() in ("ASC", "DESC", "USING", "BTREE", "HASH"):
                continue
            kept.append(token)
        name = " ".join(kept).strip().strip("`").strip()
        if "(" in name and name.endswith(")"):
            name = name[: name.index("(")]
        if name:
            out.append(name)
    return out


def quote(text):
    return "'%s'" % text.replace("'", "''")


def strip_line_comments(raw):
    """Remove MySQL `--` line comments so they cannot confuse the parser."""
    return "\n".join(re.sub(r"^\s*--.*$", "", line) for line in raw.splitlines())


def parse_mysql_table(raw, table):
    """(columns, table_comment) for one MySQL table; columns are (name, definition) pairs.

    `raw` is expected to be comment-stripped already (see strip_line_comments).
    """
    pattern = re.compile(r"CREATE TABLE (?:IF NOT EXISTS )?`%s`\s*\(" % re.escape(table), re.I)
    m = pattern.search(raw)
    if not m:
        return None, None
    body = find_body(raw, m.end() - 1)
    tail = raw[m.end() - 1 + len(body) + 1: m.end() - 1 + len(body) + 400]
    tc = re.search(r"COMMENT\s*=\s*'((?:[^']|'')*)'", tail, re.I)
    table_comment = tc.group(1).replace("''", "'") if tc else None
    columns = []
    for part in split_defs(body):
        head = part.split()[0] if part.split() else ""
        if not head.startswith("`"):
            continue
        col = head.strip("`")
        columns.append((col, part[len(head):].strip()))
    return columns, table_comment


def column_type(definition):
    """The declared type token of a column definition, e.g. `varchar(32)`."""
    m = re.match(r"([a-z]+(?:\s*\([^)]*\))?)", definition, re.I)
    return m.group(1) if m else ""


def is_not_null(definition):
    return bool(re.search(r"\bNOT\s+NULL\b", definition, re.I))


def column_default(definition):
    m = re.search(r"\bDEFAULT\s+('(?:[^']|'')*'|[^\s,]+)", definition, re.I)
    return m.group(1) if m else None
