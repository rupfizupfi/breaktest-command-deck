# Frontend views and routing

Background: `doc/04-frontend/frontend-module-split.md`, `doc/04-frontend/hilla-generated-layer.md`, `doc/04-frontend/state-and-realtime.md`.

## Routing rules (Hilla file-based router)

Views live in `<module>/src/main/frontend/views/`:

| File | Meaning |
|---|---|
| `foo.tsx` | route `/foo` |
| `@index.tsx` | index route of its directory |
| `@layout.tsx` | layout shell (AppLayout with SideNav) |
| `{projectId}/sample.tsx` | path param → `/project/:projectId/sample` |

Every view **must** export a `config`:

```tsx
export const config: ViewConfig = {menu: {order: N, icon: 'line-awesome/svg/x.svg'}, title: 'X', loginRequired: true};
```

Use `menu: {exclude: true}` to keep the route but hide it from the SideNav (see `views/test/destructive.tsx`, deck's `views/control.tsx`).

## Which module?

- Content/CRUD views → `cms/src/main/frontend/views/`
- Hardware control, live dashboards, test execution → `command-deck/src/main/frontend/views/`

The deck merges **both** route trees in `command-deck/src/main/frontend/routes.tsx` (`withFileRoutes(cmsFileRoutes)` first, then its own). A cms view automatically appears in the deck app. Duplicate paths collide silently — check both trees. A custom Vite plugin (`command-deck/customFileSystemRouterPlugin.ts`) copies cms's `file-routes.json` into the deck's on build/HMR; if a new cms route doesn't show up in the deck dev server, restart the deck's `bootRun`.

## Import aliases

- `Frontend/*` → **this module's** `src/main/frontend/`
- `cms/*` → the cms frontend (shared components) — works from both modules

The `cms/` alias is declared in four places (both `tsconfig.json` `paths` + both `vite.config.ts` `resolve.alias`). Don't add new aliases without updating all four. Shared components go in `cms/src/main/frontend/components/`.

**New npm dependency for a shared component:** install it in **both** `cms/package.json` and `command-deck/package.json`. The deck's `forceMainNodeModules()` Vite plugin rewrites cms imports to the deck's `node_modules` to avoid duplicate React — a package present in only one module breaks the other's build.

## Calling the backend

Import generated clients from `Frontend/generated/endpoints` — never from relative paths into `generated/`, and never edit `generated/` files. To scope a CRUD service to a fixed filter (parent id, type discriminator), wrap it with `constraintServiceToFilter(Service, filter)` from `cms/util/service` (example: `views/test/destructive.tsx`).

## Real-time data (deck only)

Subscribe via the `RxStomp` singleton in `command-deck/src/main/frontend/service/StatusService.ts`: call `connectComponent()` in a `useEffect`, return `disconnectComponent()` as cleanup, subscribe to the exposed observables (`/topic/load-cell`, `/topic/frequency-converter-info`, `/topic/logs`). Known footgun: `StatusService.ts` hardcodes `ws://localhost:8080/status` — wrong outside dev.

## Theming

Theme `breaktest-command-deck` is **duplicated** in both modules under `src/main/frontend/themes/breaktest-command-deck/`. Style changes usually need to be made in both copies. `theme.json` `lumoImports` is dead in Vaadin 25 — utility classes come from `@StyleSheet(Lumo.UTILITY_STYLESHEET)` on `Application.java`.
