#!/usr/bin/env node
// One runner for the verification gates, sequential, one JSON result.
//
//   node script/loop/run-gates.mjs [--gates jvm,plugin,typecheck,vitest-cms,vitest-deck,docs,goldens,driver:dscusb,driver:usbmodbus]
//        [--set baseline|item:BL-nnn] [--stamp YYYYMMDD-HHMMSS] [--out <file.json>] [--paths a,b]
//        [--allow-goldens name,..] [--skip-generate] [--repo deck|dscusb|usbmodbus] [--floor] [--retry-lock 5] [--lock-wait 60]
//
// Exit 0 all green, 1 any red or skipped, 2 usage error or a build lock that outlasted the retries.
// `skipped` is not green: a gate that verified nothing authorizes nothing (CLAUDE.md commit rule).
// Runs in the deck's main tree only: the loop works one item at a time there, no worktrees
// (owner, 2026-10-13). `--paths` scopes the tree hash commit-group.mjs re-checks before it
// commits; without it the hash covers every dirty path. A unit spanning repos gives its paths in
// unit form (a driver repo's as `../driver/<repo>/<path>`, no --repo): one tree hash per repo in
// `treeHashes` {repo: {hash, scope, paths, head}}, `repo` "mixed", and --floor adds each repo's floor
// (deck paths their gates, a driver repo driver:<repo>). A single-repo run fills `treeHashes` with its
// one repo and keeps the top-level treeHash fields. `driver:<repo>` builds and tests the sibling repo and reports
// whether drivers/<repo>.jar differs from the fresh build; it never copies a jar: refreshing
// drivers/ is the main session's job. Never kills a process: a lock is waited out or reported.
import fs from 'node:fs';
import path from 'node:path';
import {
  DECK_ROOT, IS_WIN, UMBRELLA, floorGatesByRepo, pathsByRepo, repoRoot, fail, finish, git, list, parseArgs, run, sha256, stampNow, toPosix, treeHash,
} from './lib.mjs';

const ALL = ['jvm', 'plugin', 'typecheck', 'vitest-cms', 'vitest-deck', 'docs', 'goldens', 'driver:dscusb', 'driver:usbmodbus'];
const DEFAULT = ['jvm', 'plugin', 'typecheck', 'vitest-cms', 'vitest-deck', 'docs', 'goldens'];
const GOLDENS = 'command-deck/src/test/resources/ch/rupfizupfi/deck/testrunner/goldens';
const LOCK_RE = /Unable to delete (?:file|directory)[^\n]*\.jar|\.jar[^\n]*being used by another process/i;

const a = parseArgs();
const tree = DECK_ROOT;
let byRepo;
try { byRepo = pathsByRepo(list(a.paths), a.repo); } catch (e) { fail(e.message); }
const repos = Object.keys(byRepo);
// --floor adds the gates the --paths always need (lib.mjs floorGates, per repo), so a unit cannot under-test itself.
const asked = list(a.gates).length ? list(a.gates) : DEFAULT;
const gates = a.floor ? [...new Set([...asked, ...floorGatesByRepo(byRepo)])].filter((g) => ALL.includes(g)) : asked;
const unknown = gates.filter((g) => !ALL.includes(g));
if (unknown.length) fail(`unknown gate(s): ${unknown.join(', ')}; known: ${ALL.join(', ')}`);
const stamp = a.stamp ?? stampNow();
const set = a.set ?? 'adhoc';
const outFile = a.out ? path.resolve(a.out) : null;
// Logs live beside --out, else in the umbrella's unversioned .claude/loop-runs/ (never in the deck tree).
const logDir = path.join(outFile ? path.dirname(outFile) : path.join(UMBRELLA, '.claude', 'loop-runs', stamp), `gates-${set.replace(/[:]/g, '-')}-${stamp}`);
fs.mkdirSync(logDir, { recursive: true });
const retryLock = Number(a['retry-lock'] ?? 5);
const lockWaitMs = Number(a['lock-wait'] ?? 60) * 1000;
const allowGoldens = list(a['allow-goldens']);

const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);

function daemonPids() {
  const r = run('jps', ['-l']);
  return r.stdout.split(/\r?\n/).filter((l) => /GradleDaemon|KotlinCompileDaemon/.test(l)).map((l) => l.trim());
}

