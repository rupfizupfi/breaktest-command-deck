// Shared helpers for script/loop/*.mjs: paths, CLI parsing, git, JSON output, globs.
// Node built-ins only, so every script runs from a fresh clone without an install.
import { spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const DECK_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
export const UMBRELLA = path.resolve(DECK_ROOT, '..');
/**
 * The backlog lives in the umbrella, outside every git repo (owner, 2026-10-14): item files are
 * never staged or committed, and a commit's sha is written into its items after the fact.
 * `--backlog-dir <dir>` (any script) or DECK_BACKLOG_DIR points the scripts at another copy.
 */
function backlogDirArg(argv = process.argv.slice(2)) {
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith('--backlog-dir=')) return argv[i].slice('--backlog-dir='.length);
    if (argv[i] === '--backlog-dir' && argv[i + 1] && !argv[i + 1].startsWith('--')) return argv[i + 1];
  }
  return process.env.DECK_BACKLOG_DIR || null;
}
export const BACKLOG_DIR = path.resolve(backlogDirArg() ?? path.join(UMBRELLA, 'doc', '@work', 'backlog'));
export const ITEMS_DIR = path.join(BACKLOG_DIR, 'items');
export const IS_WIN = process.platform === 'win32';
export const DRIVER_REPOS = ['dscusb', 'usbmodbus'];

/** The deck repo, or a sibling driver repo by name. */
export function repoRoot(name) {
  if (!name || name === true || name === 'deck') return DECK_ROOT;
  if (!DRIVER_REPOS.includes(name)) throw new Error(`unknown repo ${name}; known: deck, ${DRIVER_REPOS.join(', ')}`);
  return path.join(UMBRELLA, 'driver', name);
}

/**
 * Unit form: an item's files (and a mixed unit's paths) are deck-relative, a driver repo's written
 * `../driver/<repo>/<path>`. A unit may span the deck and driver repos (owner, 2026-10-18): each
 * repo gets its own snapshot, capture, tree hash and commit, the deck first.
 */
const DRIVER_PATH = /^\.\.\/driver\/([^/]+)\/(.*)$/;
export const REPO_ORDER = ['deck', ...DRIVER_REPOS];
export const repoSort = (repos) => [...new Set(repos)].sort((x, y) => REPO_ORDER.indexOf(x) - REPO_ORDER.indexOf(y));
export const toUnitPath = (repo, p) => (!repo || repo === 'deck' ? toPosix(p) : `../driver/${repo}/${toPosix(p)}`);

/** Unit-form paths or globs -> {repo: [repo-relative]}, repos in commit order (deck first). */
export function splitByRepo(paths) {
  const out = {};
  for (const p of paths.map(toPosix)) {
    const m = DRIVER_PATH.exec(p);
    const [repo, rel] = m ? [m[1], m[2]] : ['deck', p];
    (out[repo] ??= []).push(rel);
  }
  return Object.fromEntries(repoSort(Object.keys(out)).map((r) => [r, out[r]]));
}

/** The repos a unit's files name, deck first (a unit with no files is the deck's). */
export const unitRepos = (globs) => { const r = Object.keys(splitByRepo(globs)); return r.length ? r : ['deck']; };

/**
 * `--paths` with an optional `--repo`, per repo. A driver `--repo` with plain paths is the
 * single-repo form (paths relative to that repo); otherwise driver paths carry their
 * `../driver/<repo>/` prefix and the rest are the deck's.
 */
export function pathsByRepo(paths, repo = null) {
  const r = repo && repo !== true ? String(repo) : 'deck';
  if (!paths.length) return { [r]: [] };
  const split = splitByRepo(paths);
  const drivers = Object.keys(split).filter((x) => x !== 'deck');
  if (r === 'deck') return split;
  if (!drivers.length) return { [r]: paths.map(toPosix) };
  if (drivers.some((x) => x !== r) || split.deck) throw new Error(`--repo ${r} with paths in other repos (${Object.keys(split).join(', ')}): drop --repo and give unit-form paths`);
  return split;
}

/** Paths no loop step may change: generated output, and the loop's own graders. */
export const PROTECTED = [
  '**/src/main/frontend/generated/**', '**/vite.generated.ts',
  // The loop's own graders: an implementer that edits them is grading itself.
  'script/loop/**', 'doc/_check-size.py', 'doc/_check-diagrams.py', 'doc/_check-inventories.py', 'doc/_verify-refs.mjs',
  'drivers/**',
];

// Git Bash exports this; with it set, cmd.exe refuses to run gradlew.bat from the current dir.
delete process.env.NoDefaultCurrentDirectoryInExePath;

/** `--k v`, `--k=v`, bare `--flag` (true); repeated keys collect into arrays. */
export function parseArgs(argv = process.argv.slice(2)) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith('--')) { out._.push(a); continue; }
    let key = a.slice(2), val;
    const eq = key.indexOf('=');
    if (eq >= 0) { val = key.slice(eq + 1); key = key.slice(0, eq); }
    else if (i + 1 < argv.length && !argv[i + 1].startsWith('--')) val = argv[++i];
    else val = true;
    if (key in out) out[key] = [].concat(out[key], val);
    else out[key] = val;
  }
  return out;
}

export const list = (v) => (v === undefined || v === true ? [] : [].concat(v).flatMap((s) => String(s).split(',')).map((s) => s.trim()).filter(Boolean));

/** Prints one JSON object and exits: 0 ok, 1 failed, 2 usage/locked. */
export function finish(obj, code = 0) {
  process.stdout.write(JSON.stringify(obj, null, 2) + '\n');
  process.exit(code);
}

export function fail(message, code = 2, extra = {}) {
  finish({ ok: false, error: message, ...extra }, code);
}

