#!/usr/bin/env node
// The backlog: one JSON file per item under <backlog>/items/, a generated BACKLOG.md beside them.
// <backlog> is the umbrella's doc/@work/backlog/, outside every product repo (lib.mjs BACKLOG_DIR; every command
// takes --backlog-dir <dir> to use another copy).
//
//   validate                                   schema + rule check of every item (exit 1 on error)
//   list [--status s,..] [--kind k,..] [--sev s,..] [--decision d,..]
//   show <id>
//   next-unit [--max 3] [--kinds a,b] [--severity-floor low] [--dirty <json|auto>] [--exclude id,..]
//                                              ordered work units for one iteration (next-batch: alias)
//   group <id,id,..> [--unit U-name] [--safety] put similar ready/proposed SELF items into one work unit
//   ungroup <id,id,..>
//   expand <planId> <todos.json|-> [--sequential]  a plan item's todos become child items in its unit
//   add <item.json|-> [--source-type harvest]  one item or an array; dedupes on dedupeKey
//   update <id> [--set k=v ...] [--attempt <json>] [--evidence <json>] [--note <text>]
//   import --review <md> [--oq] [--map <json>] [--overrides <json>] [--extra <json>] [--merge]
//   render                                     rewrite BACKLOG.md
//   compact [--min-age-commits 10] [--wontdo]  delete done items long on main
//
// Every command prints one JSON object. Mutating commands re-validate and re-render.
// A work unit is the loop's serial step: one implementer, one gate run, one skeptic pass over every
// member's acceptance, normally one commit. Members share `unit`; a plan item (`plan: true`) is a
// container whose children (`parent`) form its unit and which closes when they all have.
// Item files live outside every product repo and the loop never commits them: commit-group.mjs commits a unit's
// code/doc paths first, then writes the real sha (and repo) into each member's evidence.
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  BACKLOG_DIR, DECK_ROOT, ITEMS_DIR, UMBRELLA, dirtyPaths, fail, finish, git, globsOverlap, list, parseArgs,
  pathMatchesGlob, readJson, today, toPosix, writeJson,
} from './lib.mjs';

export const KINDS = ['code', 'frontend', 'doc', 'build', 'ops', 'hardware', 'research'];
export const SEVERITIES = ['critical', 'high', 'medium', 'low'];
export const STATUSES = ['proposed', 'ready', 'in-progress', 'blocked-owner', 'blocked-bench', 'blocked-failed', 'blocked-conflict', 'done', 'wontdo'];
export const DECISIONS = ['SELF', 'OWNER', 'BENCH'];
export const EXPLORE = ['none', 'research', 'brainstorm'];
export const GATE_IDS = ['jvm', 'plugin', 'typecheck', 'vitest-cms', 'vitest-deck', 'docs', 'goldens', 'deck-run', 'driver:dscusb', 'driver:usbmodbus'];
export const SOURCE_TYPES = ['review', 'oq', 'tasks', 'owner', 'harvest', 'manual'];
export const MAX_UNIT = 5;
export const sevRank = (s) => 3 - SEVERITIES.indexOf(s);

const ID_RE = /^BL-\d{3,}$/;
const MD_PATH = path.join(BACKLOG_DIR, 'BACKLOG.md');
// The owner-facing question list lives beside the backlog in the umbrella, not in the deck.
const OQ_FILE = path.join(UMBRELLA, 'doc', '@work', 'questions', 'OPEN-QUESTIONS.md');

// ------------------------------------------------------------------ store ---

export function loadItems() {
  if (!fs.existsSync(ITEMS_DIR)) return [];
  return fs.readdirSync(ITEMS_DIR).filter((f) => /^BL-\d+\.json$/.test(f)).sort().map((f) => {
    const item = readJson(path.join(ITEMS_DIR, f));
    item.__file = f;
    return item;
  });
}

const itemPath = (id) => path.join(ITEMS_DIR, `${id}.json`);

export function saveItem(item) {
  const { __file, ...clean } = item;
  writeJson(itemPath(clean.id), clean);
}

/** Next free id, claimed with an exclusive create so two writers cannot take the same one. */
function claimId(items) {
  let n = items.reduce((m, i) => Math.max(m, Number(i.id.slice(3))), 0) + 1;
  fs.mkdirSync(ITEMS_DIR, { recursive: true });
  for (;; n++) {
    const id = `BL-${String(n).padStart(3, '0')}`;
    try { fs.writeFileSync(itemPath(id), '{}\n', { flag: 'wx' }); return id; } catch (e) { if (e.code !== 'EEXIST') throw e; }
  }
}

