#!/usr/bin/env node
// Commits one work unit (or a separable part of it) under the standing "a passing gate authorizes a
// commit" exception (CLAUDE.md). The authority is the gate run, so this refuses unless that run
// was green for every gate the items require and saw exactly the tree being committed.
//
//   node script/loop/commit-group.mjs --items BL-1,BL-2 --capture <cap.json>[,<cap.json>..] --gates <gates.json>
//        --message "fix: one line" [--paths a,b] [--ui-run <deck-run.json>] [--repo dscusb|usbmodbus]
//        [--backlog-dir <dir>] [--plan]
//
// --repo commits a unit's driver-repo code there instead of the deck (one capture; a capture that
// records its repo needs no --repo). A unit spanning repos passes one capture per repo, no --repo,
// and --paths in unit form (`../driver/<repo>/<path>` for a driver repo's): one commit per repo with
// the same message, the deck first, then each driver repo (owner, 2026-10-18). Rebuilding
// drivers/*.jar is never done here (main session only).
//
// Required gates: the items' own plus the floor every changed path needs (lib.mjs floorGates, per
// repo). Checks, for every repo before anything is committed: message lint (deck-commit: one line,
// conventional type, lowercase, <=72 chars, no em dash, no attribution); gates.green and every
// required gate green (skipped is not green); gates no older than 30 min; the gate run hashed the
// repo and its tree hash over the committed paths is unchanged since; paths are the unit's own
// (capture), none protected or generated, none foreign; the index is empty before staging. Then it
// stages each repo's code/doc paths by name and commits, and only after every repo's commit marks
// each item done with evidence {commit: <the deck sha, else the one sha>, repo, commits: [{sha, repo}]}.
// A later repo refused after an earlier one committed is reported as partial (committed: true,
// partial: true, commits, done, refusedRepo, refused, pending): the landed shas go into
// evidence.commits, the items stay open, and a rerun for the pending repo merges them when it marks
// them done. The backlog is unversioned (umbrella doc/@work/backlog/), so no item file is ever staged
// and nothing under it can ride along. --plan runs every check and returns the commit plan without
// writing anything. No push, no amend, no PR.
import path from 'node:path';
import {
  ITEMS_DIR, PROTECTED, floorGates, pathsByRepo, repoRoot, repoSort, fail, finish, git, list, parseArgs, pathMatchesGlob, readJson, toPosix, toUnitPath, treeHash,
} from './lib.mjs';
import { loadItems, render, saveItem, validate } from './backlog.mjs';

const MAX_AGE_MS = 30 * 60 * 1000;
const TYPES = ['feat', 'fix', 'chore', 'refactor', 'build', 'docs', 'perf', 'test', 'ci'];

const a = parseArgs();
const ids = list(a.items);
const message = typeof a.message === 'string' ? a.message : null;
if (!ids.length || !a.capture || !a.gates || !message) fail('usage: commit-group.mjs --items BL-1,.. --capture <cap.json>[,..] --gates <gates.json> --message "<one line>" [--paths a,b] [--plan]');

const repoArg = a.repo && a.repo !== true ? String(a.repo) : null;
const refuse = (why, extra = {}) => finish({ ok: false, committed: false, refused: why, ...extra }, 1);

// ---- captures: one per repo; each records its repo (an older capture takes --repo, else deck)
const capFiles = list(a.capture);
if (capFiles.length > 1 && repoArg) fail('several captures: drop --repo, each capture records its repo');
const caps = {};
for (const f of capFiles) {
  const cap = readJson(f);
  const r = cap.repo ?? repoArg ?? 'deck';
  if (repoArg && cap.repo && cap.repo !== repoArg) fail(`--repo ${repoArg}, but ${f} was captured in repo ${cap.repo}`);
  if (caps[r]) fail(`two captures for repo ${r}`);
  repoRoot(r);
  caps[r] = { file: toPosix(path.resolve(f)), cap };
}
const capRepos = repoSort(Object.keys(caps));
const mixed = capRepos.length > 1;
const at = (r, why) => (mixed ? `${r}: ${why}` : why);

// ---- message
const lint = [];
if (/[\r\n]/.test(message)) lint.push('one line only, no body');
if (!new RegExp(`^(${TYPES.join('|')}): [a-z0-9]`).test(message)) lint.push(`must start with "<${TYPES.join('|')}>: " and a lowercase subject`);
if (message.length > 72) lint.push(`${message.length} chars, max 72`);
if (/—|–/.test(message)) lint.push('hyphens, not em or en dashes');
if (/co-authored-by|generated with|claude/i.test(message)) lint.push('no attribution');
if (/\.$/.test(message)) lint.push('no trailing period');
if (lint.length) refuse('message lint', { lint });

