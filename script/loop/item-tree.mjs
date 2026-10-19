#!/usr/bin/env node
// Tracks what one work unit changed in the main tree, so a failed unit can be undone without
// touching anything it did not write. The loop works in the main tree, one unit at a time, with no
// worktrees (owner, 2026-10-13).
//
//   every command takes [--repo deck|dscusb|usbmodbus]: snapshot defaults to deck; capture, revert
//   and reapply default to the repo their snapshot/capture recorded and refuse a --repo that differs.
//   A unit spanning several repos (files in the deck and in ../driver/<repo>/) runs each command once
//   per repo, deck first, with its own snapshot and capture files (owner, 2026-10-18).
//   snapshot --unit <U> --out <snap.json> [--files glob,..] [--foreign <json|auto>]
//       before the implementer starts: HEAD plus the content hash of every dirty path. `clean` is
//       false when a dirty path (someone else's work) overlaps the unit's files. --files takes the
//       unit's globs in unit form (lib.mjs splitByRepo); the snapshot keeps this repo's, repo-relative
//       (`unitFiles` keeps the full list when that differs).
//   capture --snapshot <snap.json> --out <cap.json>
//       after the implementer: every path whose content differs from the snapshot -> a binary
//       patch of the tracked ones plus copies of the new files. Flags
//       touchedForeign (a path that was already dirty: never reverted, a guard), protectedTouched,
//       goldensTouched and devDbTouched. Records `repo`, and `unitPaths` = `files` in unit form.
//   revert --capture <cap.json> [--paths a,b]
//       undo the unit's own changes, or only `--paths` (one member's separable files; unit-form
//       paths of other repos are ignored, so one list serves every repo's revert): saves the
//       current diff first, then `git apply -R` for tracked files and deletes the files the unit
//       created. Never checkout/restore/stash/reset. A path back at HEAD's content is rewritten in
//       its checked-out line-ending form (`eolRestored`) so `git status` shows it clean. Verifies
//       each path is back to HEAD.
//   reapply --capture <cap.json>
//       put a saved unit back (resume after a budget or lock stop): `git apply` plus the new files,
//       each tracked file restored to the exact bytes captured (`eolRestored` lists line-ending fixes).
//
// Exit 0 ok, 1 refused or verification failed, 2 usage.
import fs from 'node:fs';
import path from 'node:path';
import { DECK_ROOT, PROTECTED, repoRoot, dirtyPaths, fail, finish, git, list, parseArgs, pathMatchesGlob, readJson, sha256, splitByRepo, toPosix, toUnitPath, writeJson } from './lib.mjs';

const GOLDENS = 'command-deck/src/test/resources/ch/rupfizupfi/deck/testrunner/goldens/**';
const DEV_DB = path.join(DECK_ROOT, '.data', 'deck.mv.db');

const a = parseArgs();
const [cmd] = a._;
// The repo the input file was taken in (a capture without `repo` predates it: ask its snapshot).
function recordedRepo() {
  try {
    if (cmd === 'capture' && a.snapshot) return readJson(a.snapshot).repo;
    if ((cmd === 'revert' || cmd === 'reapply') && a.capture) {
      const c = readJson(a.capture);
      return c.repo ?? (c.snapshot && fs.existsSync(c.snapshot) ? readJson(c.snapshot).repo : undefined);
    }
  } catch {}
  return undefined;
}
const asked = a.repo && a.repo !== true ? String(a.repo) : null;
const recorded = recordedRepo();
if (asked && recorded && asked !== recorded) fail(`--repo ${asked}, but ${a.snapshot ?? a.capture} was taken in repo ${recorded}`);
// --repo dscusb|usbmodbus tracks a driver repo instead of the deck.
const REPO = asked ?? recorded ?? 'deck';
const ROOT = repoRoot(REPO);

const hashOf = (p) => {
  try { const st = fs.statSync(path.join(ROOT, p)); return st.isFile() ? sha256(fs.readFileSync(path.join(ROOT, p))) : '<dir>'; } catch { return '<absent>'; }
};
const isCrlf = (p) => { try { return fs.readFileSync(path.join(ROOT, p)).includes('\r\n'); } catch { return false; } };
const mtime = (f) => { try { return fs.statSync(f).mtimeMs; } catch { return null; } };
const dirtyMap = () => Object.fromEntries(dirtyPaths(ROOT).map((p) => [toPosix(p), hashOf(p)]));
const inHead = (p) => git(ROOT, ['cat-file', '-e', `HEAD:${p}`]).code === 0;