const slug = (s) => String(s).toLowerCase().replace(/`/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 48);

export function dedupeKeyOf(item) {
  const src = item.source?.oq || (item.source?.ref || '').split('#')[1] || '';
  const f = (item.files || [])[0];
  const stem = f ? path.posix.basename(toPosix(f).replace(/\/\*\*.*$/, '').replace(/\*.*$/, '')).replace(/\.[^.]+$/, '') : '';
  return [src.toLowerCase(), stem.toLowerCase(), slug(item.title)].join('|');
}

export function normalize(raw, { id, sourceType } = {}) {
  const d = today();
  const item = {
    schema: 1,
    id: id ?? raw.id,
    title: raw.title,
    source: { type: raw.source?.type ?? sourceType ?? 'manual', ref: raw.source?.ref ?? null, oq: raw.source?.oq ?? raw.oq ?? null },
    kind: raw.kind ?? 'code',
    severity: raw.severity ?? 'medium',
    status: raw.status ?? 'proposed',
    decision: raw.decision ?? 'SELF',
    explore: raw.explore ?? 'none',
    safety: raw.safety ?? false,
    acceptance: raw.acceptance ?? [],
    gates: raw.gates ?? [],
    files: raw.files ?? [],
    deps: raw.deps ?? [],
    ownerQuestion: raw.ownerQuestion ?? null,
    benchCheck: raw.benchCheck ?? null,
    attempts: raw.attempts ?? [],
    evidence: { commit: null, commits: [], tests: [], goldens: [], notes: '', ...(raw.evidence ?? {}) },
    notes: raw.notes ?? [],
    unit: raw.unit ?? null,
    plan: raw.plan ?? false,
    parent: raw.parent ?? null,
    dedupeKey: raw.dedupeKey ?? null,
    created: raw.created ?? d,
    updated: raw.updated ?? d,
  };
  if (!item.dedupeKey) item.dedupeKey = dedupeKeyOf(item);
  return item;
}

// --------------------------------------------------------------- validate ---

function knownOqs() {
  try { return new Set([...fs.readFileSync(OQ_FILE, 'utf8').matchAll(/\*\*(OQ-\d+)\*\*/g)].map((m) => m[1])); } catch { return null; }
}

export function validate(items) {
  const errors = [], warnings = [];
  const ids = new Set(items.map((i) => i.id));
  const keys = new Map();
  const oqs = knownOqs();
  const open = (i) => !['done', 'wontdo'].includes(i.status);
  for (const i of items) {
    const e = (m) => errors.push(`${i.id ?? i.__file}: ${m}`);
    const w = (m) => warnings.push(`${i.id}: ${m}`);
    if (!ID_RE.test(i.id ?? '')) { e('bad id'); continue; }
    if (i.__file && i.__file !== `${i.id}.json`) e(`file name ${i.__file} does not match id`);
    if (i.schema !== 1) e('schema must be 1');
    if (!i.title || typeof i.title !== 'string') e('title required');
    if (!KINDS.includes(i.kind)) e(`kind ${i.kind}`);
    if (!SEVERITIES.includes(i.severity)) e(`severity ${i.severity}`);
    if (!STATUSES.includes(i.status)) e(`status ${i.status}`);
    if (!DECISIONS.includes(i.decision)) e(`decision ${i.decision}`);
    if (!EXPLORE.includes(i.explore)) e(`explore ${i.explore}`);
    if (!SOURCE_TYPES.includes(i.source?.type)) e(`source.type ${i.source?.type}`);
    for (const k of ['acceptance', 'gates', 'files', 'deps', 'attempts', 'notes']) if (!Array.isArray(i[k])) e(`${k} must be an array`);
    if (typeof i.safety !== 'boolean') e('safety must be boolean');
    for (const g of i.gates || []) if (!GATE_IDS.includes(g)) e(`unknown gate ${g}`);
    for (const d of i.deps || []) if (!ids.has(d)) w(`dep ${d} does not exist`);
    if (i.status === 'ready') {
      if (!i.acceptance?.length) e('ready needs at least one acceptance criterion');
      if (!i.files?.length) e('ready needs files');
      if (i.decision !== 'SELF') e('ready needs decision SELF');
      if (!i.gates?.length) e('ready needs gates');
    }
    if (i.decision === 'OWNER') {
      if (!i.ownerQuestion?.question) e('OWNER needs ownerQuestion.question');
      if (!['blocked-owner', 'wontdo'].includes(i.status)) e(`OWNER item cannot be ${i.status} (answer it: decision SELF)`);
    }
    if (i.status === 'blocked-owner' && i.decision !== 'OWNER') e('blocked-owner needs decision OWNER');
    if (i.decision === 'BENCH') {
      if (!i.benchCheck) e('BENCH needs benchCheck');
      if (!['blocked-bench', 'done', 'wontdo'].includes(i.status)) e(`BENCH item cannot be ${i.status}`);
      if (i.status === 'done' && !i.evidence?.bench) e('a closed bench item needs evidence.bench (the owner records the bench result)');
    }
    if (i.status === 'blocked-bench' && i.decision !== 'BENCH') e('blocked-bench needs decision BENCH');
    if (i.status === 'done' && i.decision !== 'BENCH' && !i.evidence?.commit) e('done needs evidence.commit (the sha commit-group.mjs wrote)');
    if (i.evidence?.commit === 'self') e('evidence.commit "self" is retired: item files are not committed, record the sha');
    if (i.status === 'wontdo' && !i.evidence?.notes) e('wontdo needs evidence.notes (the reason)');
    if (i.kind === 'hardware' && i.decision === 'SELF' && open(i)) w('hardware item is SELF: confirm no acceptance criterion needs a device');
    if (i.dedupeKey) {
      if (keys.has(i.dedupeKey)) e(`dedupeKey duplicates ${keys.get(i.dedupeKey)}`);
      keys.set(i.dedupeKey, i.id);
    } else e('dedupeKey required');
    if (oqs && i.source?.oq && open(i) && !oqs.has(i.source.oq)) w(`${i.source.oq} is no longer in OPEN-QUESTIONS.md`);
    if (typeof i.plan !== 'boolean') e('plan must be boolean');
    if (i.plan && i.status === 'ready') e('a plan item is a container: its children are made ready, not the plan');
    if (i.parent) {
      const p = items.find((x) => x.id === i.parent);
      if (!p) e(`parent ${i.parent} does not exist`);
      else if (!p.plan) e(`parent ${i.parent} is not a plan item`);
      if (i.unit !== i.parent) e(`a plan's child belongs to the plan's unit (${i.parent})`);
    }
    if (i.plan && i.unit && i.unit !== i.id) e("a plan item's unit is its own id");
  }
  // Units: at most MAX_UNIT open members, all SELF, safety uniform.
  const units = new Map();
  for (const i of items.filter((x) => x.unit && open(x) && !x.plan)) units.set(i.unit, [...(units.get(i.unit) || []), i]);
  for (const [u, ms] of units) {
    if (ms.length > MAX_UNIT) errors.push(`${u}: ${ms.length} open members, cap ${MAX_UNIT}`);
    const notSelf = ms.filter((m) => m.decision !== 'SELF');
    if (notSelf.length) errors.push(`${u}: never mix SELF with OWNER/BENCH (ungroup ${notSelf.map((m) => m.id).join(', ')})`);
    if (new Set(ms.map((m) => m.safety)).size > 1) errors.push(`${u}: safety and non-safety members (group with --safety to treat the whole unit as safety)`);
  }
  for (const p of items.filter((x) => x.plan && open(x))) {
    const kids = items.filter((x) => x.parent === p.id);
    if (kids.length && kids.every((k) => k.status === 'done')) warnings.push(`${p.id}: every child is done but the plan is open`);
  }
  return { errors, warnings };
}

// ----------------------------------------------------------------- render ---

const STATUS_ORDER = ['in-progress', 'ready', 'proposed', 'blocked-failed', 'blocked-conflict', 'done', 'wontdo'];
const cell = (s) => String(s ?? '').replace(/\|/g, '\\|').replace(/\r?\n/g, ' ');

export function render(items) {
  const bySev = (a, b) => sevRank(b.severity) - sevRank(a.severity) || a.id.localeCompare(b.id);
  const counts = Object.fromEntries(STATUSES.map((s) => [s, items.filter((i) => i.status === s).length]));
  const src = (i) => [i.source.oq, i.source.ref ? i.source.ref.split('#')[1] || path.posix.basename(i.source.ref) : null].filter(Boolean).join(' ') || i.source.type;
  const lines = [
    '# Backlog',
    '',
    'Generated by `node script/loop/backlog.mjs render` (deck repo) from `items/*.json` beside it; do not edit. Unversioned: nothing here is committed.',
    'Item rules and commands: the `deck-backlog` skill.',
    '',
    Object.entries(counts).filter(([, n]) => n).map(([s, n]) => `${s} ${n}`).join(' · '),
    '',
  ];
  const owner = items.filter((i) => i.status === 'blocked-owner').sort(bySev);
  if (owner.length) {
    lines.push('## Owner questions', '');
    for (const i of owner) {
      lines.push(`- **${i.id}** (${i.severity}${i.source.oq ? `, ${i.source.oq}` : ''}) ${i.ownerQuestion.question}`);
      for (const o of i.ownerQuestion.options || []) lines.push(`  - ${o}`);
    }
    lines.push('');
  }
  const bench = items.filter((i) => i.status === 'blocked-bench').sort(bySev);
  if (bench.length) {
    lines.push('## Bench checks', '');
    for (const i of bench) lines.push(`- **${i.id}** (${i.severity}${i.source.oq ? `, ${i.source.oq}` : ''}) ${i.benchCheck}`);
    lines.push('');
  }
  for (const s of STATUS_ORDER) {
    const rows = items.filter((i) => i.status === s).sort(bySev);
    if (!rows.length) continue;
    lines.push(`## ${s}`, '', '| id | sev | kind | unit | title | source | gates | attempts | note |', '|---|---|---|---|---|---|---|---|---|');
    for (const i of rows) {
      const note = s === 'done' ? (i.evidence.commit ?? '') + (i.evidence.repo && i.evidence.repo !== 'deck' ? ` (${i.evidence.repo})` : '')
        : s === 'wontdo' ? i.evidence.notes
        : i.notes.at(-1) ?? '';
      lines.push(`| ${i.id} | ${i.severity} | ${i.kind}${i.safety ? ' ⚠' : ''} | ${i.plan ? 'plan' : i.unit ?? ''} | ${cell(i.title)} | ${cell(src(i))} | ${i.gates.join(', ')} | ${i.attempts.length || ''} | ${cell(note)} |`);
    }
    lines.push('');
  }
  fs.mkdirSync(BACKLOG_DIR, { recursive: true });
  fs.writeFileSync(MD_PATH, lines.join('\n'));
  return MD_PATH;
}

function commitWrite(extra = {}) {
  const items = loadItems();
  const v = validate(items);
  const md = render(items);
  finish({ ok: v.errors.length === 0, ...extra, errors: v.errors, warnings: v.warnings, rendered: md }, v.errors.length ? 1 : 0);
}

// -------------------------------------------------------------- next-unit ---

/** Members in dependency order (inside the unit), then by id. */
function orderMembers(ms) {
  const ids = new Set(ms.map((m) => m.id)), out = [], seen = new Set();
  const visit = (m) => {
    if (seen.has(m.id)) return;
    seen.add(m.id);
    for (const d of m.deps) if (ids.has(d)) visit(ms.find((x) => x.id === d));
    out.push(m);
  };
  [...ms].sort((a, b) => a.id.localeCompare(b.id)).forEach(visit);
  return out;
}

/**
 * The iteration's work units, in the order the loop runs them, one at a time in the main tree.
 * Ungrouped ready items are units of one. A unit waits until every member's deps are done, inside
 * the unit, or in a unit ahead of it; a unit touching a foreign dirty path is skipped this round.
 */
export function nextUnits(items, { max = 3, kinds = KINDS.filter((k) => k !== 'hardware'), floor = 'low', dirty = [], exclude = [] } = {}) {
  const done = new Set(items.filter((i) => i.status === 'done').map((i) => i.id));
  const rejected = [];
  const ready = items.filter((i) => i.status === 'ready' && !i.plan && !exclude.includes(i.id));
  const groups = new Map();
  for (const i of ready) {
    if (!kinds.includes(i.kind)) { rejected.push({ id: i.id, why: `kind ${i.kind} not in this run` }); continue; }
    const key = i.unit ?? i.id;
    groups.set(key, [...(groups.get(key) || []), i]);
  }
  let units = [...groups].map(([unit, ms]) => {
    const members = orderMembers(ms);
    const severity = members.reduce((s, m) => (sevRank(m.severity) > sevRank(s) ? m.severity : s), 'low');
    const pending = items.filter((x) => x.unit && x.unit === unit && !x.plan && !['ready', 'done', 'wontdo'].includes(x.status)).map((x) => `${x.id}:${x.status}`);
    return { unit, members, severity, safety: members.some((m) => m.safety), kind: members[0].kind, files: [...new Set(members.flatMap((m) => m.files))], gates: [...new Set(members.flatMap((m) => m.gates))], pending };
  });
  units = units.filter((u) => {
    if (sevRank(u.severity) >= sevRank(floor)) return true;
    rejected.push({ unit: u.unit, why: `below severity floor ${floor}` });
    return false;
  });
  const isDoc = (u) => u.kind === 'doc';
  units.sort((a, b) => isDoc(a) - isDoc(b) || sevRank(b.severity) - sevRank(a.severity) || b.safety - a.safety
    || a.members[0].created.localeCompare(b.members[0].created) || a.unit.localeCompare(b.unit));
  const chosen = [], planned = new Set();
  let progress = true;
  while (progress && chosen.length < max) {
    progress = false;
    for (const u of units) {
      if (chosen.includes(u) || u.blocked || chosen.length >= max) continue;
      const inUnit = new Set(u.members.map((m) => m.id));
      const missing = u.members.flatMap((m) => m.deps).filter((d) => !done.has(d) && !inUnit.has(d) && !planned.has(d));
      if (missing.length) { u.missing = missing; continue; }
      u.missing = null;
      const dirtyHit = dirty.find((p) => u.files.some((g) => pathMatchesGlob(p, g)));
      if (dirtyHit) { u.blocked = true; rejected.push({ unit: u.unit, why: `foreign dirty path ${dirtyHit}`, conflict: true }); continue; }
      chosen.push(u);
      u.members.forEach((m) => planned.add(m.id));
      progress = true;
    }
  }
  for (const u of units) {
    if (chosen.includes(u) || u.blocked) continue;
    rejected.push({ unit: u.unit, why: u.missing ? `deps not done: ${[...new Set(u.missing)].join(', ')}` : 'batch full' });
  }
  return { units: chosen, rejected };
}

// ----------------------------------------------------------------- import ---

const SEV_UP = { low: 'medium', medium: 'high', high: 'critical', critical: 'critical' };

function tableRows(md) {
  // [{section, header:[...], cells:[...]}] for every markdown table row under its nearest heading.
  const rows = [];
  let section = '', header = null;
  for (const line of md.split(/\r?\n/)) {
    const h = line.match(/^(#{2,3})\s+(.*)/);
    if (h) { section = h[2].trim(); header = null; continue; }
    if (!line.startsWith('|')) { header = null; continue; }
    const cells = line.replace(/^\||\|$/g, '').split(/(?<!\\)\|/).map((c) => c.trim());
    if (cells.every((c) => /^-+$/.test(c))) continue;
    if (!header) { header = cells.map((c) => c.toLowerCase()); continue; }
    const hdr = header;
    rows.push({ section, header: hdr, cells, get: (k) => cells[hdr.indexOf(k)] });
  }
  return rows;
}

function expandIds(key) {
  const ids = [];
  for (const tok of key.split(/[,/]/).map((t) => t.trim()).filter(Boolean)) {
    const r = tok.match(/^([A-Z])-(\d+)\s*[–-]\s*\1-(\d+)$/);
    if (r) for (let n = Number(r[2]); n <= Number(r[3]); n++) ids.push(`${r[1]}-${String(n).padStart(2, '0')}`);
    else ids.push(tok);
  }
  return ids;
}

function splitTitles(title, n) {
  for (const sep of [/;\s*/, /,\s*(?![^(]*\))/]) {
    const parts = title.split(sep).map((s) => s.trim()).filter(Boolean);
    if (parts.length === n) return parts;
  }
  return null;
}

const KIND_BY_SECTION = { 'Safety and realtime': 'code', 'Security and backend': 'code', Architecture: 'code', Frontend: 'frontend', 'Ops and drift': 'build' };
const SAFETY_SECTION = 'Safety and realtime';

function decisionOf(text) {
  const t = text || '';
  const oq = t.match(/OQ-\d+/)?.[0] ?? null;
  if (/bench/i.test(t)) return { decision: 'BENCH', oq };
  if (/OWNER/.test(t) && !/SELF/.test(t)) return { decision: 'OWNER', oq };
  return { decision: 'SELF', oq };
}

function importReview(file) {
  const md = fs.readFileSync(file, 'utf8');
  const ref = toPosix(path.relative(path.resolve(DECK_ROOT, '..'), file));
  const rows = tableRows(md);
  const fixFirst = new Set(rows.filter((r) => r.section.startsWith('Fix first')).flatMap((r) => expandIds(r.get('id').replace(/\s*\+\s*/g, ','))));
  const ownerDecided = new Map();
  for (const r of rows.filter((r) => r.section.startsWith('Owner decisions'))) {
    for (const id of r.get('topic').match(/[A-Z]-\d+/g) || []) ownerDecided.set(id, `${r.get('decision')} — ${r.get('consequence for the fix list')}`);
  }
  const out = [];
  for (const r of rows.filter((r) => KIND_BY_SECTION[r.section] && r.get('id'))) {
    const key = r.get('id');
    const ids = expandIds(key);
    const titles = ids.length > 1 ? splitTitles(r.get('title'), ids.length) : null;
    const groups = titles ? ids.map((id, k) => ({ ids: [id], title: titles[k] })) : [{ ids, title: r.get('title') }];
    for (const g of groups) {
      const { decision, oq } = decisionOf(r.get('decision'));
      const decided = g.ids.map((id) => ownerDecided.get(id)).filter(Boolean);
      const sev = r.get('sev');
      out.push({
        reviewKey: key,
        reviewIds: g.ids,
        title: g.title.replace(/`/g, ''),
        source: { type: 'review', ref: `${ref}#${g.ids.join(',')}`, oq },
        kind: KIND_BY_SECTION[r.section],
        severity: g.ids.some((id) => fixFirst.has(id)) ? SEV_UP[sev] : sev,
        decision: decided.length ? 'SELF' : decision,
        safety: r.section === SAFETY_SECTION,
        notes: [r.get('where') ? `where: ${r.get('where')}` : null, ...decided.map((d) => `owner decision 2026-10-09: ${d}`)].filter(Boolean),
      });
    }
  }
  for (const id of fixFirst) if (/^OQ-/.test(id)) out.push({ reviewKey: id, reviewIds: [id], title: rows.find((r) => r.get('id') === id)?.get('what') ?? id, source: { type: 'review', ref: `${ref}#${id}`, oq: id }, kind: 'code', severity: 'high', decision: 'SELF', safety: true, notes: [] });
  return out;
}

const OQ_SECTIONS = {
  Defects: { kind: 'code', severity: 'medium' },
  'Security and tenancy': { kind: 'code', severity: 'medium' },
  Investigations: { kind: 'research', severity: 'low' },
  'Decided, awaiting implementation': { kind: 'code', severity: 'medium' },
  'Blocked or undecided': { kind: 'research', severity: 'low' },
};

function firstSentence(s) {
  const plain = s.replace(/\*\*/g, '').replace(/`/g, '').replace(/\[([^\]]+)\]\([^)]*\)/g, '$1');
  const m = plain.match(/^(.{20,160}?[.?])(\s|$)/);
  return (m ? m[1] : plain.slice(0, 140)).trim();
}

function importOq() {
  const md = fs.readFileSync(OQ_FILE, 'utf8');
  const out = [];
  for (const r of tableRows(md)) {
    const sec = Object.keys(OQ_SECTIONS).find((s) => r.section.startsWith(s));
    const id = r.cells[0]?.match(/OQ-\d+/)?.[0];
    if (!sec || !id) continue;
    const text = r.cells[1];
    const why = r.cells[2] ?? '';
    const link = (why.match(/\]\(([^)]+)\)/) || text.match(/\]\(([^)]+)\)/) || [])[1];
    const item = {
      oqId: id,
      title: `${id}: ${firstSentence(text)}`,
      text: text.replace(/\*\*/g, '').replace(/`/g, '').replace(/\[([^\]]+)\]\([^)]*\)/g, '$1'),
      source: { type: 'oq', ref: `doc/@work/questions/OPEN-QUESTIONS.md#${id}`, oq: id },
      ...OQ_SECTIONS[sec],
      notes: [link ? `owner doc: doc/${link.replace(/^\.\//, '')}` : null, /Blocked/.test(sec) ? `why stuck: ${why.replace(/\*\*/g, '')}` : null].filter(Boolean),
    };
    if (/closable|close it|answered/i.test(text + why)) { item.closable = true; }
    if (/owner-owed|owner to confirm/i.test(text + why)) { item.decision = 'OWNER'; }
    if (/needs the bench|on the bench:|bench-verify|bench check passes/i.test(text + why)) { item.decision = 'BENCH'; }
    out.push(item);
  }
  return out;
}

