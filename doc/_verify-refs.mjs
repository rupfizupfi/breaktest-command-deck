#!/usr/bin/env node
// doc/_verify-refs.mjs — reference verifier for doc/**.
//
// Scope, deliberately asymmetric:
//   - reads markdown ONLY from doc/ — no other source is scanned
//   - resolves every reference it finds against the WHOLE repo, from the repo
//     root, never relative to doc/
// So "doc/diagrams/src/x.mmd" is the in-convention form and a bare
// "diagrams/src/x.mmd" is shorthand, even though the latter resolves when read
// from inside doc/.
//
// Scans every .md under doc/ for references shaped like
//     module/path/File.ext
//     module/path/File.ext:NN
//     module/path/File.ext:NN-MM
// and reports any whose target is missing, or whose line number is past the end
// of the file. Replaces the PowerShell version, whose regex excluded a leading
// backtick and therefore skipped nearly every reference in the doc set (it
// checked 6 of them) — refs here are conventionally written inside backticks.
//
//   node doc/_verify-refs.mjs            # fail on broken refs only
//   node doc/_verify-refs.mjs --strict   # also fail on unverifiable shorthand
//   node doc/_verify-refs.mjs --quiet    # summary lines only
//
// Exit code 1 on failure, 0 on success.
//
// Deliberate limits: a reference with no "/" (root files such as `build.gradle`
// or `.gitignore:34`) is not checked, and a line number is only range-checked,
// never matched against content.
//
// Second pass — Markdown links. Every inline link `[label](target)` and every
// reference definition `[id]: target` whose target is relative (not a URL, not
// rooted) is resolved against the linking file's own directory, the way a
// renderer does:
//   - the target must exist (file or directory) and stay inside this repo — a
//     link that climbs out of the repo points at a sibling checkout or the
//     umbrella workspace, which a fresh clone does not have, so it is BROKEN;
//     cite an id (OQ-n, BL-nnn) instead
//   - a "#anchor" on a .md target (or a bare "#anchor" in the same file) must
//     match a heading under GitHub's slug rule (the one _check-size.py uses for
//     TOC links) or an explicit <a id|name="..."> in that file
// Links inside fenced blocks and inline code spans are not links and are skipped.

import { readdirSync, readFileSync, statSync, existsSync } from 'node:fs';
import { join, relative, resolve, extname, sep } from 'node:path';

const docRoot = resolve(import.meta.dirname);
const repoRoot = resolve(docRoot, '..');
const args = new Set(process.argv.slice(2));
const strict = args.has('--strict');
const quiet = args.has('--quiet');

// Extensions treated as code/config references. Not .md: doc-to-doc links are
// relative Markdown links, checked by the second pass below.
const CODE_EXT = new Set([
  '.java', '.kt', '.ts', '.tsx', '.js', '.mjs', '.cjs', '.json', '.gradle',
  '.properties', '.yaml', '.yml', '.sh', '.ps1', '.py', '.bat', '.sql', '.css',
  '.txt', '.mmd', '.puml', '.svg', '.html', '.p12', '.jar', '.dump', '.png',
  '.dockerfile', '.config', '.toml', '.lock',
]);

// The repo's convention is a path rooted at a real top-level entry. Read them
// from disk so a new module needs no edit here. Anything outside this set is
// shorthand ("views/@layout.tsx:4") or a file a design doc proposes but has not
// created — unverifiable, not necessarily wrong.
const REPO_ROOTS = new Set(
  readdirSync(repoRoot, { withFileTypes: true })
    .filter((e) => e.isDirectory())
    .map((e) => e.name),
);

// Three shapes are established in the doc set and are NOT drift, so they are
// reported as advisory rather than failing the gate:
//   - "command-deck/.../testrunner/LoadCellThread.java:15" — the "..." elision
//   - "cms/util/auth.ts" — a module specifier resolved through the Vite 'cms'
//     alias, never a path on disk
//   - "src/main/frontend/generated/file-routes.json" — deliberately module-agnostic
// The first is detected by the literal "...". The other two share a structural
// tell: the file is missing AND so is its parent directory, so nothing was ever
// renamed out from under the reference. A missing file whose parent directory
// DOES exist is real drift.
const isElided = (ref) => ref.includes('...');
const parentExists = (ref) => {
  const parent = ref.slice(0, ref.lastIndexOf('/'));
  const abs = join(repoRoot, parent);
  return parent !== '' && existsSync(abs) && statSync(abs).isDirectory();
};

