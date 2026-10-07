"""Cheap SQL lint for the generated migrations.

The guard that matters here is the orphaned comment marker: a header bullet such as
"  * something" with no leading `--` is not a comment, PostgreSQL reads it as SQL, and the
migration dies with "syntax error at or near *". That is exactly what happened once when a
header was patched programmatically, so the generators refuse to emit such a file.

The check has to understand SQL lexical structure, because `*` lines legitimately appear
inside dollar-quoted string content (the seeded agent prompts contain Markdown bullet
lists) and inside block comments. Only `*` lines in normal statement context are flagged.
"""
DOLLAR_TAG = r"\$\$|\$[A-Za-z_][A-Za-z0-9_]*\$"


def _states(text):
    """Yield (line_number, line, in_protected_region) for every line.

    `in_protected_region` is true when the line starts inside a dollar-quoted string, a
    block comment or a single-quoted string, i.e. where SQL syntax rules do not apply.
    """
    import re

    protected = False          # inside dollar-quote / block comment / single-quote string
    block_comment = False
    dollar_tag = None
    in_single = False
    lines = text.splitlines()
    for number, line in enumerate(lines, 1):
        yield number, line, protected
        i = 0
        while i < len(line):
            ch = line[i]
            nxt = line[i + 1] if i + 1 < len(line) else ""
            if block_comment:
                if ch == "*" and nxt == "/":
                    block_comment = False
                    i += 2
                    continue
            elif dollar_tag:
                if line.startswith(dollar_tag, i):
                    i += len(dollar_tag)
                    dollar_tag = None
                    continue
            elif in_single:
                if ch == "'":
                    if nxt == "'":
                        i += 2
                        continue
                    in_single = False
            else:
                if ch == "-" and nxt == "-":
                    break                    # rest of the line is a comment
                if ch == "/" and nxt == "*":
                    block_comment = True
                    i += 2
                    continue
                if ch == "'":
                    in_single = True
                    i += 1
                    continue
                if ch == "$":
                    m = re.match(DOLLAR_TAG, line[i:])
                    if m:
                        dollar_tag = m.group(0)
                        i += len(dollar_tag)
                        continue
            i += 1
        protected = block_comment or bool(dollar_tag) or in_single


def orphaned_comment_lines(sql):
    """Line numbers whose leading `*` sits in normal SQL context (i.e. lost its `--`)."""
    bad = []
    for number, line, protected in _states(sql):
        if protected:
            continue
        if line.lstrip().startswith("*"):
            bad.append(number)
    return bad


def assert_no_orphan_comment_lines(sql, path):
    bad = orphaned_comment_lines(sql)
    if bad:
        raise SystemExit(
            "generated SQL has orphaned comment line(s) at %s in %s: add the '--' prefix"
            % (bad[:5], path)
        )
