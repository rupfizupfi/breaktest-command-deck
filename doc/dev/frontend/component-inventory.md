# Component inventory

## Purpose

Catalogue the reusable React components under each module's
`src/main/frontend/components/` directory, grouped by domain. Generated files
(`generated/...`) are out of scope (see
[`hilla-generated-layer.md`](hilla-generated-layer.md)).

## Diagram — how components are shared

The deck app reuses cms components by importing through the Vite alias
`'cms' &rarr; ../cms/src/main/frontend`. Concrete picture in
[`frontend-module-split.md`](frontend-module-split.md) and
[`doc/diagrams/src/frontend-venn.mmd`](../../diagrams/src/frontend-venn.mmd).

## Inventory — cms (`cms/src/main/frontend/components/`)

| Domain | Component | File | Used by |
|---|---|---|---|
| CRUD scaffolding | `AutoCrud` | `components/autocrud/AutoCrud.tsx` | `views/customer.tsx`, `views/sample.tsx`, `views/test/*.tsx`, deck `views/run.tsx`. A copy clears `id` and sets `version` to `0`, so the clone persists as a new row; `__copy` (see `WithCopyFlag`) is what the form header reads. `formPropsForItem` derives per-item form props (`views/system/setting.tsx`); `onEditStateChange` reports the edited item and whether the form is dirty (deck `views/run.tsx`) |
| CRUD scaffolding | `AutoCrudDialog` | `components/autocrud/AutoCrudDialog.tsx` | Internal to AutoCrud |
| CRUD scaffolding | `mediaQuery.ts`, `util.ts`, `sample.tsx`, `test.tsx` | `components/autocrud/` | Helpers + view-specific subclasses bound to the AutoCrud abstraction |
| Forms / inputs | `AutoComboBox` | `components/combobox/AutoComboBox.tsx` | Lookup-bound combobox driven by a Hilla service factory |
| Forms / inputs | `MultiSelectComboBox` | `components/combobox/MultiSelectComboBox.tsx` | Multi-select variant |
| Forms / inputs | `service.ts` (`createAutoComboBoxService`) | `components/combobox/service.ts` | Used by deck `views/run.tsx` to build a service-shim around `TestParameterService` / `SampleService` |
| Forms / control | `dynamicField.tsx` | `components/control/dynamicField.tsx` | Picks a `Setting` editor from the stored Java type. A `java.lang.String` whose opening value reads `YYYY-MM-DD` gets a `DatePicker` bound to that same text — the shape is fixed when the form opens, and the form binding owns value and change |
| Charts / dashboard | `LogComponent` + `logBuffer.ts` | `components/dashboard/` | **Reused** by deck `LiveTestResult.tsx` for the run-view log feed, capped to its newest lines — a direct cross-module import, not a shared package (see [`dev/modules/shared-code-strategy.md`](../modules/shared-code-strategy.md)) |
| Charts / dashboard | `ResultViewer` | `components/dashboard/ResultViewer.tsx` | Renders a stored test-result CSV (post-run view); `toForceTrace` in `forceCsv.ts` computes min/max/duration in one loop |
| Shell | `ErrorBoundary` | `components/ErrorBoundary.tsx` | Both layouts around `<Outlet/>`, deck around `InfoBoard` and the live chart; contains a render error to its region |
| Multi-tenancy | `OwnerSelector` | `components/owner/OwnerSelector.tsx` | Picks the owner field on `DataWithOwner` records (admin-only meaningful) |
| Multi-tenancy | `OwnerGridView` | `components/owner/OwnerGridView.tsx` | Tiny renderer used by `ownerGridColumn` |
| Multi-tenancy | `createEmptyValueProxy` | `components/owner/createEmptyValueProxy.tsx` | Makes a model's new entity default its `owner` to the signed-in user (`OwnerSelector` can still pick "all users"). Logic in `defaultOwner.ts` + `util/peekableCache.ts`, tested by `cms/src/test/frontend/defaultOwner.test.ts` |
| Multi-tenancy | `CurrentUserGate` | `components/owner/CurrentUserGate.tsx` | **Required around every AutoCrud whose model uses `createEmptyValueProxy`.** Hilla's form snapshots its empty value once, at mount; mounted before the user lookup answered, every new row was created shared (owner: all) — read-only for its non-admin creator. Shows a progress bar until the user is known, a Retry on failure |
| Misc | `Placeholder` | `cms/src/main/frontend/components/placeholder/Placeholder.tsx` | Tiny WIP placeholder block. **In use — do not remove:** `cms/src/main/frontend/views/project/{projectId}/sample.tsx:9`, `cms/src/main/frontend/views/result/{resultId}/image.tsx:5`, `cms/src/main/frontend/views/result/{resultId}/result.tsx:7` |