// A path-ish token with at least one "/", ending in .ext, optional :NN or :NN-MM.
// The lookbehind rejects a match that starts mid-path — without it "../diagrams/x.mmd"
// matches as "diagrams/x.mmd" and a rendered relative link is reported as a defect.
// Note what is NOT in it: the backtick. The PowerShell original excluded a leading
// backtick and so skipped nearly every reference in the doc set, which is the bug
// this script exists to fix. Backticks, quotes, parens and pipes are stripped as
// delimiters below instead.
const REF = /(?<![A-Za-z0-9_./\-])([A-Za-z0-9_][A-Za-z0-9_.\-/]*\/[A-Za-z0-9_.\-/]*\.[A-Za-z0-9]{1,10})(?::(\d+)(?:-(\d+))?)?/g;

const broken = [];
const shorthand = [];
const seen = new Set();
let checked = 0;
let files = 0;

function markdownFiles(dir) {
  const out = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...markdownFiles(full));
    else if (entry.name.endsWith('.md')) out.push(full);
  }
  return out;
}

function lineCount(absPath) {
  const text = readFileSync(absPath, 'utf8');
  if (text === '') return 0;
  const n = text.split('\n').length;
  // A trailing newline does not start a line.
  return text.endsWith('\n') ? n - 1 : n;
}

for (const mdPath of markdownFiles(docRoot)) {
  files++;
  const relMd = relative(repoRoot, mdPath).split(sep).join('/');
  const lines = readFileSync(mdPath, 'utf8').split(/\r?\n/);

  // Fenced blocks hold Mermaid labels, import statements and shell snippets —
  // path-shaped, but not claims about this repo's files.
  let inFence = false;

  lines.forEach((line, i) => {
    if (/^\s*(```|~~~)/.test(line)) {
      inFence = !inFence;
      return;
    }
    if (inFence) return;

    // Inline code spans are kept (that is where real refs live); only the
    // delimiters go, so the regex sees a clean token.
    const scrubbed = line.replace(/[`"'|]/g, ' ');

    for (const m of scrubbed.matchAll(REF)) {
      let ref = m[1];
      const startLine = m[2] ? Number(m[2]) : null;
      const endLine = m[3] ? Number(m[3]) : null;

      // Trailing sentence punctuation that survived the character class.
      ref = ref.replace(/[.,;:)\]]+$/, '');

      if (/^(https?:|mailto:)/.test(ref)) continue;
      if (ref.startsWith('@')) continue;           // npm scoped specifier
      if (ref.startsWith('./') || ref.startsWith('../') || ref.startsWith('/')) continue;
      if (!ref.includes('/')) continue;

      // Dockerfile carries no extension but is referenced with line ranges.
      const ext = extname(ref).toLowerCase();
      const isDockerfile = ref.endsWith('/Dockerfile');
      if (!isDockerfile && !CODE_EXT.has(ext)) continue;

      const location = { doc: relMd, line: i + 1, ref: startLine ? `${ref}:${m[2]}${m[3] ? `-${m[3]}` : ''}` : ref };

      // A markdown link repeats its target in label and href, so one line can
      // yield the same reference twice. Report it once.
      const dedupKey = `${relMd}:${i + 1}:${location.ref}`;
      if (seen.has(dedupKey)) continue;
      seen.add(dedupKey);

      if (isElided(ref)) {
        shorthand.push({ ...location, why: 'abbreviated with "..."' });
        continue;
      }

      // Always resolved against the repo root, never against doc/: a reference is
      // a claim about a path in this repository, so "doc/diagrams/src/x.mmd" is
      // the in-convention form and a bare "diagrams/src/x.mmd" is shorthand.
      const top = ref.split('/')[0];
      const candidate = join(repoRoot, ref);
      const abs = existsSync(candidate) && statSync(candidate).isFile() ? candidate : null;

      if (!abs) {
        if (!REPO_ROOTS.has(top)) {
          shorthand.push({ ...location, why: `"${top}/" is not a repo top-level directory` });
        } else if (!parentExists(ref)) {
          shorthand.push({ ...location, why: 'neither the file nor its parent directory exists — module specifier or abbreviation' });
        } else {
          checked++;
          broken.push({ ...location, why: 'file does not exist' });
        }
        continue;
      }

      checked++;
      if (startLine !== null) {
        const total = lineCount(abs);
        const highest = endLine ?? startLine;
        if (highest > total) {
          broken.push({ ...location, why: `line ${highest} past end of file (${total} lines)` });
        }
      }
    }
  });
}

// ---- Second pass: relative Markdown links and their anchors -----------------