class Locked extends Error {}

/** Runs gradlew in `cwd`, retrying on the device-api jar lock; returns {code, out, log}. */
function gradle(cwd, args, name) {
  const log = path.join(logDir, `${name}.log`);
  for (let attempt = 0; ; attempt++) {
    const r = IS_WIN
      ? run('cmd.exe', ['/d', '/c', 'gradlew.bat', ...args, '--console=plain'], { cwd })
      : run('./gradlew', [...args, '--console=plain'], { cwd });
    const out = r.stdout + r.stderr;
    fs.writeFileSync(log, out);
    if (r.code !== 0 && LOCK_RE.test(out)) {
      if (attempt >= retryLock) throw new Locked(out.match(LOCK_RE)[0]);
      sleep(lockWaitMs);
      continue;
    }
    return { code: r.code, out, log };
  }
}

function junit(dir, sinceMs) {
  const res = { tests: 0, failures: 0, errors: 0, skipped: 0, failing: [], skipReasons: [] };
  if (!fs.existsSync(dir)) return res;
  for (const f of fs.readdirSync(dir).filter((f) => f.endsWith('.xml'))) {
    const file = path.join(dir, f);
    if (fs.statSync(file).mtimeMs < sinceMs) continue;
    const xml = fs.readFileSync(file, 'utf8');
    const suite = xml.match(/<testsuite\b[^>]*>/)?.[0] ?? '';
    const attr = (k) => Number(suite.match(new RegExp(`\\b${k}="(\\d+)"`))?.[1] ?? 0);
    res.tests += attr('tests'); res.failures += attr('failures'); res.errors += attr('errors'); res.skipped += attr('skipped');
    for (const m of xml.matchAll(/<testcase\b[^>]*name="([^"]*)"[^>]*classname="([^"]*)"[^>]*?(?:\/>|>([\s\S]*?)<\/testcase>)/g)) {
      const body = m[3] ?? '';
      if (/<(failure|error)\b/.test(body)) res.failing.push(`${m[2]}#${m[1]}`);
      const skip = body.match(/<skipped\b[^>]*message="([^"]*)"/);
      if (skip && res.skipReasons.length < 5) res.skipReasons.push(skip[1]);
    }
  }
  return res;
}

const decode = (s) => s.replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');