// Git takes a size that differs from the index's stat record as "modified" without comparing
// content, so a file whose content is HEAD's but whose line endings are not the checked-out form
// stays ` M` ("needs update") under autocrlf=true. For each of the unit's own paths whose cleaned
// content is HEAD's blob, rewrite it in the form whose size the index recorded (the smudged
// checkout form, else the raw blob), then refresh stat info. Stages nothing.
function restoreCheckoutForm(paths) {
  const restored = [];
  const atHead = paths.filter((p) => {
    if (!fs.existsSync(path.join(ROOT, p))) return false;
    const blob = git(ROOT, ['rev-parse', `HEAD:${p}`]);
    return blob.code === 0 && git(ROOT, ['hash-object', '--', p]).stdout.trim() === blob.stdout.trim();
  });
  for (const p of atHead) {
    const f = path.join(ROOT, p);
    const size = Number(/size: (\d+)/.exec(git(ROOT, ['ls-files', '--debug', '--', p]).stdout)?.[1] ?? 0);
    if (fs.statSync(f).size === size) continue; // already the form the index recorded
    const smudged = git(ROOT, ['cat-file', '--filters', `HEAD:${p}`], { encoding: 'buffer' });
    if (smudged.code !== 0) continue;
    const raw = git(ROOT, ['cat-file', '-p', `HEAD:${p}`], { encoding: 'buffer' }).stdout;
    fs.writeFileSync(f, [smudged.stdout, raw].find((b) => size && b.length === size) ?? smudged.stdout);
    restored.push(p);
  }
  if (atHead.length) git(ROOT, ['update-index', '-q', '--refresh']); // stat refresh only; exit 1 just means other paths are dirty
  return restored;
}

function removeEmptyDirs(rel) {
  let dir = path.dirname(path.join(ROOT, rel));
  while (path.resolve(dir) !== path.resolve(ROOT)) {
    try { if (fs.readdirSync(dir).length) break; fs.rmdirSync(dir); } catch { break; }
    dir = path.dirname(dir);
  }
}

function snapshot() {
  if (!a.out) fail('usage: snapshot --unit <U> --out <snap.json> [--files globs] [--foreign json|auto]');
  const unitFiles = list(a.files);
  // This repo's globs, repo-relative. A driver --repo with only plain globs is the single-repo form.
  const split = splitByRepo(unitFiles);
  const files = REPO !== 'deck' && !Object.keys(split).some((r) => r !== 'deck') ? unitFiles : (split[REPO] ?? []);
  const dirty = dirtyMap();
  const known = a.foreign === 'auto' || !a.foreign ? null : (fs.existsSync(a.foreign) ? readJson(a.foreign) : JSON.parse(a.foreign));
  const overlap = Object.keys(dirty).filter((p) => files.some((g) => pathMatchesGlob(p, g)));
  const snap = {
    repo: REPO, unit: a.unit ?? null, head: git(ROOT, ['rev-parse', 'HEAD']).stdout.trim(), takenAt: new Date().toISOString(),
    dirty, files, ...(files.join(',') !== unitFiles.join(',') ? { unitFiles } : {}), devDbMtime: mtime(DEV_DB),
    foreignNew: known ? Object.keys(dirty).filter((p) => !known.includes(p)) : [],
    overlap, clean: overlap.length === 0,
  };
  writeJson(path.resolve(a.out), snap);
  finish({ ok: snap.clean, out: toPosix(path.resolve(a.out)), ...snap, dirty: Object.keys(dirty) }, snap.clean ? 0 : 1);
}

