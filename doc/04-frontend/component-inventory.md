# Component inventory

> Branch: `dev-split` &middot; Snapshot: 2026-04-25 &middot; 04-frontend

## Purpose

Catalogue the reusable React components under each module's
`src/main/frontend/components/` directory so a new contributor can find the
right thing instead of writing a new one. Grouped by domain. Generated files
(`generated/...`) are out of scope (see
[`hilla-generated-layer.md`](./hilla-generated-layer.md)).

## Diagram — how components are shared

The deck app reuses cms components by importing through the Vite alias
`'cms' &rarr; ../cms/src/main/frontend`. Concrete picture in
[`frontend-module-split.md`](./frontend-module-split.md) and
[`doc/diagrams/src/frontend-venn.mmd`](../diagrams/src/frontend-venn.mmd).

## Inventory — cms (`cms/src/main/frontend/components/`)

| Domain | Component | File | Used by |
|---|---|---|---|
| CRUD scaffolding | `AutoCrud` | `components/autocrud/AutoCrud.tsx` | `views/customer.tsx`, `views/sample.tsx`, `views/test/*.tsx`, deck `views/run.tsx` |
| CRUD scaffolding | `AutoCrudDialog` | `components/autocrud/AutoCrudDialog.tsx` | Internal to AutoCrud |
| CRUD scaffolding | `mediaQuery.ts`, `util.ts`, `sample.tsx`, `test.tsx` | `components/autocrud/` | Helpers + view-specific subclasses bound to the AutoCrud abstraction |
| Forms / inputs | `AutoComboBox` | `components/combobox/AutoComboBox.tsx` | Lookup-bound combobox driven by a Hilla service factory |
| Forms / inputs | `MultiSelectComboBox` | `components/combobox/MultiSelectComboBox.tsx` | Multi-select variant |
| Forms / inputs | `service.ts` (`createAutoComboBoxService`) | `components/combobox/service.ts` | Used by deck `run.tsx:49-50` to build a service-shim around `TestParameterService` / `SampleService` |
| Forms / control | `dynamicField.tsx` | `components/control/dynamicField.tsx` | Helper that picks a renderer based on a model field |
| Charts / dashboard | `LogComponent` | `components/dashboard/LogComponent.tsx` | **Reused** by deck `LiveTestResult.tsx:12` for the run-view log feed — a direct cross-module import, not a shared package (keep it that way; see [`../02-modules/shared-code-strategy.md`](../02-modules/shared-code-strategy.md)) |
| Charts / dashboard | `ResultViewer` | `components/dashboard/ResultViewer.tsx` | Renders a stored test-result CSV (post-run view) |
| Multi-tenancy | `OnwerSelector` (sic) | `components/owner/OnwerSelector.tsx` | Picks the owner field on `DataWithOwner` records (admin-only meaningful) |
| Multi-tenancy | `OwnerGridView` | `components/owner/OwnerGridView.tsx` | Tiny renderer used by `ownerGridColumn` |
| Multi-tenancy | `createEmptyValueProxy` | `components/owner/createEmptyValueProxy.tsx` | Sets up a Vaadin Form Model with an empty `owner` field as the default |
| Misc | `Placeholder` | `cms/src/main/frontend/components/placeholder/Placeholder.tsx` | Tiny WIP placeholder block. **In use — do not remove:** `cms/src/main/frontend/views/project/{projectId}/sample.tsx:9`, `cms/src/main/frontend/views/result/{resultId}/image.tsx:5`, `cms/src/main/frontend/views/result/{resultId}/result.tsx:7` |

Plus shared utilities at the top level (used by both modules via the alias):

| File | Purpose |
|---|---|
| `cms/src/main/frontend/util/auth.ts` | `useAuth()` from `@vaadin/hilla-react-auth` wired to `UserEndpoint.getAuthenticatedUser`. Both layouts import this. |
| `cms/src/main/frontend/util/model.ts`, `service.ts` | Form-model and CRUD helpers consumed by AutoCrud |
| `cms/src/main/frontend/model/owner/`, `model/sample/` | Vaadin "GridColumn" descriptors used by both `views/sample.tsx` (cms) and `views/run.tsx` (deck) |

