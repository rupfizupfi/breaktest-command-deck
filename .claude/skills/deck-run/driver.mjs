// Headless driver for breaktest-command-deck (deck-run skill).
// puppeteer-core against the system Chrome/Edge — no browser download.
//
// Commands:
//   node driver.mjs up                     boot :command-deck:bootRun if :8080 is down (detached), poll readiness
//   node driver.mjs down                   kill a stack that `up` spawned (never touches a foreign :8080)
//   node driver.mjs shots                  login + screenshot the standard route set -> ./shots/*.png, PASS/FAIL per route
//   node driver.mjs crud-smoke             customer CRUD round-trip: create -> assert in grid -> delete -> assert gone
//   node driver.mjs shot <route> <file>    one route -> ./shots/<file>
//
// Env: DECK_BASE (default http://localhost:8080), DECK_BROWSER (chrome/edge path),
//      DECK_USER / DECK_PASS (default user/user), DECK_ADMIN_USER / DECK_ADMIN_PASS (default admin/admin).

import puppeteer from 'puppeteer-core';
import { spawn } from 'node:child_process';
import { execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const skillDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(skillDir, '..', '..', '..');
const shotsDir = path.join(skillDir, 'shots');
const runDir = path.join(skillDir, '.run');
// 127.0.0.1, not localhost — Node's fetch may resolve localhost to ::1 while the server answers on IPv4.
const BASE = process.env.DECK_BASE ?? 'http://127.0.0.1:8080';
const USER = process.env.DECK_USER ?? 'user';
const PASS = process.env.DECK_PASS ?? 'user';
const ADMIN_USER = process.env.DECK_ADMIN_USER ?? 'admin';
const ADMIN_PASS = process.env.DECK_ADMIN_PASS ?? 'admin';

// Routes asserted by `shots`. deckOnly routes render a not-found shell when only :cms runs —
// they are reported but do not fail the run unless the deck module is detected.
const ROUTES = [
  { route: '/', name: 'index' },
  { route: '/customer', name: 'customer' },
  { route: '/sample', name: 'sample' },
  { route: '/project', name: 'project' },
  { route: '/test/destructive', name: 'test-destructive' },
  { route: '/test/cyclic', name: 'test-cyclic' },
  { route: '/test/timeCyclic', name: 'test-timecyclic' },
  { route: '/system/setting', name: 'system-setting' },
  { route: '/control', name: 'control', deckOnly: true },
  { route: '/run', name: 'run', deckOnly: true },
  { route: '/admin/user', name: 'admin-user', admin: true },
];

// Vaadin dev-mode first render compiles the frontend on demand — allow generous budgets.
const NAV_TIMEOUT = 120_000;

function findBrowser() {
  const candidates = [
    process.env.DECK_BROWSER,
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  ].filter(Boolean);
  for (const c of candidates) if (fs.existsSync(c)) return c;
  throw new Error('No browser found. Set DECK_BROWSER to a chrome/edge binary.');
}

async function isUp() {
  try {
    const res = await fetch(`${BASE}/login`, { signal: AbortSignal.timeout(3000), redirect: 'manual' });
    return res.status === 200;
  } catch {
    return false;
  }
}

async function launch() {
  const browser = await puppeteer.launch({
    executablePath: findBrowser(),
    headless: 'shell',
    args: ['--no-sandbox', '--window-size=1600,1000'],
    defaultViewport: { width: 1600, height: 1000 },
  });
  return browser;
}

async function login(page, user, pass) {
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle2', timeout: NAV_TIMEOUT });
  // LoginOverlay slots native inputs into the light DOM (for password managers).
  await page.waitForSelector('input[name="username"]', { visible: true, timeout: 60_000 });
  await page.type('input[name="username"]', user);
  await page.type('input[name="password"]', pass);
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 60_000 }).catch(() => {}),
    page.keyboard.press('Enter'),
  ]);
  await page.waitForFunction(() => !location.pathname.startsWith('/login'), { timeout: 30_000 });
}

