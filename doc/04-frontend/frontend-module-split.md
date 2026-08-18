# Frontend module split — cms vs command-deck

> Branch: `dev-split` &middot; Snapshot: 2026-04-25 &middot; 04-frontend

## Purpose

Both Spring Boot modules ship their *own* frontend (their own `package.json`,
`vite.config.ts`, `index.tsx`, `views/`, `components/`, `generated/`). On
`dev-split` the deck app additionally **imports source files from the cms
frontend** through a Vite alias. This page lays out exactly what is
duplicated, what is genuinely shared, and what is unique to each side —
and ends with a short, clearly-flagged recommendation.

## Contents

- [Diagram — Venn-style](#diagram--venn-style)
- [What is duplicated](#what-is-duplicated)
- [What is genuinely shared (via the alias)](#what-is-genuinely-shared-via-the-alias)
- [What is unique to each module](#what-is-unique-to-each-module)
- [Recommendation (NOT current state)](#recommendation-not-current-state)
- [Where to look in the code](#where-to-look-in-the-code)
- [Open questions](#open-questions)

## Diagram — Venn-style

```mermaid
flowchart LR
    subgraph CMS["cms/src/main/frontend (authored)"]
        cms_views[views: admin/user, customer, login,<br/>project/**, sample, system/**, test/**, result/{resultId}/{image,result}]
        cms_components[components/: autocrud, combobox, control,<br/>dashboard/{LogComponent, ResultViewer},<br/>owner, placeholder]
        cms_util[util/: auth.ts, model.ts, service.ts]
        cms_model[model/: init.ts, owner/, sample/]
    end

    subgraph SHARED["Shared via alias 'cms' -> ../cms/src/main/frontend"]
        shared[Used by command-deck:<br/>cms/util/auth, cms/components/owner/*,<br/>cms/components/autocrud/AutoCrud,<br/>cms/components/combobox/*,<br/>cms/model/{owner,sample}/*,<br/>cms/components/dashboard/LogComponent]
    end

    subgraph DECK["command-deck/src/main/frontend (authored)"]
        deck_views[views: @index, control,<br/>result/{resultId}/tracking, run]
        deck_components[components/dashboard: InfoBoard, LiveTestResult<br/>components/webcam: DistanceMeasureCam, tracking/*]
        deck_service[service/StatusService.ts<br/>(STOMP singleton — unique to deck)]
    end

    CMS -.alias 'cms'.-> SHARED
    SHARED -.imported via alias.-> DECK
```

(Source: [`doc/diagrams/src/frontend-venn.mmd`](../diagrams/src/frontend-venn.mmd).)

## What is duplicated

These files exist in *both* `cms/src/main/frontend/` and
`command-deck/src/main/frontend/` and are nearly verbatim copies:

- `index.html`, `index.tsx`, `themes/` — the Vaadin/Hilla bootstrap.
  This duplication is unavoidable: each module produces its own JAR with its
  own bundle.
- `views/@layout.tsx` — the MainLayout component. The deck variant is
  the cms variant *plus* a single `<InfoBoard/>` element in the drawer. This
  is the most obvious extraction candidate.
- `views/@index.tsx` — both render a small landing screen. They diverge
  meaningfully (the deck index subscribes to load-cell telemetry; the cms
  one does not), so the duplication is more nominal than actual.
- `package.json` and `vite.config.ts` — the deck variant is a
  superset (custom route plugin, the `cms` alias, a computed `resolve.dedupe`
  list); the cms one is a minimal stub. Not really duplication, more a shared
  dependency-pinning problem — see
  [`build-and-tooling.md`](./build-and-tooling.md).
- The whole `generated/` directory — auto-generated, ignored.

## What is genuinely shared (via the alias)

The deck app reaches into the cms tree at compile time. The actual import
sites in `command-deck/src/main/frontend/`:

| cms file | Deck consumer |
|---|---|
| `cms/util/auth.ts` (`useAuth`, `AuthProvider`) | `command-deck/src/main/frontend/views/@layout.tsx:5` |
| `cms/components/owner/OnwerSelector.tsx` | `views/run.tsx:13` |
| `cms/components/owner/createEmptyValueProxy.tsx` | `views/run.tsx:15` |
| `cms/components/autocrud/AutoCrud.tsx` | `views/run.tsx:16` |
| `cms/components/combobox/AutoComboBox.tsx`, `combobox/service.ts` | `views/run.tsx:3,4` |
| `cms/model/owner/ownerGridColumn` | `views/run.tsx:17` |
| `cms/model/sample/sampleGridColumn` | `views/run.tsx:18` |
| `cms/components/dashboard/LogComponent.tsx` | `components/dashboard/LiveTestResult.tsx:12` |
| `cms/generated/file-routes` (the cms-side route tree) | `routes.tsx:4` (also merged at build by `customFileSystemRouterPlugin`) |

So the deck app reuses the entire CRUD/owner/combobox/auth toolkit from cms
without ever publishing it as a package.

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
- `components/webcam/*` (provisional — see commit `2bfa0a0`).
- Hilla TS clients for `TestRunnerService`, `DeviceInfoService`,
  `SuckService` (only generated where the corresponding Java class lives).

## Recommendation (NOT current state)

> *Recommendation only. The repo today implements the alias-based approach
> below described under "What is genuinely shared".*

If the duplication around `@layout.tsx`, the alias-and-`dedupe` gymnastics in
`command-deck/vite.config.ts`, and the build-time route merge
in `customFileSystemRouterPlugin.ts` start to weigh more than the convenience
of monorepo-friendly source imports, a reasonable next step would be:

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

This is **not** trivial — the deck currently exposes the *entire* cms
view tree under itself, which is partly intentional (one app for the
operator) and partly accidental (the merge plugin is order-sensitive).

## Where to look in the code
- `command-deck/vite.config.ts:27-32` (`alias: { 'cms': ... }`)
- `command-deck/customFileSystemRouterPlugin.ts:73-102` (route merge)
- `command-deck/src/main/frontend/routes.tsx:1-9`
- `command-deck/src/main/frontend/views/run.tsx:1-20` (the import block is the most concentrated cms-import surface)
- `command-deck/src/main/frontend/views/@layout.tsx:5,8` vs `cms/src/main/frontend/views/@layout.tsx:6` (the divergence)
- `cms/src/main/frontend/util/auth.ts:1-7`

## Open questions

1. **Dead `Avatar` import** in `cms/src/main/frontend/views/@layout.tsx:4`
   — imported from `@vaadin/react-components/Avatar.js`, referenced
   nowhere else in the file. Delete the line. (OQ-31)