Plus shared utilities at the top level (used by both modules via the alias):

| File | Purpose |
|---|---|
| `cms/src/main/frontend/util/auth.ts` | `useAuth()` from `@vaadin/hilla-react-auth` wired to `UserEndpoint.getAuthenticatedUser`. Both layouts import this. |
| `cms/src/main/frontend/util/model.ts`, `service.ts` | Form-model and CRUD helpers consumed by AutoCrud |
| `cms/src/main/frontend/model/owner/`, `model/sample/` | Vaadin "GridColumn" descriptors used by both `views/sample.tsx` (cms) and `views/run.tsx` (deck) |
| `cms/src/main/frontend/model/runStatus.tsx` | The one `RunStatus` colour map, exhaustive over the generated enum; `ResultViewer` and deck `views/run.tsx` |

## Inventory — command-deck (`command-deck/src/main/frontend/components/`)

| Domain | Component | File | Used by |
|---|---|---|---|
| Charts / dashboard | `InfoBoard` | `components/dashboard/InfoBoard.tsx` | Mounted in `views/@layout.tsx` — live frequency-inverter telemetry in the drawer |
| Charts / dashboard | `LiveTestResult` (incl. inner `TestResultGraph`) | `components/dashboard/LiveTestResult.tsx` | The chart in `views/run.tsx`. See [`state-and-realtime.md`](state-and-realtime.md). |
| Webcam / tracking *(provisional)* | `DistanceMeasureCam` | `components/webcam/DistanceMeasureCam.tsx` | `views/result/{resultId}/tracking.tsx` |
| Webcam / tracking *(provisional)* | `CamShiftTracking` factory | `components/webcam/tracking/CamShiftTracking.tsx` | Used by `DistanceMeasureCam` |
| Webcam / tracking *(provisional)* | `AreaSelector` | `components/webcam/tracking/AreaSelector.ts` | Helper for the Camshift initial bounding box |

Plus deck-only top-level helpers:

| File | Purpose |
|---|---|
| `command-deck/src/main/frontend/service/StatusService.ts` | The STOMP singleton (see [`state-and-realtime.md`](state-and-realtime.md)); its pure rules live in `service/connectionState.ts`. |
| `command-deck/src/main/frontend/service/loadCellBatch.ts` | The one `/topic/load-cell` batch parser, shared by `InfoBoard`, `views/control.tsx` and `LiveTestResult`; unit-tested in `command-deck/src/test/frontend/loadCellBatch.test.ts`. |

## Provisional — webcam tracking subsystem

Three files: `webcam/DistanceMeasureCam.tsx` (the React component) plus
`webcam/tracking/CamShiftTracking.tsx` and `webcam/tracking/AreaSelector.ts`.
`DistanceMeasureCam`:

- Loads `@techstark/opencv-js` lazily (`cvReady.then(...)`) and feeds the
  `requestAnimationFrame` capture loop into `creatCamshiftTracking(cv)`.
- Implements an in-place 2-click calibration flow converting pixel
  distance to cm.
- Owns the lifecycle in one mount effect: the `requestAnimationFrame` loop and
  the dispose handle returned by `init(canvas)` both end at unmount, and
  `startTracking` releases its native Mats on every path.

Lifting the OpenCV pipeline out of the component is planned (OQ-28). The
route `/result/:resultId/tracking` and the `tracking.tsx` view that mounts
`<DistanceMeasureCam/>` are stable; **the component's internals are not** —
re-read the source before depending on them.

## Where to look in the code
- `cms/src/main/frontend/components/` (whole tree)
- `command-deck/src/main/frontend/components/` (whole tree)
- `command-deck/src/main/frontend/components/webcam/DistanceMeasureCam.tsx` (provisional)
- `command-deck/src/main/frontend/components/webcam/tracking/CamShiftTracking.tsx`
- Reuse via alias examples: `command-deck/src/main/frontend/views/run.tsx:3,4,10,12,13,14,15,16`
- Sharing of `useAuth` — `cms/src/main/frontend/util/auth.ts:1-7` imported by both `@layout.tsx`s

## Known limits

- `DistanceMeasureCam.tsx` holds the `captureFrame` loop, the OpenCV pipeline and the chart consumer in one module (OQ-28).