function cmdImport(a) {
  const candidates = [];
  const map = a.map ? new Map(readJson(a.map).rows.map((r) => [r.key, r])) : new Map();
  const overrides = a.overrides ? readJson(a.overrides) : {};
  const unmatched = [];
  if (a.review) {
    for (const c of importReview(path.resolve(a.review))) {
      const m = map.get(c.reviewKey);
      const item = { ...c };
      if (m) {
        if (m.status === 'done') {
          item.status = 'done';
          const commits = m.commits || [];
          item.evidence = { commit: commits[0]?.sha ?? null, repo: commits[0] && commits[0].repo !== 'deck' ? commits[0].repo : undefined, commits, notes: m.evidence };
        } else if (m.status === 'decided-no-change') {
          item.status = 'wontdo'; item.decision = 'SELF';
          item.evidence = { notes: `owner decided no change: ${m.evidence}` };
        } else {
          if (m.remaining) item.notes = [...item.notes, `still open: ${m.remaining}`];
          if (m.oq) item.source = { ...item.source, oq: m.oq };
          if (m.status === 'tracked') item.trackedBy = m.oq;
        }
      } else unmatched.push(c.reviewKey);
      candidates.push(item);
    }
  }
  const oqItems = a.oq ? importOq() : [];
  for (const o of oqItems) {
    if (o.closable && o.decision !== 'BENCH' && o.decision !== 'OWNER') {
      Object.assign(o, { title: `close ${o.oqId} (answered): ${o.title.replace(/^OQ-\d+:\s*/, '')}`, kind: 'doc', severity: 'low', files: [] });
    }
    candidates.push(o);
  }
  // Review rows already tracked by an open OQ collapse into that OQ's item.
  for (const c of candidates.filter((c) => c.trackedBy)) {
    const o = candidates.find((x) => x.oqId === c.trackedBy);
    if (o) { o.notes = [...o.notes, `also review ${c.reviewIds.join(', ')}: ${c.title}`]; c.drop = true; }
  }
  for (const c of candidates) {
    const ov = overrides[c.oqId] || (c.reviewIds && (overrides[c.reviewIds.join(',')] || (c.reviewIds.length === 1 && overrides[c.reviewIds[0]])));
    if (ov) { const notes = ov.notes ? [...(c.notes || []), ...ov.notes] : c.notes; Object.assign(c, ov, { notes }); }
  }
  const extra = a.extra ? readJson(a.extra) : [];
  const final = [...candidates.filter((c) => !c.drop), ...extra].map((c) => {
    const { reviewKey, reviewIds, oqId, closable, trackedBy, drop, ...rest } = c;
    // A finding already fixed or declined needs no owner or bench any more.
    if (['done', 'wontdo'].includes(rest.status) && rest.decision !== 'SELF') rest.decision = 'SELF';
    if (rest.decision === 'OWNER' && !rest.status) rest.status = 'blocked-owner';
    if (rest.decision === 'BENCH') {
      rest.status = rest.status && rest.status !== 'proposed' ? rest.status : 'blocked-bench';
      rest.benchCheck ??= rest.text ?? rest.title;
    }
    delete rest.text;
    return rest;
  });
  const importDir = path.join(BACKLOG_DIR, 'import');
  writeJson(path.join(importDir, 'candidates.json'), final);
  if (!a.merge) finish({ ok: true, candidates: final.length, unmatched, file: path.join(importDir, 'candidates.json') });
  const res = addAll(final, 'manual');
  commitWrite({ imported: res.added.length, merged: res.merged, unmatched });
}