async function gotoRoute(page, route) {
  const res = await page.goto(BASE + route, { waitUntil: 'networkidle2', timeout: NAV_TIMEOUT });
  // let client-side rendering settle (grids fetch after mount)
  await new Promise((r) => setTimeout(r, 1200));
  return res;
}

async function pageText(page) {
  return page.evaluate(() => document.body.innerText ?? '');
}

// Click the first element of `selector` whose trimmed text matches. DOM click works on vaadin buttons.
async function clickByText(page, selector, text, { exact = false } = {}) {
  const clicked = await page.evaluate(
    (sel, txt, ex) => {
      const els = [...document.querySelectorAll(sel)];
      const el = els.find((e) => {
        const t = (e.textContent ?? '').trim();
        return ex ? t === txt : t.includes(txt);
      });
      if (!el) return false;
      el.click();
      return true;
    },
    selector,
    text,
    exact,
  );
  if (!clicked) throw new Error(`clickByText: no ${selector} with text "${text}"`);
}

// Fill a Hilla AutoForm field by its label ("Firstname", ...). Headless keyboard events don't reliably
// reach Vaadin's slotted inputs, so set the native value and dispatch input/change (React-controlled-safe).
async function fillFieldByLabel(page, label, value) {
  const landed = await page.evaluate(
    (lbl, val) => {
      const fields = [...document.querySelectorAll('vaadin-text-field, vaadin-email-field, vaadin-number-field, vaadin-integer-field')];
      const f = fields.find((e) => (e.querySelector('label')?.textContent ?? '').trim().toLowerCase() === lbl.toLowerCase());
      const input = f?.querySelector('input');
      if (!input) return 'missing';
      input.focus();
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
      setter.call(input, val);
      input.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
      input.blur();
      return f.value === val || input.value === val ? 'ok' : `stuck at "${f.value ?? input.value}"`;
    },
    label,
    value,
  );
  if (landed !== 'ok') throw new Error(`fillFieldByLabel("${label}"): ${landed}`);
}

function ensureDirs() {
  fs.mkdirSync(shotsDir, { recursive: true });
  fs.mkdirSync(runDir, { recursive: true });
}

// ---------- commands ----------

async function cmdUp() {
  if (await isUp()) {
    console.log(`already up: ${BASE}`);
    return;
  }
  ensureDirs();
  const logPath = path.join(runDir, 'bootrun.log');
  const out = fs.openSync(logPath, 'w');
  const child = spawn('cmd.exe', ['/c', 'gradlew.bat', ':command-deck:bootRun'], {
    cwd: repoRoot,
    detached: true,
    stdio: ['ignore', out, out],
  });
  child.unref();
  fs.writeFileSync(path.join(runDir, 'boot.json'), JSON.stringify({ pid: child.pid, log: logPath }));
  console.log(`spawned :command-deck:bootRun (pid ${child.pid}), polling ${BASE} ...`);
  // Cold Vaadin dev-bundle builds are slow — 8 min budget.
  for (let i = 0; i < 96; i++) {
    if (await isUp()) {
      console.log('up ✓');
      return;
    }
    await new Promise((r) => setTimeout(r, 5000));
  }
  console.error(`FAIL: not up after 8 min. Log tail (${logPath}):`);
  const log = fs.readFileSync(logPath, 'utf8');
  console.error(log.slice(-4000));
  process.exit(1);
}