function captureNow(snap, outFile, only = null) {
  const now = dirtyMap();
  const head = git(ROOT, ['rev-parse', 'HEAD']).stdout.trim();
  // A path the snapshot saw as dirty belongs to someone else unless the unit changed it further.
  const changed = Object.keys(now).filter((p) => now[p] !== snap.dirty[p]);
  // A path dirty at snapshot and clean now was reverted by the unit (or its owner): report it.
  const vanished = Object.keys(snap.dirty).filter((p) => !(p in now));
  const scope = only ? changed.filter((p) => only.includes(p)) : changed;
  const touchedForeign = [...scope.filter((p) => p in snap.dirty), ...vanished];
  const own = scope.filter((p) => !(p in snap.dirty));
  const tracked = own.filter(inHead);
  const newFiles = own.filter((p) => !inHead(p) && now[p] !== '<dir>');
  const patch = outFile.replace(/\.json$/, '') + '.patch';
  const diff = tracked.length ? git(ROOT, ['diff', '--binary', 'HEAD', '--', ...tracked], { encoding: 'buffer' }).stdout : Buffer.alloc(0);
  fs.mkdirSync(path.dirname(patch), { recursive: true });
  fs.writeFileSync(patch, diff);
  const filesDir = outFile.replace(/\.json$/, '') + '.files';
  for (const p of newFiles) {
    const dst = path.join(filesDir, p);
    fs.mkdirSync(path.dirname(dst), { recursive: true });
    fs.copyFileSync(path.join(ROOT, p), dst);
  }
  return {
    repo: REPO, unit: snap.unit, snapshotHead: snap.head, head, headMoved: head !== snap.head, capturedAt: new Date().toISOString(),
    files: own, unitPaths: own.map((p) => toUnitPath(REPO, p)), tracked, newFiles, patch: toPosix(patch), filesDir: toPosix(filesDir), touchedForeign,
    protectedTouched: own.filter((p) => PROTECTED.some((g) => pathMatchesGlob(p, g))),
    goldensTouched: own.filter((p) => pathMatchesGlob(p, GOLDENS)),
    outsideFiles: snap.files.length ? own.filter((p) => !snap.files.some((g) => pathMatchesGlob(p, g))) : [],
    devDbTouched: mtime(DEV_DB) !== snap.devDbMtime,
    hashes: Object.fromEntries(own.map((p) => [p, now[p]])),
  };
}

function capture() {
  if (!a.snapshot || !a.out) fail('usage: capture --snapshot <snap.json> --out <cap.json>');
  const snap = readJson(a.snapshot);
  const out = path.resolve(a.out);
  const cap = { ...captureNow(snap, out), snapshot: toPosix(path.resolve(a.snapshot)) };
  writeJson(out, cap);
  const bad = cap.touchedForeign.length || cap.protectedTouched.length;
  finish({ ok: !bad, out: toPosix(out), ...cap }, bad ? 1 : 0);
}

function revert() {
  if (!a.capture) fail('usage: revert --capture <cap.json> [--paths a,b]');
  const cap = readJson(a.capture);
  const snap = readJson(cap.snapshot);
  // --paths in unit form names several repos' files: this run takes only this repo's.
  const asked = list(a.paths).map(toPosix);
  const split = splitByRepo(asked);
  const only = !asked.length ? null : REPO !== 'deck' && !Object.keys(split).some((r) => r !== 'deck') ? asked : (split[REPO] ?? []);
  if (only && !only.length) finish({ ok: true, reverted: [], method: {}, eolRestored: [], notBack: [], errors: [], note: `no --paths in repo ${REPO}` });
  // Re-capture first: the unit may have changed more since the verify-time capture.
  const save = path.resolve(a.capture).replace(/\.json$/, `.revert${only ? '-partial' : ''}.json`);
  const cur = captureNow(snap, save, only);
  writeJson(save, cur);
  if (cur.touchedForeign.length) {
    // Never revert a path that held someone else's work; leave all of it for the main session.
    finish({ ok: false, error: 'unit touched foreign dirty paths; nothing reverted', touchedForeign: cur.touchedForeign, saved: toPosix(save) }, 1);
  }
  const errors = [], method = {};
  if (cur.tracked.length) {
    // Each file goes back in the line-ending style it has now (the repo mixes LF and CRLF working
    // copies under autocrlf=true, and git apply would otherwise rewrite LF files as CRLF).
    const eol = Object.fromEntries(cur.tracked.map((p) => [p, isCrlf(p)]));
    for (const p of cur.tracked) {
      const r = git(ROOT, ['-c', 'core.autocrlf=false', 'apply', '-R', '--binary', '--whitespace=nowarn', `--include=${p}`, cur.patch]);
      if (r.code === 0) { method[p] = 'apply -R'; continue; }
      // A CRLF working copy does not take the LF-normalized patch: write HEAD's content for this
      // file of the unit's own instead (the patch is saved above either way).
      const blob = git(ROOT, ['show', `HEAD:${p}`], { encoding: 'buffer' });
      if (blob.code !== 0) { errors.push(`cannot read HEAD:${p}: ${r.stderr.trim()}`); continue; }
      fs.writeFileSync(path.join(ROOT, p), blob.stdout);
      method[p] = 'HEAD content (apply -R did not fit)';
    }
    for (const p of cur.tracked) {
      const f = path.join(ROOT, p);
      if (!fs.existsSync(f)) continue;
      const buf = fs.readFileSync(f);
      if (buf.includes(0)) continue; // binary: leave as written
      const text = buf.toString('utf8');
      const lf = text.replace(/\r\n/g, '\n');
      fs.writeFileSync(f, eol[p] ? lf.replace(/\n/g, '\r\n') : lf);
    }
  }
  if (!errors.length) for (const p of cur.newFiles) { fs.rmSync(path.join(ROOT, p), { force: true }); removeEmptyDirs(p); }
  const eolRestored = restoreCheckoutForm(cur.tracked);
  // Back means git sees no change against HEAD (content compare, so autocrlf does not trip it).
  const after = dirtyMap();
  const notBack = errors.length ? cur.files : cur.files.filter((p) => p in after);
  finish({ ok: !errors.length && !notBack.length, reverted: errors.length ? [] : cur.files, method, eolRestored, notBack, errors, saved: toPosix(save), patch: cur.patch }, errors.length || notBack.length ? 1 : 0);
}

