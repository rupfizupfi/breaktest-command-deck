> Branch: `dev-split` — external research captured 2026-08-17.
> **Nothing evaluated here is adopted** — see [`README.md`](README.md).

# Frontend verification — what can actually test a Hilla/React app

**Status: nothing adopted.** Neither module's `package.json` has a `scripts`
block, and there is no vitest, playwright, testing-library, or testbench
dependency anywhere.

---

## Contents

- [Blunt finding: Vaadin ships nothing that tests a Hilla/React frontend](#blunt-finding-vaadin-ships-nothing-that-tests-a-hillareact-frontend)
  - [The reference example to copy](#the-reference-example-to-copy)
  - [The capability that matters most here](#the-capability-that-matters-most-here)
- [Component tests: browser mode is a correctness requirement, not a preference](#component-tests-browser-mode-is-a-correctness-requirement-not-a-preference)
- [The highest-return item: `tsc --noEmit`](#the-highest-return-item-tsc---noemit)
  - [Supporting evidence for OQ-14](#supporting-evidence-for-oq-14)
- [Lint and type-level tooling](#lint-and-type-level-tooling)
- [Recommended ladder, cheapest first](#recommended-ladder-cheapest-first)

## Blunt finding: Vaadin ships nothing that tests a Hilla/React frontend

| Option | Verdict |
|---|---|
| **TestBench** | Still **Selenium**-based, still commercial — requires Vaadin Pro at **$159/developer/month**. Its value is a high-level API for *Flow* components and shadow-DOM traversal. **Buying it for a pure-Hilla app would be close to pure waste.** |
| **Free browserless testing (new in 25.1)** | Real — genuinely moved to Apache 2.0 in Feb 2026, and 25.2 gave it a "more Playwright-like" API. **But it is Flow-only.** It drives the *server-side Flow component tree*; a Hilla view is client-side TSX with no server-side representation, so there is nothing for it to introspect. Architecturally necessary, not an oversight. |
| **Karibu-Testing** | Apache 2.0 and free, but a server-side Flow component-tree simulator with **no v25 module**, and its README states there is no browser and no JavaScript. Zero Hilla coverage. |

Confidence **HIGH**. ⚠️ Note one marketing-vs-technical claim to discount:
Vaadin's own Playwright post says "for large-scale apps, TestBench remains the
obvious choice" — that is a sales position, argued only from Flow component
helpers.

**Vaadin's own docs point at Playwright instead** — and, usefully, ship a
working example on almost exactly this stack.

### The reference example to copy

`vaadin/flow-hilla-hybrid-example`, branch **`v25`**: React 19.2.8, React Router
7.18.2, Vite 8.0.16, TypeScript 6.0.3, Playwright 1.60, Vaadin 25.2.7. Flow
views tested in Java Playwright, **Hilla views tested in TypeScript
Playwright**. Its config boots Spring Boot via a `webServer` block, runs
Chromium only, and sets `trace: 'on-first-retry'`.

> **Copy its config; do not copy its auth.** The official example re-logs-in on
> *every* test and uses a hardcoded 200 ms sleep — an anti-pattern Playwright's
> own docs warn against. Use a `storageState` setup project instead: log in
> once, write the state file, have other projects declare a dependency on it.
> Spring Security's `JSESSIONID` is a plain session cookie and replays cleanly.
> ⚠️ It does not self-refresh, so server restart or session expiry invalidates
> the stored state — regenerate per run.

### The capability that matters most here

**`page.routeWebSocket()`** lets the UI be driven from a *synthetic* STOMP
load-cell stream. For a lab-instrument control panel, that is the difference
between an E2E suite that can run and one that cannot — a destructive-test flow
becomes testable with no hardware attached. Playwright's **Clock API** is
similarly relevant for the time-based cyclic runs. (**MEDIUM** — the APIs are
documented, but no Hilla/STOMP-specific example was found.)

For live data generally, prefer web-first assertions with auto-retry
(`expect(locator).not.toHaveText('0.0')`, `expect.poll`, `expect(...).toPass()`)
over WebSocket introspection or manual wait loops.

---

## Component tests: browser mode is a correctness requirement, not a preference

The views render `@vaadin/react-components` — **custom elements with shadow
DOM**. jsdom has had custom-element support for years but **does not usefully
implement shadow roots**, so `shadowRoot` queries fail. Vaadin's docs mandating
browser mode is therefore not a style choice. (**HIGH**.) It also sidesteps an
open Vitest 4 / jsdom incompatibility entirely.

**The mocking seam for Hilla is `vi.spyOn` on the generated module.** The
generator emits one TS module per `@BrowserCallable` service, exporting each
method as a named function — that module boundary *is* the seam, and mocking
there keeps the call type-checked against the generated signature, which is the
whole point of Hilla.

**Do not use MSW for Hilla calls.** Hilla posts to `/connect/<Service>/<method>`
with its own envelope; intercepting at HTTP level means hand-maintaining that
wire format and discarding the generated types. No Hilla+MSW precedent was found
anywhere. (MSW *is* the right tool for `cms`'s plain `/api/**` endpoints —
different seam, different tool.)

> ⚠️ **Vaadin's official Hilla testing guide will not run.** It is written for
> Vitest 1.x-era APIs — `@vitest/browser` plus a webdriverio string provider,
> `browser.name`, `SpyInstance` — all removed or renamed in Vitest 4. The page
> is byte-identical between the `/latest/` and `/next/` doc channels, i.e.
> untouched for Vaadin 25. The current shape uses `@vitest/browser-playwright`
> with `browser.instances[]`, which also means **one browser engine serves both
> component and E2E tests**.

**How alone this project would be:** a Vaadin Forum thread *"How to test
@BrowserCallable"*, opened **2026-08-08** by a well-known Vaadin expert, drew
only speculative replies and **no Vaadin staff response**. There is no blessed
full-stack Hilla testing pattern. Nothing well-known is being missed.

---

## The highest-return item: `tsc --noEmit`

Because Hilla generates the TypeScript client from Java **bytecode**, a backend
signature change — renaming a method, changing a parameter type, adding a DTO
field, changing nullability — becomes a **TypeScript compile error at the call
site**. That is a free cross-language contract check between Spring and React,
covering exactly the breakage a solo developer hits most often and that an E2E
suite is slowest to catch.

It does **not** check runtime behaviour, auth enforcement, WebSocket wiring, or
rendering. It is a *contract* check, not a *behaviour* check.

**This answers OQ-17** ("can the Hilla generator run standalone, no JVM boot,
for a CI-only TS typecheck?"). The typecheck requires
`src/main/frontend/generated/` to exist, so **CI must run the Gradle generation
step before `tsc`** — an ordering problem, not a blocker. Note this is also the
one legitimate reason people commit `generated/`, and it is solved by ordering
CI steps rather than by committing (**OQ-14**).

### Supporting evidence for OQ-14

Vaadin's source-control page lists `src/main/frontend/generated/` under
*ignore*, and their own v25 Hilla example gitignores it explicitly. The
documented failure mode is **stale generated files surviving an upgrade** —
Vaadin issue flow#19526, still open, where leftover generated TS imported a
package absent from `package.json` and Vite failed module resolution. Relevant
because this project is on 25.2.6 and will upgrade again. Two prerequisites
before untracking: confirm nothing in there was hand-edited (once ignored, edits
vanish silently), and fix the CI ordering above.

## Lint and type-level tooling

- **typescript-eslint supports TypeScript 6 today** — supported range
  `>=4.8.4 <6.1.0`, ESLint `^8.57 || ^9 || ^10`, released weekly. The TS 6
  tracking issue is closed. This is the tool that usually lags; it does not.
- **ESLint 10 removed eslintrc entirely** — flat config only, no opt-out.
  ESLint 9 reached EOL around 2026-08-06 (**MEDIUM**), so go straight to 10.
- Type-aware rules are what earn their keep here: floating promises and
  unhandled async in the STOMP subscriptions, and unsafe `any` leaking from
  generated boundaries.
- ⚠️ **Do not chase TypeScript 7.** The Go-native compiler reached RC in 2026,
  but typescript-eslint depends on a stable programmatic API that does not land
  until 7.1; interim guidance is to stay on TS 6.0.x. This project's 6.0.3 —
  matching Vaadin's own v25 example — is correctly positioned. (**MEDIUM**.)

## Recommended ladder, cheapest first

| # | Step | Effort |
|---|---|---|
| 1 | `tsc --noEmit` in CI (after Gradle generation) | ~1 h |
| 2 | ESLint 10 flat config + typescript-eslint type-aware rules | ~2 h |
| 3 | A handful of Playwright specs: login, each route renders, one CRUD round-trip | ~4 h |
| 4 | Vitest browser-mode component tests — only for genuinely tricky logic (limit checks, unit conversion, cyclic state machine) | as needed |
| 5 | Visual regression — **defer** | — |

**Why defer visual regression:** development is on Windows 11 and any CI would
be Linux; font rendering differs, so locally-generated baselines will not
survive CI. The one case that might justify it later is a *component-level*
screenshot of the live chart, with baselines generated inside the CI container
and the numeric readout masked so the stream does not cause false diffs.

Sources: [Vaadin testing overview](https://vaadin.com/docs/latest/flow/testing) ·
[Playwright page](https://vaadin.com/docs/latest/flow/testing/playwright) ·
[Browserless testing](https://vaadin.com/docs/latest/flow/testing/browserless) ·
[Hilla testing guide (stale)](https://vaadin.com/docs/latest/hilla/guides/testing) ·
[Source control](https://vaadin.com/docs/latest/flow/configuration/source-control) ·
[flow-hilla-hybrid-example@v25](https://github.com/vaadin/flow-hilla-hybrid-example/tree/v25) ·
[flow#19526](https://github.com/vaadin/flow/issues/19526) ·
[Forum: how to test @BrowserCallable](https://vaadin.com/forum/t/how-to-test-browsercallable/179694) ·
[Playwright auth](https://playwright.dev/docs/auth) ·
[Vitest 4](https://vitest.dev/blog/vitest-4) ·
[typescript-eslint versions](https://typescript-eslint.io/users/dependency-versions/) ·
[ESLint 10](https://eslint.org/blog/2026/02/eslint-v10.0.0-released/)