// -------------------------------------------------------------- mutations ---

function addAll(raws, sourceType) {
  const items = loadItems();
  const added = [], merged = [];
  for (const raw of raws) {
    const probe = normalize(raw, { id: 'BL-000', sourceType });
    const dup = items.find((i) => i.dedupeKey === probe.dedupeKey);
    if (dup) {
      dup.notes.push(`duplicate proposal ${today()}: ${raw.title}${raw.notes?.length ? ` — ${raw.notes.join('; ')}` : ''}`);
      dup.updated = today();
      saveItem(dup);
      merged.push({ title: raw.title, into: dup.id });
      continue;
    }
    const id = claimId(items);
    const item = normalize(raw, { id, sourceType });
    saveItem(item);
    items.push(item);
    added.push(id);
  }
  return { added, merged };
}

function setPath(obj, dotted, value) {
  const keys = dotted.split('.');
  let o = obj;
  for (const k of keys.slice(0, -1)) o = o[k] ??= {};
  o[keys.at(-1)] = value;
}

const parseValue = (v) => { try { return JSON.parse(v); } catch { return v; } };
const jsonArg = (v) => (fs.existsSync(String(v)) ? readJson(v) : JSON.parse(v));

function cmdUpdate(id, a) {
  if (!ID_RE.test(id || '')) fail('usage: update <BL-nnn> [--set k=v] [--attempt json] [--evidence json] [--note text]');
  const items = loadItems();
  const item = items.find((i) => i.id === id);
  if (!item) fail(`no item ${id}`, 1);
  for (const s of list(a.set).length ? [].concat(a.set) : []) {
    const eq = String(s).indexOf('=');
    if (eq < 0) fail(`--set needs k=v: ${s}`);
    setPath(item, s.slice(0, eq), parseValue(s.slice(eq + 1)));
  }
  if (a.attempt) item.attempts.push(jsonArg(a.attempt));
  if (a.evidence) item.evidence = { ...item.evidence, ...jsonArg(a.evidence) };
  for (const n of a.note ? [].concat(a.note) : []) item.notes.push(String(n));
  item.updated = today();
  saveItem(item);
  closePlans();
  commitWrite({ updated: id, status: item.status });
}