const runners = {
  jvm() {
    const t0 = Date.now();
    const g = gradle(tree, [':cms:test', '--rerun', ':command-deck:test', '--rerun'], 'jvm');
    const mods = ['cms', 'command-deck'].map((m) => ({ m, ...junit(path.join(tree, m, 'build', 'test-results', 'test'), t0 - 1000) }));
    const sum = (k) => mods.reduce((s, x) => s + x[k], 0);
    const totals = { tests: sum('tests'), failures: sum('failures'), errors: sum('errors'), skipped: sum('skipped') };
    const failing = mods.flatMap((x) => x.failing);
    const green = g.code === 0 && totals.failures === 0 && totals.errors === 0 && totals.tests > 0;
    return { status: green ? 'green' : 'red', summary: `${totals.tests} tests, ${totals.failures} failures, ${totals.errors} errors, ${totals.skipped} skipped (${mods.map((x) => `${x.m} ${x.tests}`).join(', ')})${g.code && !failing.length ? ` — gradle exit ${g.code}` : ''}`, totals, failing, log: g.log };
  },
  plugin() {
    const t0 = Date.now();
    const g = gradle(tree, [':command-deck:driverPluginTest', '--rerun'], 'plugin');
    const j = junit(path.join(tree, 'command-deck', 'build', 'test-results', 'driverPluginTest'), t0 - 1000);
    const jars = fs.existsSync(path.join(tree, 'drivers')) ? fs.readdirSync(path.join(tree, 'drivers')).filter((f) => f.endsWith('.jar')) : [];
    if (g.code === 0 && j.tests > 0 && j.skipped === j.tests) {
      return { status: 'skipped', summary: `all ${j.tests} skipped: ${j.skipReasons.map(decode).join(' / ') || 'no reason recorded'}`, jars, totals: j, log: g.log };
    }
    const green = g.code === 0 && j.failures + j.errors === 0 && j.tests > 0;
    return { status: green ? 'green' : 'red', summary: `${j.tests} tests, ${j.failures + j.errors} failed, ${j.skipped} skipped; jars: ${jars.join(', ') || 'none'}`, jars, totals: j, failing: j.failing, log: g.log };
  },
  typecheck() {
    const args = ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(tree, 'script', 'typecheck.ps1')];
    if (a['skip-generate']) args.push('-SkipGenerate');
    const log = path.join(logDir, 'typecheck.log');
    let r, out;
    for (let attempt = 0; ; attempt++) {
      r = run(IS_WIN ? 'powershell.exe' : 'pwsh', args, { cwd: tree });
      out = r.stdout + r.stderr;
      fs.writeFileSync(log, out);
      if (r.code !== 0 && LOCK_RE.test(out)) { if (attempt >= retryLock) throw new Locked(out.match(LOCK_RE)[0]); sleep(lockWaitMs); continue; }
      break;
    }
    const lines = [...out.matchAll(/^(PASS|FAIL)\s+(\S+)\s+(.*)$/gm)].map((m) => `${m[1]} ${m[2]} ${m[3].trim()}`);
    const failing = lines.filter((l) => l.startsWith('FAIL'));
    return { status: r.code === 0 && lines.length && !failing.length ? 'green' : 'red', summary: lines.join('; ') || `exit ${r.code}, no summary`, failing, log };
  },
  'vitest-cms': () => vitest('cms'),
  'vitest-deck': () => vitest('command-deck'),
  docs() {
    const py = IS_WIN ? 'python' : 'python3';
    const cmds = [[py, 'doc/_check-size.py'], ['node', 'doc/_verify-refs.mjs'], [py, 'doc/_check-diagrams.py'], [py, 'doc/_check-inventories.py']];
    const parts = [], failing = [];
    let all = '';
    for (const [c, s] of cmds) {
      const r = run(c, [s], { cwd: tree, env: { ...process.env, PYTHONDONTWRITEBYTECODE: '1' } });
      const out = r.stdout + r.stderr;
      all += `=== ${s} (exit ${r.code})\n${out}\n`;
      const tail = out.trim().split(/\r?\n/).filter(Boolean).slice(-2).join(' ');
      parts.push(`${path.basename(s)}: ${r.code === 0 ? 'OK' : 'FAIL'}`);
      if (r.code !== 0) failing.push(`${s}: ${out.split(/\r?\n/).filter((l) => /BROKEN|DRIFT|FAIL|over|error/i.test(l)).slice(0, 10).join(' | ') || tail}`);
    }
    const log = path.join(logDir, 'docs.log');
    fs.writeFileSync(log, all);
    return { status: failing.length ? 'red' : 'green', summary: parts.join(', '), failing, log };
  },
  goldens() {
    const r = git(tree, ['status', '--porcelain=v1', '--untracked-files=all', '--', GOLDENS]);
    const changed = r.stdout.split(/\r?\n/).filter(Boolean).map((l) => l.slice(3));
    const unexplained = changed.filter((f) => !allowGoldens.some((g) => toPosix(f).endsWith(g) || path.basename(f, '.txt') === g));
    return { status: unexplained.length ? 'red' : 'green', summary: changed.length ? `changed: ${changed.join(', ')}${unexplained.length ? ` (not allowed: ${unexplained.join(', ')})` : ' (all allowed)'}` : 'no golden changed', changed, failing: unexplained };
  },
  'driver:dscusb': () => driver('dscusb'),
  'driver:usbmodbus': () => driver('usbmodbus'),
};

function vitest(mod) {
  const dir = path.join(tree, mod);
  if (!fs.existsSync(path.join(dir, 'node_modules'))) return { status: 'red', summary: `${mod}/node_modules missing: pnpm install --dir ${mod} --frozen-lockfile`, failing: [] };
  const report = path.join(logDir, `vitest-${mod}.json`);
  const r = run(IS_WIN ? 'pnpm.cmd' : 'pnpm', ['--dir', dir, 'test', '--reporter=json', `--outputFile=${report}`], { cwd: dir, shell: IS_WIN });
  const log = path.join(logDir, `vitest-${mod}.log`);
  fs.writeFileSync(log, r.stdout + r.stderr);
  let j = null;
  try { j = JSON.parse(fs.readFileSync(report, 'utf8')); } catch {}
  if (!j) return { status: 'red', summary: `exit ${r.code}, no JSON report`, failing: [], log };
  const failing = (j.testResults || []).flatMap((f) => (f.assertionResults || []).filter((x) => x.status === 'failed').map((x) => `${path.relative(dir, f.name)}#${x.fullName}`));
  const green = r.code === 0 && j.numFailedTests === 0 && j.numTotalTests > 0;
  return { status: green ? 'green' : 'red', summary: `${j.numTotalTests} tests, ${j.numFailedTests} failed, ${j.numPendingTests ?? 0} skipped`, totals: { tests: j.numTotalTests, failures: j.numFailedTests, skipped: j.numPendingTests ?? 0 }, failing, log };
}