// ---- items
const items = loadItems();
const members = ids.map((id) => items.find((i) => i.id === id));
const missing = ids.filter((id, k) => !members[k]);
if (missing.length) refuse(`no such item(s): ${missing.join(', ')}`);
const notOpen = members.filter((m) => ['done', 'wontdo'].includes(m.status) || m.decision !== 'SELF');
if (notOpen.length) refuse(`not committable: ${notOpen.map((m) => `${m.id} ${m.status}/${m.decision}`).join(', ')}`);
const units = new Set(members.map((m) => m.unit ?? m.id));
if (units.size > 1) refuse(`items from different units: ${[...units].join(', ')}`);

// ---- paths per repo: --paths (unit form when mixed), else each capture's files
let byRepo;
if (list(a.paths).length) {
  try { byRepo = pathsByRepo(list(a.paths), mixed ? null : capRepos[0]); } catch (e) { fail(e.message); }
  const stray = Object.keys(byRepo).filter((r) => !caps[r]);
  if (stray.length) refuse(`paths in repo(s) with no capture: ${stray.join(', ')}`);
} else byRepo = Object.fromEntries(capRepos.map((r) => [r, caps[r].cap.files.map(toPosix)]));
// A repo with nothing to commit (its capture is empty, or --paths names none of it) is left out.
const repos = repoSort(Object.keys(byRepo).filter((r) => byRepo[r].length));
const allPaths = repos.flatMap((r) => byRepo[r].map((p) => (mixed ? toUnitPath(r, p) : p)));

// ---- gates
const gates = readJson(a.gates);
// A gate file from before per-repo hashes carries its one repo's hash at the top level.
const hashes = gates.treeHashes ?? { [gates.repo ?? 'deck']: { hash: gates.treeHash, scope: gates.treeHashScope, paths: gates.paths, head: gates.finishedHead } };
for (const r of (repos.length ? repos : capRepos)) if (!hashes[r]) refuse(`the gate run hashed repo ${Object.keys(hashes).join('+')}, not ${r}`);
const required = [...new Set([...members.flatMap((m) => m.gates), ...repos.flatMap((r) => floorGates(byRepo[r], r))])];
if (!required.length) refuse('the items name no gate: no gate, no commit');
const notGreen = required.filter((g) => g !== 'deck-run' && gates.gates?.[g]?.status !== 'green');
if (notGreen.length) refuse(`required gate(s) not green in ${a.gates}: ${notGreen.map((g) => `${g}=${gates.gates?.[g]?.status ?? 'not run'}`).join(', ')}`);
if (required.includes('deck-run')) {
  const ui = a['ui-run'] ? readJson(a['ui-run']) : null;
  if (!ui || ui.verdict !== 'PASS') refuse('deck-run is required: pass --ui-run <deck-run.json> with verdict PASS');
}
const age = Date.now() - Date.parse(gates.finishedAt ?? 0);
if (!(age < MAX_AGE_MS)) refuse(`gate run is ${Math.round(age / 60000)} min old (max 30): re-run the gates`);

// ---- paths: every repo is checked before anything is committed
if (!repos.length) refuse('nothing to commit');
for (const r of repos) {
  const ROOT = repoRoot(r);
  const cap = caps[r].cap;
  const paths = byRepo[r];
  const foreignToCapture = paths.filter((p) => !cap.files.includes(p));
  if (foreignToCapture.length) refuse(at(r, `paths outside the unit's capture: ${foreignToCapture.join(', ')}`));
  const prot = paths.filter((p) => PROTECTED.some((g) => pathMatchesGlob(p, g)));
  if (prot.length) refuse(at(r, `protected paths: ${prot.join(', ')}`));
  if (cap.touchedForeign?.length) refuse(at(r, `the unit touched foreign dirty paths: ${cap.touchedForeign.join(', ')}`));
  const th = hashes[r];
  const scope = th.scope === 'paths' ? th.paths : null;
  if (scope && paths.some((p) => !scope.includes(p))) refuse(at(r, 'the gate run hashed a different path set than this commit'));
  const now = treeHash(ROOT, scope);
  if (now.hash !== th.hash) refuse(at(r, 'the tree changed since the gate run (tree hash differs): re-run the gates'), { gateHead: th.head, head: now.head, ...(mixed ? { repo: r } : {}) });
  // ---- index must be empty: someone else's staged work would ride along
  const staged = git(ROOT, ['diff', '--cached', '--name-only']).stdout.split(/\r?\n/).filter(Boolean);
  if (staged.length) refuse(at(r, `the index already holds staged changes: ${staged.join(', ')}`));
}

