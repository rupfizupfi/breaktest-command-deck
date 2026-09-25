#!/usr/bin/env python3
"""Keep inline ```mermaid fences in sync with their doc/diagrams/src/*.mmd source.

  python doc/_check-diagrams.py            # exit 1 on drift
  python doc/_check-diagrams.py --quiet    # summary only

The third gate, beside _check-size.py and _verify-refs.mjs, and the only one
that can see inside a fenced block: _verify-refs.mjs skips fences wholesale so
that Mermaid node labels aren't mistaken for file references. That blind spot is
exactly where a diagram body lives, so a doc's copy of a diagram can drift from
its .mmd source with both existing gates green.

The doc model says every fact has one home. A diagram inlined for rendering AND
kept as .mmd source has two, so the duplication is deliberate — GitHub renders
the fence, the .mmd stays editable. This script is the price of that choice: it
holds the two copies to the same content.

Verdicts, per (source, doc) pair:
  DRIFT       both carry the diagram and they disagree            -> FAIL
  ABBREVIATED the fence is a strict, faithful subsequence of the
              source (a summary view, e.g. an ER diagram's
              relationships without the attribute blocks)         -> advisory
  IDENTICAL   in sync                                             -> ok
  ORPHAN      a .mmd no doc links                                 -> advisory

Matching a fence to a source needs two tests, not one. Similarity alone misses
the summary case: a faithful 14-line digest of a 104-line ER diagram scores far
below MATCH_FLOOR, so a floor-only check would silently skip the very pairs most
likely to drift a fact. So COVERAGE — the share of fence lines that appear
verbatim in the source — is tried first and decides the summary cases; the
similarity floor only catches whole-diagram copies. A fence that is a complete
subsequence is an intentional digest; one that nearly is, but drops or alters a
line, is a digest that lost a fact, and fails.
"""
import difflib
import glob
import os
import re
import sys

# Below this ratio a same-length fence is a different diagram, not a drifted copy.
MATCH_FLOOR = 0.5

# A fence sharing at least this share of its lines with the source is a digest of
# it. Below it, the two are unrelated diagrams.
COVERAGE_FLOOR = 0.6

SRC_DIR = 'doc/diagrams/src'


def norm(text):
    """Comparable lines: no %% comments (the .mmd carries provenance headers
    that the fence has no place for), no blanks, no trailing space."""
    out = []
    for line in text.split('\n'):
        s = line.strip()
        if not s or s.startswith('%%'):
            continue
        out.append(line.rstrip())
    return out


def fences(text):
    return [norm(f) for f in re.findall(r'```mermaid\n(.*?)```', text, re.S)]


def is_subsequence(small, big):
    """Every line of `small`, in order, appears in `big`. A faithful summary."""
    it = iter(big)
    return all(line in it for line in small)


def main():
    quiet = '--quiet' in sys.argv[1:]
    sources = sorted(glob.glob(os.path.join(SRC_DIR, '*.mmd')))
    docs = sorted(glob.glob('doc/**/*.md', recursive=True))

    linked = {os.path.basename(p): [] for p in sources}
    doc_text = {}
    for d in docs:
        text = open(d, encoding='utf-8').read()
        doc_text[d] = text
        for name in set(re.findall(r'diagrams/src/([A-Za-z0-9\-_]+\.mmd)', text)):
            if name in linked:
                linked[name].append(d)

    drift, abbrev, identical, orphan = [], [], [], []

    for src_path in sources:
        name = os.path.basename(src_path)
        src = norm(open(src_path, encoding='utf-8').read())
        if not linked[name]:
            orphan.append(name)
            continue
        for d in sorted(linked[name]):
            cands = fences(doc_text[d])
            if not cands:
                continue
            # Rank by coverage, falling back to similarity: a digest scores low
            # on similarity purely because it is shorter.
            best, ratio, cover = None, -1.0, 0.0
            for f in cands:
                c = sum(1 for line in f if line in src) / len(f) if f else 0.0
                r = difflib.SequenceMatcher(None, src, f).ratio()
                if (c, r) > (cover, ratio):
                    best, ratio, cover = f, r, c
            if best is None or (cover < COVERAGE_FLOOR and ratio < MATCH_FLOOR):
                continue  # doc links this .mmd but inlines a different diagram
            rel = d.replace('\\', '/')
            if src == best:
                identical.append((name, rel))
            elif is_subsequence(best, src):
                abbrev.append((name, rel, len(best), len(src)))
            else:
                # What the fence asserts and the source does not is always the
                # actionable half. The reverse is mostly a digest's intentional
                # omissions, so it is capped rather than dumped.
                added = [l for l in best if l not in src]
                dropped = [l for l in src if l not in best]
                drift.append((name, rel, added, dropped))

    if not quiet:
        for name, rel, added, dropped in drift:
            print('DRIFT  %s  <->  %s' % (name, rel))
            for line in added:
                print('    doc only  %s' % line.strip())
            shown = dropped if len(dropped) <= 8 else dropped[:8]
            for line in shown:
                print('    src only  %s' % line.strip())
            if len(dropped) > len(shown):
                print('    src only  ... and %d more source line(s) absent from '
                      'the fence' % (len(dropped) - len(shown)))
        for name, rel, n, total in abbrev:
            print('note   %s  <->  %s  fence is a %d/%d-line summary (advisory)'
                  % (name, rel, n, total))
        for name in orphan:
            print('note   %s  linked by no doc (advisory)' % name)

    print('\n%d in sync / %d drifted / %d summarised / %d orphan'
          % (len(identical), len(drift), len(abbrev), len(orphan)))
    print('FAIL' if drift else 'OK')
    return 1 if drift else 0


if __name__ == '__main__':
    sys.exit(main())
