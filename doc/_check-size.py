#!/usr/bin/env python3
"""Enforce the doc size/TOC/anchor standard from doc/README.md.

  python doc/_check-size.py        # exits 1 on any violation

Thresholds (lines as `wc -l` counts them):
  <=120  target, no TOC required
  >120   must have a "## Contents" section
  >250   must split, or declare an inline exemption

Also validates that every in-page TOC link resolves to a real heading, using
GitHub's slug rules. Run from the repo root, alongside _verify-refs.mjs.
"""
import glob
import io
import re
import sys

EXEMPTIONS = (
    'Exempt from the 250-line split limit',
    'Over the 250-line split limit by design',
)


def slug(heading):
    """GitHub's heading -> anchor transform. Underscores survive; spaces are
    NOT collapsed, so an em-dash between words yields a double hyphen."""
    h = heading.strip().lower()
    h = re.sub(r'[`*]', '', h)
    h = re.sub(r'[^a-z0-9 \-_]', '', h)
    return h.replace(' ', '-')


def headings(text):
    """H2-H4 outside fenced code blocks, in document order."""
    out, fenced = [], False
    for line in text.split('\n'):
        if line.lstrip().startswith('```'):
            fenced = not fenced
            continue
        if fenced:
            continue
        m = re.match(r'^(#{2,4}) (.+)$', line)
        if m:
            out.append(m.group(2).strip())
    return out


def check(path):
    problems = []
    text = io.open(path, encoding='utf-8').read()
    lines = text.count('\n')
    exempt = any(e in text for e in EXEMPTIONS)
    has_toc = bool(re.search(r'^## Contents$', text, re.M))

    if lines > 250 and not exempt:
        problems.append(
            '%d lines: split it, or state the exemption inline' % lines)
    elif 120 < lines <= 250 and not has_toc:
        problems.append('%d lines: needs a "## Contents" section' % lines)

    seen, anchors = {}, set()
    for h in headings(text):
        base = slug(h)
        n = seen.get(base, 0)
        seen[base] = n + 1
        anchors.add(base if n == 0 else '%s-%d' % (base, n))
    for target in re.findall(r'^\s*[-*] \[.+?\]\(#(.+?)\)\s*$', text, re.M):
        if target not in anchors:
            problems.append('TOC link #%s matches no heading' % target)

    return lines, problems


def main():
    failed = False
    buckets = {'ok': 0, 'toc': 0, 'exempt': 0}
    for path in sorted(glob.glob('doc/**/*.md', recursive=True)):
        lines, problems = check(path)
        if lines > 250:
            buckets['exempt'] += 1
        elif lines > 120:
            buckets['toc'] += 1
        else:
            buckets['ok'] += 1
        for p in problems:
            print('%s: %s' % (path.replace('\\', '/'), p))
            failed = True
    print('\n%d target / %d with TOC / %d over-250 by exemption'
          % (buckets['ok'], buckets['toc'], buckets['exempt']))
    print('FAIL' if failed else 'OK')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