function reapply() {
  if (!a.capture) fail('usage: reapply --capture <cap.json>');
  const cap = readJson(a.capture);
  const dirty = dirtyMap();
  const clash = cap.files.filter((p) => p in dirty);
  if (clash.length) finish({ ok: false, error: 'paths are dirty; refusing to reapply over them', clash }, 1);
  const errors = [];
  const eolRestored = [];
  if (cap.tracked.length && fs.statSync(cap.patch).size) {
    let r = git(ROOT, ['-c', 'core.autocrlf=false', 'apply', '--binary', '--whitespace=nowarn', cap.patch]);
    // A CRLF working copy (the checkout form, and what revert leaves) does not take the
    // LF-normalized patch raw; let git convert line endings on read and write instead.
    if (r.code !== 0) r = git(ROOT, ['apply', '--binary', '--whitespace=nowarn', cap.patch]);
    if (r.code !== 0) errors.push(`git apply failed (HEAD moved since ${cap.snapshotHead.slice(0, 7)}?): ${r.stderr.trim()}`);
    // Put each file back byte for byte in the line-ending style the unit left it in.
    else for (const p of cap.tracked) {
      const f = path.join(ROOT, p);
      if (!cap.hashes?.[p] || !fs.existsSync(f) || hashOf(p) === cap.hashes[p]) continue;
      const buf = fs.readFileSync(f);
      if (buf.includes(0)) continue;
      const lf = buf.toString('utf8').replace(/\r\n/g, '\n');
      const fit = [lf, lf.replace(/\n/g, '\r\n')].find((t) => sha256(Buffer.from(t, 'utf8')) === cap.hashes[p]);
      if (fit !== undefined) { fs.writeFileSync(f, fit); eolRestored.push(p); }
    }
  }
  if (!errors.length) for (const p of cap.newFiles) {
    const dst = path.join(ROOT, p);
    if (fs.existsSync(dst)) { errors.push(`${p} exists`); continue; }
    fs.mkdirSync(path.dirname(dst), { recursive: true });
    fs.copyFileSync(path.join(cap.filesDir, p), dst);
  }
  finish({ ok: !errors.length, applied: errors.length ? [] : cap.files, eolRestored, errors }, errors.length ? 1 : 0);
}

switch (cmd) {
  case 'snapshot': snapshot(); break;
  case 'capture': capture(); break;
  case 'revert': revert(); break;
  case 'reapply': reapply(); break;
  default: fail('usage: item-tree.mjs snapshot|capture|revert|reapply (see the header comment)');
}