function driver(repo) {
  const dir = path.join(UMBRELLA, 'driver', repo);
  if (!fs.existsSync(dir)) return { status: 'skipped', summary: `no checkout at ${dir}` };
  const g = gradle(dir, ['test', 'shadowJar'], `driver-${repo}`);
  const j = junit(path.join(dir, 'build', 'test-results', 'test'), 0);
  const libs = path.join(dir, 'build', 'libs');
  const built = fs.existsSync(libs) ? fs.readdirSync(libs).filter((f) => f.endsWith('.jar')).map((f) => path.join(libs, f)).sort((x, y) => fs.statSync(y).mtimeMs - fs.statSync(x).mtimeMs)[0] : null;
  const deployed = path.join(DECK_ROOT, 'drivers', `${repo}.jar`);
  const builtSha = built ? sha256(fs.readFileSync(built)) : null;
  const deployedSha = fs.existsSync(deployed) ? sha256(fs.readFileSync(deployed)) : null;
  const jarRefreshNeeded = !!builtSha && builtSha !== deployedSha;
  const green = g.code === 0 && j.failures + j.errors === 0;
  return {
    status: green ? 'green' : 'red',
    summary: `${j.tests} tests, ${j.failures + j.errors} failed; ${jarRefreshNeeded ? `drivers/${repo}.jar differs from the fresh build: jar refresh needed (main session)` : 'drivers/ jar matches the build'}`,
    head: git(dir, ['rev-parse', '--short', 'HEAD']).stdout.trim(), built, deployed, jarRefreshNeeded, failing: j.failing, log: g.log,
  };
}

// ------------------------------------------------------------------- main ---

const head = git(tree, ['rev-parse', '--short', 'HEAD']).stdout.trim();
const result = { ok: true, tree: toPosix(tree), head, startedAt: new Date().toISOString(), set, stamp, requested: gates, gates: {}, green: false, logDir: toPosix(logDir) };
try {
  for (const id of gates) {
    const t0 = Date.now();
    let r;
    try { r = runners[id](); } catch (e) { if (e instanceof Locked) throw e; r = { status: 'red', summary: `runner error: ${e.message}`, failing: [] }; }
    result.gates[id] = { ...r, durationMs: Date.now() - t0 };
  }
} catch (e) {
  if (!(e instanceof Locked)) throw e;
  Object.assign(result, { ok: false, lock: true, lockLine: e.message, pids: daemonPids(), hint: 'a Gradle/Kotlin daemon holds a jar; wait for its build to end and re-run. Never kill it.' });
}
// --repo hashes a driver repo's paths instead, for a unit that commits there; a mixed unit hashes each repo.
const treeHashes = Object.fromEntries(repos.map((r) => { const t = treeHash(repoRoot(r), byRepo[r]); return [r, { hash: t.hash, scope: t.scope, paths: byRepo[r], head: t.head }]; }));
if (repos.length === 1) {
  const th = treeHashes[repos[0]];
  Object.assign(result, { repo: a.repo ?? 'deck', finishedAt: new Date().toISOString(), treeHash: th.hash, treeHashScope: th.scope, paths: list(a.paths), finishedHead: th.head, treeHashes });
} else {
  Object.assign(result, { repo: 'mixed', repos, finishedAt: new Date().toISOString(), paths: list(a.paths), treeHashes });
}
result.green = !result.lock && gates.every((g) => result.gates[g]?.status === 'green');
if (outFile) fs.writeFileSync(outFile, JSON.stringify(result, null, 2) + '\n');
result.out = outFile && toPosix(outFile);
finish(result, result.lock ? 2 : result.green ? 0 : 1);
