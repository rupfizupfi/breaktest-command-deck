# Hilla generated layer

> Branch: `dev-split` &middot; Snapshot: 2026-04-25 &middot; 04-frontend

## Purpose

`src/main/frontend/generated/` is the seam between Java and TypeScript. It
exists so a React component can `await TestRunnerService.start(id)` and get
type-safety, CSRF handling and JSR-250 enforcement for free. This page
explains what is in there, who writes it, and the **golden rule:** never edit
anything inside `generated/`.

## Contents

- [Diagram — Hilla RPC round-trip](#diagram--hilla-rpc-round-trip)
- [What lives under `generated/`](#what-lives-under-generated)
- [The hand-written client](#the-hand-written-client)
- [Walked example: `TestRunnerService.start(id)`](#walked-example-testrunnerservicestartid)
- [Auth / error propagation](#auth--error-propagation)
- [Where to look in the code](#where-to-look-in-the-code)
- [Open questions](#open-questions)

## Diagram — Hilla RPC round-trip

```mermaid
sequenceDiagram
    autonumber
    participant View as RunView<br/>(views/run.tsx)
    participant Generated as Frontend/generated/<br/>TestRunnerService.ts
    participant Client as connect-client.ts<br/>(ConnectClient + rpcErrorPolicy)
    participant Net as fetch (browser)
    participant Spring as Spring DispatcherServlet
    participant Hilla as com.vaadin.hilla<br/>EndpointController
    participant Sec as Spring Security<br/>+ @PermitAll / @RolesAllowed
    participant Bean as TestRunnerService.java<br/>(@BrowserCallable)

    View->>Generated: TestRunnerService.start(id)
    Generated->>Client: client.call("TestRunnerService","start",{testId})
    Client->>Net: POST /connect/TestRunnerService/start<br/>X-CSRF-Token + JSESSIONID
    Net->>Spring: HTTP request
    Spring->>Hilla: EndpointController.serveEndpoint(...)
    Hilla->>Sec: check JSR-250 annotations
    alt unauthenticated
        Sec-->>Net: 401 (no body)
        Note right of View: throws UnauthorizedResponseError —<br/>the .protect() guard in routes.tsx only redirects<br/>to /login on the next navigation
    else authorized
        Sec->>Bean: invoke start(testId)
        Bean-->>Hilla: void
        Hilla-->>Net: 200 application/json "null"
    end
    Net-->>Client: Response
    Client-->>Generated: parsed JSON / undefined
    Generated-->>View: Promise<void>
```

(Source: [`doc/diagrams/src/hilla-rpc.mmd`](../diagrams/src/hilla-rpc.mmd).)

## What lives under `generated/`

These categories appear in **both** module trees and have an identical role:

| Path | Origin | Purpose |
|---|---|---|
| `<Service>.ts` (one per `@BrowserCallable`) | Hilla generator | Thin TS wrapper that calls `client.call("ServiceName","method",args)`. |
| `endpoints.ts` | Hilla generator | Re-exports every service module under a single import (`from "Frontend/generated/endpoints"`). |
| `ch/rupfizupfi/deck/...` (DTO mirrors) | Hilla generator | TypeScript classes/interfaces mirroring every Java DTO/entity referenced by an endpoint. Includes a `*Model` for each entity (form binding metadata). |
| ~~`connect-client.default.ts`~~ | Hilla generator | **No longer emitted.** Both modules supply a hand-written `src/main/frontend/connect-client.ts` instead — see [The hand-written client](#the-hand-written-client). |
| `file-routes.ts`, and in dev mode `file-routes.json` | `vite-plugin-file-router` (Vaadin) | Compiled route tree from the `views/` directory. A production build writes the `.json` to the Vite `outDir` instead. |
| `flow/*` | Hilla generator | Bridge to Vaadin Flow for Flow-driven layouts (this codebase uses it only as the `withFallback(Flow)` 404 in `routes.tsx`). |
| `routes.tsx` | Hilla generator | React-Router routes assembled from `file-routes.ts` — **but** in this repo `command-deck/src/main/frontend/routes.tsx` is **hand-written** (see [routing-and-layout.md](./routing-and-layout.md)) and the generated one is not used at runtime. |
| `theme-*.generated.js`, `vaadin*.ts`, `jar-resources/`, `vaadin-react.tsx` | Vaadin | Boot scripts, theme bundle, copies of files extracted from add-on JARs. |
| `vaadin-featureflags.js`, `generated-file-list.txt` | Vaadin | Manifests used by Vaadin's runtime. |

For the gory inventory per module see `doc/_inventory.md` &sect;4. Neither
module's tree is in git — `.gitignore:18` covers both and nothing under them
is tracked, so anything that builds the frontend must run the Hilla generator
first.

**Golden rule.** Anything under `generated/` is overwritten on every JVM start
in dev mode and on every `vaadin build` in production. If you find yourself
about to edit one of these files, stop and edit the Java source instead.

## The hand-written client

`@vaadin/hilla-generator-plugin-client` looks for `<module>/src/main/frontend/connect-client.ts`
(its `CUSTOM_CLIENT_FILE_NAME`). When that file exists every generated service imports
`"../connect-client.js"` and the generator **stops emitting** `generated/connect-client.default.ts`.
A supported extension point, so it survives regeneration.

**Both modules must have one.** The generator resolves it per module; the module without it falls
back to the generated default and silently loses the error policy. The policy itself lives once, in
`cms/src/main/frontend/util/rpcErrorPolicy.ts`, and the deck's client imports it.

| Constraint | Why |
|---|---|
| `prefix: 'connect'` — no leading slash | Must stay byte-identical to the generated default it replaces, or every RPC 404s |
| Installed as a `ConnectClient` middleware | `ConnectClient.call()` builds the chain `[responseHandlerMiddleware, ...middlewares]`, so the policy runs *inside* the response handler and sees the raw `Response` before `assertResponseIsOk` turns it into an `EndpointError` |
| Observes, never suppresses | The throw still reaches callers that handle it themselves |
| Clone before reading the body | `assertResponseIsOk` awaits `response.text()` one level up; reading the original hands it a consumed stream and breaks every call |
| Never log `context.params` | `UserService.save` carries `newPassword` |

Why a middleware rather than call-site handling: `@vaadin/hilla-react-crud`'s data provider has a
rethrow-only `.catch`, so a failed AutoGrid or ComboBox load is a blank component plus an unhandled
rejection with no call site of ours to hook. This is the only layer that sees those.

What it does per status: **401** → one full-page navigation to the *current* path (not `/login`),
because Spring Security caches the denied navigation and returns it as the `Saved-url` header that
`login.tsx` already follows; guarded by a `sessionStorage` marker so a page that also renders fine
logged-out cannot reload in a loop. **403** → persistent, click-to-dismiss toast; the operator is
authenticated but blocked, nothing will retry, and redirecting would loop a logged-in non-admin.
**Everything else, including a rejected `fetch` (status 0)** → transient toast, deduped per
(status, endpoint, method) so the three AutoGrids on `system/@index.tsx` cannot stack six toasts off
one dead backend. **Validation errors are skipped** — Hilla's `EndpointValidationError` is already
rendered into the form by `autoform.js`, and a toast would double-report. `UserEndpoint` is exempt
from the 401 path: it is `@AnonymousAllowed`, answers 200 when logged out, and is the call
`AuthProvider` makes on mount, so acting on a 401 from it could only ever loop.

## Walked example: `TestRunnerService.start(id)`

Three files, one continuous round-trip.

1. **Java source** (`@BrowserCallable`) —
   `command-deck/src/main/java/ch/rupfizupfi/deck/api/services/TestRunnerService.java:11-23`
   ```java
   @BrowserCallable
   @PermitAll
   public class TestRunnerService {
       public void start(Long testId) {
           testRunnerThread.startThread(
               testResultRepository.findById(testId)
                   .orElseThrow(() -> new RuntimeException("Test not found"))
           );
       }
       // status() / stop() / StatusResponse omitted
   }
   ```

2. **Generated TS client** —
   `command-deck/src/main/frontend/generated/TestRunnerService.ts:1-7`
   ```ts
   import client_1 from "../connect-client.js";
   async function start_1(testId: number | undefined, init?: EndpointRequestInit_1): Promise<void> {
       return client_1.call("TestRunnerService", "start", { testId }, init);
   }
   export { start_1 as start, /* status, stop */ };
   ```
   Notice how `Long testId` becomes `testId: number | undefined` and the `void` return is
   preserved. Hilla also generated a sibling
   `ch/rupfizupfi/deck/api/services/TestRunnerService/StatusResponse.ts`
   for the inner DTO (because `status()` returns one).

3. **Call site** in a view —
   `command-deck/src/main/frontend/components/dashboard/LiveTestResult.tsx:240`
   ```ts
   import { TestRunnerService } from "Frontend/generated/endpoints";
   // ...
   TestRunnerService.start(testResult.id!).catch(() => setStopped(true));
   ```
   `Frontend/` is a Vaadin-conventional alias for `src/main/frontend/`. The
   import resolves to `generated/endpoints.ts:13`, which re-exports the file
   above.

## Auth / error propagation

Hilla pipes JSR-250 annotations directly into the Spring Security
authorization layer. Behaviour observed in this codebase:

- **`@AnonymousAllowed`** — one browser-callable surface uses it:
  `cms/src/main/java/ch/rupfizupfi/deck/api/UserEndpoint.java:22`, which has
  to be reachable before login so `useAuth()` can ask who (if anyone) is
  signed in. Everywhere else `@PermitAll` plus `loginRequired: true` on the
  route is the prevailing idiom.
- **`@PermitAll`** — `TestRunnerService`, `DeviceInfoService`,
  `SuckService`, `SettingService`, etc. The endpoint is reachable for any
  authenticated principal; the route metadata (`config.loginRequired`) keeps
  unauthenticated users out of the view in the first place.
- **`@RolesAllowed("ROLE_ADMIN")`** — only `UserService` (see
  `cms/src/main/java/ch/rupfizupfi/deck/api/services/UserService.java:12`).
  Calling `UserService.list(...)` as a non-admin yields HTTP 403 from
  `EndpointController`; the generated client surfaces that as a thrown
  `EndpointError` from the awaited promise.
- **Unauthenticated** call — `EndpointController` returns HTTP 401.
  `@vaadin/hilla-react-auth` (configured via
  `cms/src/main/frontend/util/auth.ts`, which wraps
  `UserEndpoint.getAuthenticatedUser`) detects the loss of session, the route
  guard set up by `routes.tsx#protect()` redirects to `/login`, and
  `cms/src/main/frontend/views/login.tsx` renders `<LoginOverlay/>` against
  the standard Spring-Security form login. After a successful submit the
  `LoginOverlay`'s `onLogin` callback navigates to `redirectUrl` if set.
- **Method-level `@CheckUserCanOnlyAccessOwnData`** (cms AOP aspect, see
  [`../03-backend/security-and-tenancy.md`](../03-backend/security-and-tenancy.md)) raises an
  exception inside the endpoint method — Hilla turns it into a
  `EndpointException` with the message preserved, surfaced as a rejected
  promise on the client.

## Where to look in the code
- `command-deck/src/main/frontend/generated/TestRunnerService.ts` (1-7)
- `command-deck/src/main/frontend/generated/endpoints.ts:1-16`
- `command-deck/src/main/java/.../api/services/TestRunnerService.java:11-44`
- `cms/src/main/frontend/util/auth.ts:1-7`
- `cms/src/main/frontend/views/login.tsx:1-38`
- `cms/src/main/java/.../api/services/UserService.java:12` (the lone `@RolesAllowed` example)
- Generated DTO mirror example:
  `command-deck/src/main/frontend/generated/ch/rupfizupfi/deck/api/services/TestRunnerService/StatusResponse.ts`

## Open questions

1. **Alternative client from `dev/hilla/openapi.json`?** Hilla writes an
   OpenAPI spec there (`application.properties` excludes it from devtools
   restart). Still undecided whether pointing external tooling at it is
   worth it — no current consumer needs a non-Hilla client. (OQ-18)