function cmdGroup(ids, a) {
  if (ids.length < 2) fail('usage: group <id,id,..> [--unit U-name] [--safety]');
  const items = loadItems();
  const ms = ids.map((id) => items.find((i) => i.id === id) ?? fail(`no item ${id}`, 1));
  const bad = ms.filter((m) => !['proposed', 'ready'].includes(m.status) || m.decision !== 'SELF' || m.plan);
  if (bad.length) fail(`only proposed/ready SELF non-plan items group: ${bad.map((m) => `${m.id} ${m.status}/${m.decision}`).join(', ')}`, 1);
  if (ms.length > MAX_UNIT) fail(`a unit holds at most ${MAX_UNIT} items`, 1);
  const kinds = new Set(ms.map((m) => m.kind));
  if (kinds.size > 1) fail(`one kind per unit, got ${[...kinds].join(', ')}`, 1);
  const safety = ms.some((m) => m.safety);
  if (safety && ms.some((m) => !m.safety) && !a.safety) fail('mixing safety and non-safety items: pass --safety to treat the whole unit as safety', 1);
  const unit = a.unit && a.unit !== true ? String(a.unit) : `U-${ms.map((m) => m.id).sort()[0]}`;
  for (const m of ms) {
    m.unit = unit;
    if (safety) m.safety = true;
    m.updated = today();
    saveItem(m);
  }
  commitWrite({ unit, members: ids });
}

