"""Parse CREATE TABLE shapes out of the migrations that this project generates.

Both the MySQL-to-PostgreSQL converter and the unified-copy generator need to know what a
table looked like at a given point in the chain (the unified shape before the additive
merges, for instance). Parsing the generated SQL is deliberate: it is the same text the
database receives, so the two cannot disagree.
"""
import re


def split_top_level(body):
    """Split a definition body on top-level commas, respecting quotes and nesting."""
    parts, depth, cur, in_quote, i = [], 0, [], False, 0
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
        elif ch == "'":
            in_quote = True
            cur.append(ch)
        elif ch in "([":
            depth += 1
            cur.append(ch)
        elif ch in ")]":
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


def parse_created_tables(path):
    """{table: [column, ...]} for every unified table in a file, in final column order.

    A single ordered pass over CREATE TABLE and ALTER TABLE: the frozen AI migrations add
    tenant and ACL columns with later ALTERs (V3 adds tenant_id to every business table),
    so a parser that only reads CREATE TABLE reports a shape the database never has. That
    mistake showed up as a NOT NULL violation when the copy omitted tenant_id.
    """
    sql = open(path, encoding="utf-8").read()
    out = {}

    events = []
    for m in re.finditer(r"CREATE TABLE IF NOT EXISTS platform\.([a-z_]\w*)\s*\(", sql, re.I):
        events.append((m.start(), "create", m))
    for m in re.finditer(r"ALTER TABLE (?:IF EXISTS )?(?:ONLY )?platform\.([a-z_]\w*)\s+([^;]*);", sql, re.I | re.S):
        events.append((m.start(), "alter", m))
    events.sort(key=lambda e: e[0])

    for _, kind, m in events:
        table = m.group(1)
        if kind == "create":
            depth, start, body = 0, m.end() - 1, ""
            for i in range(start, len(sql)):
                if sql[i] == "(":
                    depth += 1
                elif sql[i] == ")":
                    depth -= 1
                    if depth == 0:
                        body = sql[start + 1:i]
                        break
            cols = []
            for part in split_top_level(body):
                cm = re.match(r'"(?P<q>\w+)"\s+(?P<d>.+)', part, re.S) or \
                     re.match(r"(?P<q>\w+)\s+(?P<d>.+)", part, re.S)
                if not cm or re.match(r"^(CONSTRAINT|PRIMARY|UNIQUE|FOREIGN|CHECK)$",
                                      cm.group("q"), re.I):
                    continue
                cols.append(cm.group("q"))
            out[table] = cols
            continue

        body = m.group(2)
        cols = out.setdefault(table, [])
        for am in re.finditer(r"ADD\s+COLUMN\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:\"(\w+)\"|(\w+))", body, re.I):
            name = am.group(1) or am.group(2)
            if name not in cols:
                cols.append(name)
        for dm in re.finditer(r"DROP\s+COLUMN\s+(?:IF\s+EXISTS\s+)?(?:\"(\w+)\"|(\w+))", body, re.I):
            name = dm.group(1) or dm.group(2)
            if name in cols:
                cols.remove(name)
        for rm in re.finditer(r"RENAME\s+COLUMN\s+(?:\"(\w+)\"|(\w+))\s+TO\s+(?:\"(\w+)\"|(\w+))", body, re.I):
            old, new = (rm.group(1) or rm.group(2)), (rm.group(3) or rm.group(4))
            if old in cols:
                cols[cols.index(old)] = new
    return out


def parse_created_table_definitions(path):
    """{table: {column: definition}} for CREATE TABLE statements only.

    Used where the declared type matters (the merge conflict report); ALTER-added columns
    carry their own types and are not part of that comparison.
    """
    sql = open(path, encoding="utf-8").read()
    out = {}
    for m in re.finditer(r"CREATE TABLE IF NOT EXISTS platform\.([a-z_]\w*)\s*\(", sql, re.I):
        depth, start, body = 0, m.end() - 1, ""
        for i in range(start, len(sql)):
            if sql[i] == "(":
                depth += 1
            elif sql[i] == ")":
                depth -= 1
                if depth == 0:
                    body = sql[start + 1:i]
                    break
        cols = {}
        for part in split_top_level(body):
            cm = re.match(r'"(?P<q>\w+)"\s+(?P<d>.+)', part, re.S) or \
                 re.match(r"(?P<q>\w+)\s+(?P<d>.+)", part, re.S)
            if not cm or re.match(r"^(CONSTRAINT|PRIMARY|UNIQUE|FOREIGN|CHECK)$",
                                  cm.group("q"), re.I):
                continue
            cols[cm.group("q")] = " ".join(cm.group("d").split()).rstrip(",")
        out[m.group(1)] = cols
    return out