export function run(cmd, args, opts = {}) {
  const r = spawnSync(cmd, args, { encoding: 'utf8', maxBuffer: 256 * 1024 * 1024, windowsHide: true, ...opts });
  return { code: r.status ?? (r.error ? 127 : 1), stdout: r.stdout ?? '', stderr: r.stderr ?? '', error: r.error };
}

export function git(cwd, args, opts = {}) {
  return run('git', ['-C', cwd, ...args], opts);
}

export function gitOk(cwd, args, opts = {}) {
  const r = git(cwd, args, opts);
  if (r.code !== 0) throw new Error(`git ${args.join(' ')} failed: ${r.stderr.trim() || r.stdout.trim()}`);
  return r.stdout;
}

export const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');

export function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8').replace(/^﻿/, ''));
}

export function writeJson(file, obj) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(obj, null, 2) + '\n');
}

export const today = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

export const stampNow = () => {
  const d = new Date(), p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
};

export function toPosix(p) { return p.replace(/\\/g, '/'); }

/** Glob → RegExp: `**` any depth, `*` one segment, `?` one char. Paths are repo-relative, posix. */
export function globToRegExp(glob) {
  let re = '';
  const g = toPosix(glob);
  for (let i = 0; i < g.length; i++) {
    const c = g[i];
    if (c === '*') {
      if (g[i + 1] === '*') { re += g[i + 2] === '/' ? '(?:.*/)?' : '.*'; i += g[i + 2] === '/' ? 2 : 1; }
      else re += '[^/]*';
    } else if (c === '?') re += '[^/]';
    else re += c.replace(/[.+^${}()|[\]\\]/g, '\\$&');
  }
  return new RegExp(`^${re}(?:/.*)?$`);
}

/** The literal prefix of a glob, up to its first wildcard. */
export const staticPrefix = (glob) => toPosix(glob).split(/[*?[]/)[0];

/** Two globs may name a common file when one literal prefix contains the other. */
export function globsOverlap(a, b) {
  const pa = staticPrefix(a), pb = staticPrefix(b);
  return pa.startsWith(pb) || pb.startsWith(pa);
}

export function pathMatchesGlob(p, glob) {
  const pp = toPosix(p).replace(/\/$/, '');
  if (globToRegExp(glob).test(pp)) return true;
  // A dirty directory (untracked dir shown as `dir/`) conflicts with any glob below it.
  return staticPrefix(glob).startsWith(pp + '/');
}

/**
 * Repo-relative paths whose working-tree content differs from HEAD, plus untracked files. Compares
 * content (`git diff HEAD`), not stat info, so a racy or CRLF-only status entry is not "dirty".
 */
export function dirtyPaths(tree, { exclude = [] } = {}) {
  const changed = gitOk(tree, ['diff', '--no-renames', '--name-only', '-z', 'HEAD']).split('\0').filter(Boolean);
  const untracked = gitOk(tree, ['ls-files', '--others', '--exclude-standard', '-z']).split('\0').filter(Boolean);
  return [...new Set([...changed, ...untracked])].map(toPosix).filter((p) => !exclude.some((g) => pathMatchesGlob(p, g)));
}

/** The tree a gate run saw: HEAD plus the content of every dirty path (or of `paths` when given). */
export function treeHash(tree, paths = null) {
  const head = gitOk(tree, ['rev-parse', 'HEAD']).trim();
  const scope = paths && paths.length ? paths : dirtyPaths(tree);
  const h = crypto.createHash('sha256').update(head + '\n');
  for (const p of [...new Set(scope.map(toPosix))].sort()) {
    const abs = path.join(tree, p);
    let content = '<absent>';
    try {
      const st = fs.statSync(abs);
      content = st.isFile() ? sha256(fs.readFileSync(abs)) : '<dir>';
    } catch {}
    h.update(`${p}\0${content}\n`);
  }
  return { head, hash: h.digest('hex'), scope: paths && paths.length ? 'paths' : 'dirty' };
}

/** Repo-relative path of an absolute or relative path under `tree`. */
export function relTo(tree, p) {
  return toPosix(path.relative(tree, path.resolve(tree, p)));
}

/**
 * The gates a change to these paths always needs, whatever an item lists (CLAUDE.md: the
 * applicable gates). commit-group.mjs requires them; run-gates.mjs --floor adds them.
 */
export function floorGates(paths, repo = 'deck') {
  if (repo && repo !== 'deck') return [`driver:${repo}`];
  const g = new Set();
  for (const p of paths.map(toPosix)) {
    if (p.startsWith('doc/')) { g.add('docs'); continue; }
    if (/\.(java|kt|kts|gradle|properties|sql)$/.test(p) || /^(cms|command-deck|device-api)\/src\//.test(p)) { g.add('jvm'); g.add('goldens'); }
    if (/\.(java|ts|tsx)$/.test(p) && /^(cms|command-deck)\//.test(p)) g.add('typecheck');
    if (/^cms\/.*\.(ts|tsx)$/.test(p)) g.add('vitest-cms');
    if (/^command-deck\/.*\.(ts|tsx)$/.test(p)) g.add('vitest-deck');
    if (/^device-api\//.test(p) || /\/device\//.test(p)) g.add('plugin');
    if (/(^|\/)(build\.gradle|settings\.gradle|gradle\.properties)$|^gradle\//.test(p)) { g.add('jvm'); g.add('typecheck'); g.add('plugin'); }
  }
  return [...g];
}

/** The floor over every repo of a {repo: [paths]} map (pathsByRepo): each driver repo -> driver:<repo>. */
export const floorGatesByRepo = (byRepo) => [...new Set(Object.entries(byRepo).flatMap(([r, ps]) => (r === 'deck' && !ps.length ? [] : floorGates(ps, r))))];