function cmdUngroup(ids) {
  const items = loadItems();
  for (const id of ids) {
    const m = items.find((i) => i.id === id) ?? fail(`no item ${id}`, 1);
    if (m.parent) fail(`${id} is a todo of plan ${m.parent}; it stays in the plan's unit`, 1);
    m.unit = null; m.updated = today(); saveItem(m);
  }
  commitWrite({ ungrouped: ids });
}

function cmdExpand(planId, src, a) {
  const items = loadItems();
  const plan = items.find((i) => i.id === planId) ?? fail(`no item ${planId}`, 1);
  if (!src) fail('usage: expand <planId> <todos.json|-> [--sequential]');
  const todos = [].concat(src === '-' ? JSON.parse(fs.readFileSync(0, 'utf8')) : readJson(src));
  const existing = items.filter((i) => i.parent === planId && !['done', 'wontdo'].includes(i.status)).length;
  if (existing + todos.length > MAX_UNIT) fail(`a plan's unit holds at most ${MAX_UNIT} open todos; split the plan`, 1);
  plan.plan = true; plan.unit = plan.id;
  if (plan.status === 'ready') plan.status = 'in-progress';
  plan.updated = today();
  saveItem(plan);
  const raws = todos.map((t) => ({
    kind: plan.kind, severity: plan.severity, safety: plan.safety, decision: 'SELF', gates: plan.gates,
    source: { type: plan.source.type, ref: plan.source.ref, oq: plan.source.oq },
    ...t, parent: plan.id, unit: plan.id,
  }));
  const res = addAll(raws, plan.source.type);
  if (a.sequential) {
    let prev = null;
    for (const id of res.added) {
      const it = loadItems().find((i) => i.id === id);
      if (prev && !it.deps.includes(prev)) { it.deps.push(prev); saveItem(it); }
      prev = id;
    }
  }
  commitWrite({ plan: plan.id, ...res });
}