function cmdDown() {
  const bootFile = path.join(runDir, 'boot.json');
  if (!fs.existsSync(bootFile)) {
    console.log('nothing spawned by this driver (no .run/boot.json) — refusing to kill a foreign :8080');
    return;
  }
  const { pid } = JSON.parse(fs.readFileSync(bootFile, 'utf8').replace(/^﻿/, ''));
  // The recorded pid is the cmd.exe wrapper, which detaches from the gradle/java tree —
  // kill it if alive, then kill whatever listens on the port (guarded by boot.json: we booted it).
  try {
    execSync(`taskkill /PID ${pid} /T /F`, { stdio: 'ignore' });
  } catch {}
  const port = new URL(BASE).port || '80';
  try {
    // no -p filter: the JVM usually listens on IPv6 ([::]:8080). Match on the LOCAL-address
    // column, not the state word — netstat localizes "LISTENING" (German: ABHÖREN).
    const netstat = execSync('netstat -ano', { encoding: 'utf8' });
    const pids = new Set(
      netstat
        .split(/\r?\n/)
        .map((l) => l.trim().split(/\s+/))
        .filter((c) => c.length >= 5 && c[1]?.endsWith(`:${port}`))
        .map((c) => c[c.length - 1])
        .filter((p) => p && p !== '0'),
    );
    for (const p of pids) {
      console.log(`killing port ${port} listener pid ${p}`);
      try {
        execSync(`taskkill /PID ${p} /T /F`, { stdio: 'inherit' });
      } catch {}
    }
    if (!pids.size) console.log(`no listener on :${port}`);
  } catch (e) {
    console.error(`netstat/taskkill failed: ${e.message}`);
  }
  fs.unlinkSync(bootFile);
}

async function cmdShots() {
  ensureDirs();
  if (!(await isUp())) {
    console.error(`FAIL: ${BASE} is not up — run: node driver.mjs up`);
    process.exit(1);
  }
  const browser = await launch();
  let failures = 0;
  try {
    const page = await browser.newPage();
    await login(page, USER, PASS);

    // deck detection: the deck module serves /control with real content; cms-only renders a 404/empty shell.
    await gotoRoute(page, '/control');
    const controlText = await pageText(page);
    const isDeck = controlText.length > 200 && !/could not be found|not found/i.test(controlText);
    console.log(`module detected: ${isDeck ? 'command-deck (or deck routes render)' : 'cms-only (deck routes reported, not failed)'}`);

    let adminPage = null;
    for (const r of ROUTES) {
      let p = page;
      if (r.admin) {
        if (!adminPage) {
          adminPage = await (await browser.createBrowserContext()).newPage();
          await login(adminPage, ADMIN_USER, ADMIN_PASS);
        }
        p = adminPage;
      }
      const res = await gotoRoute(p, r.route);
      const status = res?.status() ?? 0;
      const text = await pageText(p);
      const file = path.join(shotsDir, `${r.name}.png`);
      await p.screenshot({ path: file, fullPage: false });
      const blank = text.trim().length < 40;
      const skip = r.deckOnly && !isDeck;
      const ok = status === 200 && !blank;
      if (!ok && !skip) failures++;
      console.log(
        `${ok ? 'PASS' : skip ? 'SKIP' : 'FAIL'}  ${r.route.padEnd(20)} status=${status} textLen=${text.trim().length} -> shots/${r.name}.png`,
      );
    }
  } finally {
    await browser.close();
  }
  if (failures) {
    console.error(`${failures} route(s) FAILED`);
    process.exit(1);
  }
  console.log('all routes PASS');
}

