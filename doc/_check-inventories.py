#!/usr/bin/env python3
"""Hold a doc table that claims to enumerate a code symbol set to that set.

  python doc/_check-inventories.py            # exit 1 on drift
  python doc/_check-inventories.py --quiet    # summary only

The fourth gate, beside _check-size.py, _verify-refs.mjs and _check-diagrams.py.
It exists because the other three are all *reference* checks: they verify that
what a doc names still exists. None of them can see the opposite defect — a doc
that is missing something. Adding a member to a set in Java breaks no path, no
line number and no diagram, so a table documenting that set silently becomes
incomplete and every gate stays green.

That is not hypothetical here: `SimulatedFault` gained `LOAD_CELL_DROPPED_SAMPLES`
and later three recovery switches, and each time the fault-injection table went
stale with a full green board. An undocumented fault switch is a switch nobody
arms, which defeats the only reason the simulator exists.

Opt in by marking the table:

    <!-- inventory: enum command-deck/src/main/java/.../SimulatedFault.java -->
    | `SimulatedFault` | Trips | Observed outcome |
    |---|---|---|
    | `LOAD_CELL_SILENT` | ... | ... |

The marker names a *kind* and a repo-relative path. Only `enum` is implemented;
the kind is in the syntax so a future set (config properties, STOMP topics) can
be added without changing the marker convention or any existing doc.

Matching is by identifier, not by position or wording: the gate reads the first
cell of each body row, takes the UPPER_SNAKE tokens it finds there, and diffs
that set against the constants declared in the enum. Both directions fail —
a constant with no row is an undocumented member, a row with no constant is a
phantom left behind by a rename or a deletion.

Deliberate limits: it compares the *set* of names only. Whether a row's prose is
still true is the audit pass's job, not this gate's.
"""
import glob
import os
import re
import sys

MARKER = re.compile(r'<!--\s*inventory:\s*(\w+)\s+(\S+?)\s*-->')
IDENT = re.compile(r'[A-Z][A-Z0-9_]{2,}')
DOC_ROOT = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(DOC_ROOT)


def blank_comments_and_literals(text):
    """Blank the *contents* of comments and string/char literals, preserving
    length and newlines so the structural scanners below see only real syntax.

    One pass over all four, not a regex each, because they nest in both
    directions: `A("http://x")` hides a `//` that a comment regex would treat as
    a line comment and eat the rest of the declaration, while `// he said "hi`
    hides a quote that a literal scanner would treat as an opening string.
    Either mistake truncates the constant list and hands back fewer constants
    than the enum has — a false green.

    Not handled: text blocks (\"\"\"...\"\"\"). No enum in this repo uses one as a
    constant argument; if that changes this needs a case.
    """
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        two = text[i:i + 2]
        if two == '/*':
            j = text.find('*/', i + 2)
            j = n if j == -1 else j + 2
            for k in range(i, j):
                if out[k] != '\n':
                    out[k] = ' '
            i = j
        elif two == '//':
            j = text.find('\n', i)
            j = n if j == -1 else j
            for k in range(i, j):
                out[k] = ' '
            i = j
        elif text[i] in '"\'':
            quote = text[i]
            j = i + 1
            while j < n:
                if text[j] == '\\':
                    j += 2
                    continue
                if text[j] == quote:
                    break
                j += 1
            for k in range(i + 1, min(j, n)):
                if out[k] != '\n':
                    out[k] = ' '
            i = min(j + 1, n)
        else:
            i += 1
    return ''.join(out)


def split_top_level(segment):
    """`segment` split on commas that are not inside (), [] or {}. A constant
    may carry an argument list or its own class body, and either can contain
    commas that do not separate constants."""
    parts, depth, cur = [], 0, []
    for ch in segment:
        if ch in '([{':
            depth += 1
        elif ch in ')]}':
            depth -= 1
        elif ch == ',' and depth == 0:
            parts.append(''.join(cur))
            cur = []
            continue
        cur.append(ch)
    parts.append(''.join(cur))
    return parts