## Inventory — command-deck (`command-deck/src/main/frontend/components/`)

| Domain | Component | File | Used by |
|---|---|---|---|
| Charts / dashboard | `InfoBoard` | `components/dashboard/InfoBoard.tsx` | Mounted in `views/@layout.tsx:42` — live frequency-converter telemetry in the drawer |
| Charts / dashboard | `LiveTestResult` (incl. inner `TestResultGraph`) | `components/dashboard/LiveTestResult.tsx` | The chart in `views/run.tsx`. See [`state-and-realtime.md`](./state-and-realtime.md). |
| Webcam / tracking *(provisional)* | `DistanceMeasureCam` | `components/webcam/DistanceMeasureCam.tsx` | `views/result/{resultId}/tracking.tsx` |
| Webcam / tracking *(provisional)* | `CamShiftTracking` factory | `components/webcam/tracking/CamShiftTracking.tsx` | Used by `DistanceMeasureCam` |
| Webcam / tracking *(provisional)* | `AreaSelector` | `components/webcam/tracking/AreaSelector.ts` | Helper for the Camshift initial bounding box |

Plus deck-only top-level helpers:

| File | Purpose |
|---|---|
| `command-deck/src/main/frontend/service/StatusService.ts` | The STOMP singleton (see [`state-and-realtime.md`](./state-and-realtime.md)). |

## Provisional — webcam tracking subsystem

Three files: `webcam/DistanceMeasureCam.tsx` (the React component) plus
`webcam/tracking/CamShiftTracking.tsx` and `webcam/tracking/AreaSelector.ts`.
`DistanceMeasureCam`:

- Loads `@techstark/opencv-js` lazily (`cvReady.then(...)`) and feeds the
  `requestAnimationFrame` capture loop into `creatCamshiftTracking(cv)`.
- Implements an in-place 2-click calibration flow converting pixel
  distance to cm.
- Has rough edges: commented imports, `@ts-ignore`, and no cleanup of the
  `requestAnimationFrame` loop on unmount.

Another pass is planned to lift the OpenCV pipeline out of the component
(OQ-28). The route
`/result/:resultId/tracking` and the `tracking.tsx` view that mounts
`<DistanceMeasureCam/>` are stable; **the component's internals are not** —
re-read the source before depending on them.

## Where to look in the code
- `cms/src/main/frontend/components/` (whole tree)
- `command-deck/src/main/frontend/components/` (whole tree)
- `command-deck/src/main/frontend/components/webcam/DistanceMeasureCam.tsx:1-125` (provisional)
- `command-deck/src/main/frontend/components/webcam/tracking/CamShiftTracking.tsx`
- Reuse via alias examples: `command-deck/src/main/frontend/views/run.tsx:4,13,15,16,17,18`
- Sharing of `useAuth` — `cms/src/main/frontend/util/auth.ts:1-7` imported by both `@layout.tsx`s

## Open questions

1. **`OnwerSelector` is a typo for "Owner"** and it has leaked into
   view-level imports in four files (`components/autocrud/sample.tsx`,
   `components/autocrud/test.tsx`, `views/project/@index.tsx`, and deck's
   `views/run.tsx` — which imports it as `OwnerSelector`, spelled
   correctly, from the misspelled path). Rename the file and fix the
   imports. (OQ-27)
2. **The webcam component mixes concerns.** `DistanceMeasureCam.tsx` holds
   the `captureFrame` loop, the OpenCV pipeline and the chart consumer in
   one module. A rewrite splitting the pipeline out of React is planned
   (confirmed 2026-08-16) — the pipeline becomes a plain class and the
   component subscribes to it. Not yet scheduled. (OQ-28)