const gateRun = toPosix(path.resolve(a.gates));
const plan = mixed
  ? { repo: 'mixed', repos, items: ids, message, paths: allPaths, perRepo: repos.map((r) => ({ repo: r, paths: byRepo[r], capture: caps[r].file })), gates: required, gateRun }
  : { repo: repos[0], items: ids, message, paths: allPaths, gates: required, gateRun };
const goldens = repos.flatMap((r) => caps[r].cap.goldensTouched ?? []);

// ---- the items must validate as done before anything is committed (checked in memory)
const merged = (m, commits) => [...(m.evidence?.commits ?? []).filter((c) => !commits.some((x) => x.repo === c.repo)), ...commits];
const markDone = (m, commits) => {
  // Shas an earlier, partial run recorded (another repo of this unit) stay; the deck's is `commit`.
  const all = merged(m, commits);
  const main = all.find((c) => c.repo === 'deck') ?? commits[0];
  m.status = 'done';
  m.evidence = { ...m.evidence, commit: main.sha, repo: main.repo, commits: all, gates: gateRun, goldens, files: allPaths };
  m.updated = new Date().toISOString().slice(0, 10);
};
const probe = loadItems();
for (const m of probe.filter((x) => ids.includes(x.id))) markDone(m, repos.map((r) => ({ sha: 'pending', repo: r })));
const mine = validate(probe).errors.filter((e) => ids.some((id) => e.startsWith(`${id}:`)));
if (mine.length) refuse('backlog validation failed for the items', { errors: mine });
if (a.plan) finish({ ok: true, committed: false, plan: true, commitPlan: plan });

// ---- per repo, deck first: stage by name, commit; then record the shas in the items
function commitIn(r) {
  const ROOT = repoRoot(r);
  const paths = byRepo[r];
  const add = git(ROOT, ['add', '--', ...paths]);
  if (add.code !== 0) { git(ROOT, ['reset', '-q', '--', ...paths]); return { error: `git add failed: ${add.stderr.trim()}` }; }
  const nowStaged = git(ROOT, ['diff', '--cached', '--name-only']).stdout.split(/\r?\n/).filter(Boolean).map(toPosix);
  const extra = nowStaged.filter((p) => !paths.includes(p));
  if (extra.length) { git(ROOT, ['reset', '-q', '--', ...nowStaged]); return { error: `unexpected staged paths: ${extra.join(', ')}` }; }
  const c = git(ROOT, ['commit', '-q', '-m', message]);
  if (c.code !== 0) { git(ROOT, ['reset', '-q', '--', ...paths]); return { error: `git commit failed: ${(c.stderr || c.stdout).trim()}` }; }
  return { sha: git(ROOT, ['rev-parse', '--short', 'HEAD']).stdout.trim(), repo: r };
}
const commits = [];
for (const r of repos) {
  const c = commitIn(r);
  if (!c.error) { commits.push(c); continue; }
  if (!commits.length) refuse(at(r, c.error));
  // An earlier repo's commit stands: keep its sha on the items and leave them open for the rest.
  const recorded = [];
  let recordError = null;
  try {
    for (const m of loadItems().filter((i) => ids.includes(i.id))) { m.evidence = { ...m.evidence, commits: merged(m, commits) }; saveItem(m); recorded.push(m.id); }
    render(loadItems());
  } catch (e) { recordError = e.message; }
  const pending = repos.slice(repos.indexOf(r));
  finish({
    ok: false, committed: true, partial: true, sha: commits[0].sha, commits, done: commits.map((x) => x.repo), refusedRepo: r, refused: c.error, pending,
    ...plan, recorded, itemsDir: toPosix(ITEMS_DIR),
    error: `committed in ${commits.map((x) => x.repo).join(', ')}; ${r} refused: ${c.error}. The items stay open: finish ${pending.join(', ')} with its capture, fresh gates and commit-group, which then marks them done${recordError ? `; recording the landed shas failed: ${recordError}` : ''}`,
  }, 1);
}
const sha = (commits.find((c) => c.repo === 'deck') ?? commits[0]).sha;
const drivers = commits.map((c) => c.repo).filter((r) => r !== 'deck');
const recorded = [];
try {
  for (const m of loadItems().filter((i) => ids.includes(i.id))) { markDone(m, commits); saveItem(m); recorded.push(m.id); }
  render(loadItems());
} catch (e) {
  // The commits stand; only the bookkeeping failed. Report it so the main session records the shas.
  finish({ ok: false, committed: true, sha, commits, ...plan, recorded, itemsDir: toPosix(ITEMS_DIR), error: `commit made, recording it in the items failed: ${e.message}` }, 1);
}
finish({ ok: true, committed: true, sha, commits, ...plan, recorded, itemsDir: toPosix(ITEMS_DIR), ...(drivers.length ? { jarRefreshNeeded: true, jarRefreshRepos: drivers } : {}) });