/** A plan closes when its last child does. */
function closePlans() {
  const items = loadItems();
  for (const p of items.filter((i) => i.plan && !['done', 'wontdo'].includes(i.status))) {
    const kids = items.filter((i) => i.parent === p.id);
    if (kids.length && kids.every((k) => ['done', 'wontdo'].includes(k.status)) && kids.some((k) => k.status === 'done')) {
      p.status = 'done';
      // A plan has no commit of its own: it inherits its children's (the last one as `commit`).
      const commits = kids.filter((k) => k.status === 'done' && k.evidence?.commit).map((k) => ({ sha: k.evidence.commit, repo: k.evidence.repo ?? 'deck' }));
      p.evidence = { ...p.evidence, commit: p.evidence.commit ?? commits.at(-1)?.sha ?? null, commits: [...(p.evidence.commits ?? []), ...commits], notes: p.evidence.notes || `children: ${kids.map((k) => k.id).join(', ')}` };
      p.updated = today();
      saveItem(p);
    }
  }
}

function cmdCompact(a) {
  const minAge = Number(a['min-age-commits'] ?? 10);
  const removed = [];
  for (const i of loadItems()) {
    if (i.status === 'wontdo' && a.wontdo) { fs.unlinkSync(itemPath(i.id)); removed.push(i.id); continue; }
    if (i.status !== 'done') continue;
    const sha = i.evidence.commit;
    if (!sha || (i.evidence.repo && i.evidence.repo !== 'deck')) continue;
    if (git(DECK_ROOT, ['merge-base', '--is-ancestor', sha, 'main']).code !== 0) continue;
    const age = Number(git(DECK_ROOT, ['rev-list', '--count', `${sha}..HEAD`]).stdout.trim());
    if (age >= minAge) { fs.unlinkSync(itemPath(i.id)); removed.push(i.id); }
  }
  commitWrite({ removed });
}