async function cmdCrudSmoke() {
  ensureDirs();
  if (!(await isUp())) {
    console.error(`FAIL: ${BASE} is not up — run: node driver.mjs up`);
    process.exit(1);
  }
  const marker = `drvsmoke${Date.now()}`;
  const browser = await launch();
  const checks = [];
  const check = (name, ok, extra = '') => {
    checks.push(ok);
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? ` — ${extra}` : ''}`);
  };
  try {
    const page = await browser.newPage();
    page.on('console', (m) => {
      if (m.type() === 'error' || m.type() === 'warn') console.log(`  [browser ${m.type()}] ${m.text().slice(0, 300)}`);
    });
    page.on('response', async (res) => {
      if (res.url().includes('/connect/') && res.request().method() === 'POST') {
        const body = res.ok() ? '' : ` body=${(await res.text().catch(() => '')).slice(0, 300)}`;
        console.log(`  [hilla] ${res.status()} ${res.url().split('/connect/')[1]}${body}`);
      }
    });
    await login(page, USER, PASS);
    await gotoRoute(page, '/customer');
    const snap = async (n) => page.screenshot({ path: path.join(shotsDir, `crud-${n}.png`) });

    // create
    await clickByText(page, 'vaadin-button', 'New');
    await page.waitForFunction(
      () => [...document.querySelectorAll('vaadin-text-field label')].some((l) => /firstname/i.test(l.textContent ?? '')),
      { timeout: 15_000 },
    );
    await fillFieldByLabel(page, 'Firstname', marker);
    await fillFieldByLabel(page, 'Lastname', 'DriverSmoke');
    // Customer.code @Pattern(^\d{4,5}$) rejects the empty string the form otherwise submits.
    await fillFieldByLabel(page, 'Code', '8000');
    await snap('1-filled');
    await clickByText(page, 'vaadin-button', 'Submit');
    await new Promise((r) => setTimeout(r, 1500));
    await snap('2-submitted');
    await page
      .waitForFunction((m) => document.body.innerText.includes(m), { timeout: 15_000 }, marker)
      .catch(() => {});
    let text = await pageText(page);
    check('create: marker visible in grid', text.includes(marker), marker);

    // delete: select the row (grid cell content is slotted light DOM), form "Delete...", dialog "Confirm"
    const deleteRowContaining = async (needle) => {
      await page.evaluate((m) => {
        const cell = [...document.querySelectorAll('vaadin-grid-cell-content')].find((c) => c.textContent?.includes(m));
        cell?.click();
      }, needle);
      await new Promise((r) => setTimeout(r, 800));
      await clickByText(page, 'vaadin-button', 'Delete');
      await page.waitForFunction(() => document.body.innerText.includes('Are you sure'), { timeout: 10_000 });
      await clickByText(page, 'vaadin-button', 'Confirm', { exact: true });
      await page
        .waitForFunction((m) => !document.body.innerText.includes(m), { timeout: 15_000 }, needle)
        .catch(() => {});
    };
    await deleteRowContaining(marker);
    text = await pageText(page);
    check('delete: marker gone from grid', !text.includes(marker));

    // sweep leftovers from previous failed runs (self-cleaning test data)
    for (let i = 0; i < 10; i++) {
      const stale = (await pageText(page)).match(/drvsmoke\d+/)?.[0];
      if (!stale) break;
      console.log(`  cleaning stale row: ${stale}`);
      await deleteRowContaining(stale);
    }

    if (checks.includes(false)) {
      await page.screenshot({ path: path.join(shotsDir, 'crud-smoke-fail.png') });
      console.error(`screenshot -> shots/crud-smoke-fail.png; leftover test row may need manual cleanup (${marker})`);
    }
  } finally {
    await browser.close();
  }
  if (checks.includes(false)) process.exit(1);
  console.log('crud-smoke PASS');
}

async function cmdShot(route, file) {
  if (!route || !file) {
    console.error('usage: node driver.mjs shot <route> <file.png>');
    process.exit(2);
  }
  ensureDirs();
  const browser = await launch();
  try {
    const page = await browser.newPage();
    await login(page, USER, PASS);
    const res = await gotoRoute(page, route);
    await page.screenshot({ path: path.join(shotsDir, file), fullPage: false });
    const text = await pageText(page);
    console.log(`${route} status=${res?.status()} textLen=${text.trim().length} -> shots/${file}`);
  } finally {
    await browser.close();
  }
}

// ---------- main ----------

const [cmd, ...args] = process.argv.slice(2);
const commands = {
  up: cmdUp,
  down: cmdDown,
  shots: cmdShots,
  'crud-smoke': cmdCrudSmoke,
  shot: cmdShot,
};
if (!commands[cmd]) {
  console.error(`usage: node driver.mjs <${Object.keys(commands).join('|')}>`);
  process.exit(2);
}
await commands[cmd](...args);
