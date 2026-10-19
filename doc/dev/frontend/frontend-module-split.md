# Frontend module split — cms vs command-deck

## Purpose

Both Spring Boot modules ship their *own* frontend (their own `package.json`,
`vite.config.ts`, `index.tsx`, `views/`, `components/`, `generated/`). The
deck app also **imports source files from the cms frontend** through a Vite
alias. This page lists what is duplicated, what is shared and what is unique
to each side, plus a recommendation that is not current state.

## Contents

- [Diagram — Venn-style](#diagram--venn-style)
- [What is duplicated](#what-is-duplicated)
- [What is shared (via the alias)](#what-is-shared-via-the-alias)
- [What is unique to each module](#what-is-unique-to-each-module)
- [Recommendation (NOT current state)](#recommendation-not-current-state)
- [Where to look in the code](#where-to-look-in-the-code)

## Diagram — Venn-style

```mermaid
flowchart LR
    subgraph CMS["cms/src/main/frontend (authored)"]
        cms_views[views: admin/user, customer, login,<br/>project/**, sample, system/**, test/**, result/{resultId}/{image,result}]
        cms_components[components/: autocrud, combobox, control,<br/>dashboard/{LogComponent, ResultViewer},<br/>owner, placeholder]
        cms_util[util/: auth.ts, model.ts, service.ts]
        cms_model[model/: init.ts, owner/, sample/]
        cms_themes[themes/breaktest-command-deck<br/>duplicated byte-for-byte in deck — not aliased]
    end

    subgraph SHARED["Shared via alias 'cms' -> ../cms/src/main/frontend"]
        shared[Used by command-deck:<br/>cms/util/auth, cms/components/owner/*,<br/>cms/components/autocrud/AutoCrud,<br/>cms/components/combobox/*,<br/>cms/model/{owner,sample}/*,<br/>cms/components/dashboard/LogComponent]
    end

    subgraph DECK["command-deck/src/main/frontend (authored)"]
        deck_views[views: @index, control,<br/>result/{resultId}/tracking, run]
        deck_components[components/dashboard: InfoBoard, LiveTestResult<br/>components/webcam: DistanceMeasureCam,<br/>tracking/{AreaSelector, CamShiftTracking}]
        deck_service[service/StatusService.ts<br/>(STOMP singleton — unique to deck)]
    end

    CMS -.alias 'cms'.-> SHARED
    SHARED -.imported via alias.-> DECK

    note["@layout.tsx is duplicated nearly verbatim<br/>(deck adds &lt;InfoBoard/&gt; and &lt;SimulatedModeBanner/&gt;)<br/>— candidate for extraction"]
    deck_views -.see.-> note
    cms_views -.see.-> note
```

(Source: [`doc/diagrams/src/frontend-venn.mmd`](../../diagrams/src/frontend-venn.mmd).)

## What is duplicated

These files exist in *both* `cms/src/main/frontend/` and
`command-deck/src/main/frontend/` and are nearly verbatim copies:

- `index.html`, `index.tsx`, `themes/` — the Vaadin/Hilla bootstrap;
  unavoidable, since each module produces its own JAR with its own bundle.
- `views/@layout.tsx` — the MainLayout component. The deck variant is
  the cms variant plus `<InfoBoard/>` in the drawer and
  `<SimulatedModeBanner/>`. The most obvious extraction candidate.
- `views/@index.tsx` — both render a landing screen, but they diverge (the
  deck index subscribes to load-cell telemetry; the cms one does not).
- `package.json` and `vite.config.ts` — the deck variant is a
  superset (custom route plugin, the `cms` alias, a computed `resolve.dedupe`
  list); the cms one is a minimal stub. More a shared dependency-pinning
  problem than duplication — see
  [`build-and-tooling.md`](build-and-tooling.md).
- The whole `generated/` directory — auto-generated, ignored.

## What is shared (via the alias)

The deck app reaches into the cms tree at compile time. The actual import
sites in `command-deck/src/main/frontend/`:

| cms file | Deck consumer |
|---|---|
| `cms/util/auth.ts` (`useAuth`, `AuthProvider`) | `command-deck/src/main/frontend/views/@layout.tsx:5` |
| `cms/components/owner/OwnerSelector.tsx` | `views/run.tsx:10` |
| `cms/components/owner/createEmptyValueProxy.tsx` | `views/run.tsx:14` |
| `cms/components/owner/CurrentUserGate.tsx` | `views/run.tsx:15` |
| `cms/components/autocrud/AutoCrud.tsx` | `views/run.tsx:13` |
| `cms/components/combobox/AutoComboBox.tsx`, `combobox/service.ts` | `views/run.tsx:3,4` |
| `cms/model/runStatus.tsx` | `views/run.tsx:14` |
| `cms/model/owner/ownerGridColumn` | `views/run.tsx:15` |
| `cms/model/sample/sampleGridColumn` | `views/run.tsx:16` |
| `cms/components/dashboard/LogComponent.tsx`, `dashboard/logBuffer.ts` | `components/dashboard/LiveTestResult.tsx:24,25` |
| `cms/components/ErrorBoundary.tsx` | `views/@layout.tsx:8`, `components/dashboard/LiveTestResult.tsx:26` |
| `cms/generated/file-routes` (the cms-side route tree) | `routes.tsx:4` (also merged at build by `customFileSystemRouterPlugin`) |

The deck app reuses the CRUD/owner/combobox/auth toolkit from cms without
publishing it as a package.

## What is unique to each module

**cms only:**
- All `@Entity`-bound CRUD views: `customer`, `project/**`, `sample`,
  `system/**`, `test/**`, `result/:id/{image,result}`, `admin/user`, `login`.
- Form helper components in `components/control/`.
- `ResultViewer` (post-run static result) and the `Placeholder`.

**command-deck only:**
- Hardware-aware views: `@index`, `control`, `run`,
  `result/:id/tracking`.
- Real-time UI: `InfoBoard`, `LiveTestResult`/`TestResultGraph`.
- `StatusService.ts` — the STOMP singleton; the cms module has nothing
  to push because it has no `WebSocketConfig`-publishing broadcasters.
- `components/webcam/*` (provisional — see [`component-inventory.md`](component-inventory.md#provisional--webcam-tracking-subsystem)).
- Hilla TS clients for `TestRunnerService`, `DeviceInfoService`,
  `SuckService` (only generated where the corresponding Java class lives).

## Recommendation (NOT current state)

> *Recommendation only. The repo implements the alias-based approach
> described under "What is shared".*

If the `@layout.tsx` duplication, the alias-and-`dedupe` setup in
`command-deck/vite.config.ts` and the build-time route merge in
`customFileSystemRouterPlugin.ts` come to outweigh the convenience of direct
source imports:

1. **Promote the shared surface to a third frontend package**, e.g.
   `shared-frontend/` with its own `package.json` exporting:
   - `util/auth.ts`
   - `components/{autocrud, combobox, owner, control, placeholder}/*`
   - `components/dashboard/LogComponent` and any future shared chart pieces
   - `model/{owner, sample}/*`
2. Both `cms` and `command-deck` consume it as a workspace dependency
   (`"workspaces": ["cms", "command-deck", "shared-frontend"]` at the root
   package, or via `npm`/`pnpm` workspaces).
3. Drop the `resolve.dedupe` list — with proper workspaces, Node module
   resolution finds a single hoisted copy.
4. Replace the build-time `customFileSystemRouterPlugin` with a single
   `withFileRoutes` of an explicit route list, or extract the cms views that
   the deck actually wants to expose into the shared package as well.

Not trivial: the deck exposes the *entire* cms view tree under itself,
partly by intent (one app for the operator) and partly by accident (the
merge plugin is order-sensitive).

## Where to look in the code
- `command-deck/vite.config.ts:27-32` (`alias: { 'cms': ... }`)
- `command-deck/customFileSystemRouterPlugin.ts:73-102` (route merge)
- `command-deck/src/main/frontend/routes.tsx:1-9`
- `command-deck/src/main/frontend/views/run.tsx:1-20` (the import block is the most concentrated cms-import surface)
- `command-deck/src/main/frontend/views/@layout.tsx:5,8` vs `cms/src/main/frontend/views/@layout.tsx:5` (the divergence)
- `cms/src/main/frontend/util/auth.ts:1-7`