// ------------------------------------------------------------------- main ---

function filterItems(items, a) {
  const f = (key, field) => { const want = list(a[key]); return want.length ? (i) => want.includes(i[field]) : () => true; };
  return items.filter(f('status', 'status')).filter(f('kind', 'kind')).filter(f('sev', 'severity')).filter(f('decision', 'decision'));
}

const strip = ({ __file, ...i }) => i;

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const a = parseArgs();
  const [cmd, arg] = a._;
  switch (cmd) {
    case 'validate': {
      const v = validate(loadItems());
      finish({ ok: !v.errors.length, count: loadItems().length, ...v }, v.errors.length ? 1 : 0);
      break;
    }
    case 'list': {
      const items = filterItems(loadItems(), a);
      finish({ ok: true, count: items.length, items: items.map((i) => (a.full ? strip(i) : { id: i.id, status: i.status, severity: i.severity, kind: i.kind, decision: i.decision, title: i.title })) });
      break;
    }
    case 'show': {
      const i = loadItems().find((x) => x.id === arg);
      if (!i) fail(`no item ${arg}`, 1);
      finish({ ok: true, item: strip(i), commit: i.status === 'done' ? i.evidence.commit ?? null : null });
      break;
    }
    case 'next-unit':
    case 'next-batch': {
      const items = loadItems();
      let dirty = [];
      if (a.dirty === 'auto') dirty = dirtyPaths(DECK_ROOT);
      else if (a.dirty) dirty = jsonArg(a.dirty);
      const kinds = list(a.kinds);
      const r = nextUnits(items, {
        max: Number(a.max ?? 3), kinds: kinds.length ? kinds : undefined,
        floor: a['severity-floor'] ?? 'low', dirty, exclude: list(a.exclude),
      });
      const units = r.units.map((u) => ({ ...u, members: u.members.map(strip) }));
      const conflicts = r.rejected.filter((x) => x.conflict);
      finish({ ok: true, units, readyCount: items.filter((i) => i.status === 'ready').length, rejected: r.rejected, dirty, conflictsOnly: units.length === 0 && conflicts.length > 0 });
      break;
    }
    case 'add': {
      if (!arg) fail('usage: add <item.json|->');
      const raw = arg === '-' ? JSON.parse(fs.readFileSync(0, 'utf8')) : readJson(arg);
      const res = addAll([].concat(raw), a['source-type'] ?? 'manual');
      commitWrite(res);
      break;
    }
    case 'update': cmdUpdate(arg, a); break;
    case 'group': cmdGroup(list(a._.slice(1)), a); break;
    case 'ungroup': cmdUngroup(list(a._.slice(1))); break;
    case 'expand': cmdExpand(arg, a._[2], a); break;
    case 'import': cmdImport(a); break;
    case 'render': finish({ ok: true, rendered: render(loadItems()) }); break;
    case 'compact': cmdCompact(a); break;
    default:
      fail('usage: backlog.mjs validate|list|show|next-unit|group|ungroup|expand|add|update|import|render|compact (see the header comment)');
  }
}