def enum_constants(text):
    """Constants of the first enum declared in `text`, in source order.

    The constant list is everything between the enum's `{` and the first `;` at
    its own brace depth — after that come fields and methods, whose names must
    not be mistaken for members. An enum with no members past the list has no
    such `;` and simply runs to the closing brace.

    Split on commas rather than on line starts: `enum E { RED, GREEN, BLUE }` is
    legal and would otherwise report one constant, and a parser that silently
    under-reports makes this gate pass while members go undocumented — a false
    green, which is worse than no gate at all.
    """
    text = blank_comments_and_literals(text)
    head = re.search(r'\benum\s+\w+[^{]*\{', text)
    if not head:
        return None

    body = text[head.end():]
    depth, end = 0, len(body)
    for i, ch in enumerate(body):
        if ch in '([{':
            depth += 1
        elif ch in ')]':
            depth -= 1
        elif ch == '}':
            if depth == 0:
                end = i
                break
            depth -= 1
        elif ch == ';' and depth == 0:
            end = i
            break

    out = []
    for part in split_top_level(body[:end]):
        # Annotations may precede a constant; its argument list or body follows.
        m = re.match(r'\s*(?:@\w+\s*)*([A-Z][A-Z0-9_]*)\s*(?=[({]|$)', part)
        if m:
            out.append(m.group(1))
    return out


def table_after(lines, start):
    """First column of the markdown table following `start`, header and
    separator rows dropped. Returns None when no table follows the marker."""
    i = start
    while i < len(lines) and not lines[i].lstrip().startswith('|'):
        if lines[i].strip() and not lines[i].lstrip().startswith('<!--'):
            return None  # prose intervened: the marker is not on a table
        i += 1

    rows = []
    while i < len(lines) and lines[i].lstrip().startswith('|'):
        rows.append(lines[i])
        i += 1
    if len(rows) < 2:
        return None

    out = []
    for row in rows[2:]:  # drop header + |---|---| separator
        cells = row.strip().strip('|').split('|')
        if not cells:
            continue
        found = IDENT.findall(re.sub(r'[`~*]', '', cells[0]))
        if found:
            out.append(found[0])
    return out


def check(md_path):
    """Every inventory marker in one doc. Returns (results, errors)."""
    rel_md = os.path.relpath(md_path, REPO_ROOT).replace(os.sep, '/')
    lines = open(md_path, encoding='utf-8').read().split('\n')
    results, errors = [], []
    fenced = False

    for n, line in enumerate(lines):
        if line.lstrip().startswith('```'):
            fenced = not fenced
            continue
        if fenced:
            continue

        # A marker inside backticks or a fence is being *quoted* — doc/README.md
        # documents this very syntax, and explaining a marker must not arm one.
        m = MARKER.search(re.sub(r'`[^`]*`', ' ', line))
        if not m:
            continue
        kind, ref = m.group(1), m.group(2)

        if kind != 'enum':
            errors.append('%s:%d  unknown inventory kind "%s"' % (rel_md, n + 1, kind))
            continue

        target = os.path.join(REPO_ROOT, ref)
        if not os.path.isfile(target):
            errors.append('%s:%d  inventory source not found: %s' % (rel_md, n + 1, ref))
            continue

        declared = enum_constants(open(target, encoding='utf-8').read())
        if declared is None:
            errors.append('%s:%d  no enum declared in %s' % (rel_md, n + 1, ref))
            continue

        documented = table_after(lines, n + 1)
        if documented is None:
            errors.append('%s:%d  inventory marker is not followed by a table' % (rel_md, n + 1))
            continue

        missing = [c for c in declared if c not in documented]
        phantom = [c for c in documented if c not in declared]
        results.append((rel_md, ref, declared, documented, missing, phantom))

    return results, errors


def main():
    quiet = '--quiet' in sys.argv[1:]
    all_results, all_errors = [], []

    for md in sorted(glob.glob(os.path.join(DOC_ROOT, '**', '*.md'), recursive=True)):
        results, errors = check(md)
        all_results.extend(results)
        all_errors.extend(errors)

    drifted = [r for r in all_results if r[4] or r[5]]

    if not quiet:
        for rel_md, ref, declared, documented, missing, phantom in all_results:
            name = os.path.basename(ref).rsplit('.', 1)[0]
            if not missing and not phantom:
                print('ok     %s  <->  %s  %d/%d documented'
                      % (name, rel_md, len(documented), len(declared)))
                continue
            print('DRIFT  %s  <->  %s' % (name, rel_md))
            for c in missing:
                print('    undocumented  %s.%s  declared in %s, no row in the table'
                      % (name, c, ref))
            for c in phantom:
                print('    phantom       %s.%s  has a row but is not declared in %s'
                      % (name, c, ref))
        for e in all_errors:
            print('ERROR  %s' % e)

    print('\n%d inventor%s / %d in sync / %d drifted / %d error(s)'
          % (len(all_results), 'y' if len(all_results) == 1 else 'ies',
             len(all_results) - len(drifted), len(drifted), len(all_errors)))
    failed = bool(drifted or all_errors)
    print('FAIL' if failed else 'OK')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