// GitHub's heading -> anchor transform, identical to _check-size.py's slug():
// lowercase, drop backticks/asterisks, drop everything but [a-z0-9 _-], spaces
// to hyphens (not collapsed, so an em-dash between words yields "--").
const slug = (heading) =>
  heading.trim().toLowerCase().replace(/[`*]/g, '').replace(/[^a-z0-9 \-_]/g, '').replace(/ /g, '-');

const anchorCache = new Map();
function anchorsOf(absMd) {
  if (anchorCache.has(absMd)) return anchorCache.get(absMd);
  const anchors = new Set();
  const seenSlugs = new Map();
  let fenced = false;
  for (const line of readFileSync(absMd, 'utf8').split(/\r?\n/)) {
    if (/^\s*(```|~~~)/.test(line)) { fenced = !fenced; continue; }
    if (fenced) continue;
    const h = line.match(/^#{1,6}\s+(.+?)\s*#*\s*$/);
    if (h) {
      // A heading's own link syntax renders as its label.
      const base = slug(h[1].replace(/\[([^\]]*)\]\([^)]*\)/g, '$1'));
      const n = seenSlugs.get(base) ?? 0;
      seenSlugs.set(base, n + 1);
      anchors.add(n === 0 ? base : `${base}-${n}`);
    }
    for (const a of line.matchAll(/<a\s+(?:id|name)=["']([^"']+)["']/g)) anchors.add(a[1]);
  }
  anchorCache.set(absMd, anchors);
  return anchors;
}

const INLINE_LINK = /\]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)/g;
const REF_DEF = /^\s{0,3}\[[^\]]+\]:\s+<?(\S+?)>?(?:\s+"[^"]*")?\s*$/;
let linksChecked = 0;

for (const mdPath of markdownFiles(docRoot)) {
  const relMd = relative(repoRoot, mdPath).split(sep).join('/');
  const lines = readFileSync(mdPath, 'utf8').split(/\r?\n/);
  let inFence = false;
  lines.forEach((line, i) => {
    if (/^\s*(```|~~~)/.test(line)) { inFence = !inFence; return; }
    if (inFence) return;
    const code = line.replace(/`[^`]*`/g, '');
    const targets = [...code.matchAll(INLINE_LINK)].map((m) => m[1]);
    const def = code.match(REF_DEF);
    if (def) targets.push(def[1]);

    for (const raw of targets) {
      if (/^[a-z][a-z0-9+.\-]*:/i.test(raw)) continue;  // https:, mailto:, …
      if (raw.startsWith('/')) continue;                 // site-rooted, not a file link
      const hashAt = raw.indexOf('#');
      const pathPart = decodeURI(hashAt === -1 ? raw : raw.slice(0, hashAt));
      const anchor = hashAt === -1 ? null : decodeURIComponent(raw.slice(hashAt + 1));
      const location = { doc: relMd, line: i + 1, ref: raw };
      linksChecked++;

      const target = pathPart === '' ? mdPath : resolve(mdPath, '..', pathPart);
      const relTarget = relative(repoRoot, target);
      if (relTarget.startsWith('..') || resolve(relTarget) === relTarget) {
        broken.push({ ...location, why: 'link leaves the repo — cite an id (OQ-n, BL-nnn) instead' });
        continue;
      }
      if (!existsSync(target)) {
        broken.push({ ...location, why: 'link target does not exist' });
        continue;
      }
      if (anchor !== null && anchor !== '' && target.endsWith('.md') && statSync(target).isFile()) {
        if (!anchorsOf(target).has(anchor)) {
          broken.push({ ...location, why: `no heading or anchor "#${anchor}" in ${relTarget.split(sep).join('/')}` });
        }
      }
    }
  });
}

function report(rows, heading) {
  console.log(`\n${heading}`);
  const width = Math.max(...rows.map((r) => `${r.doc}:${r.line}`.length));
  for (const r of rows) {
    console.log(`  ${`${r.doc}:${r.line}`.padEnd(width)}  ${r.ref}  — ${r.why}`);
  }
}

console.log(`Checked ${checked} in-convention reference(s) and ${linksChecked} relative Markdown link(s) across ${files} markdown files.`);
if (shorthand.length) {
  console.log(
    `${shorthand.length} reference(s) are shorthand and cannot be resolved` +
      `${strict ? ' (failing: --strict)' : ' (advisory)'}.`,
  );
}

if (broken.length && !quiet) report(broken, `BROKEN (${broken.length}):`);
if (shorthand.length && !quiet) report(shorthand, `UNVERIFIABLE SHORTHAND (${shorthand.length}):`);

if (broken.length === 0 && (!strict || shorthand.length === 0)) {
  console.log('\nOK: no broken references found.');
  process.exit(0);
}
process.exit(1);
