# Security and tenancy

> **Exempt from the 250-line split limit**: the login chain, the two ownership
> mechanisms and the `/api/**` policy are one reasoning thread — a reader who
> follows only part of it draws the wrong conclusion about what is protected.

## Purpose

How a user logs in, how a request reaches a `@BrowserCallable` service, and
the mechanisms that stop one non-admin user reaching another's rows — a
query-level ownership `Specification` on every owned CRUD service, and the
`@CheckUserCanOnlyAccessOwnData` aspect for a custom single-row method.

## Contents

- [Diagram — ownership-aspect sequence](#diagram--ownership-aspect-sequence)
- [Authentication chain](#authentication-chain)
- [`/api/**` needs a session, except the button box](#api-needs-a-session-except-the-button-box)
- [Role model](#role-model)
- [How services are protected](#how-services-are-protected)
- [The `@CheckUserCanOnlyAccessOwnData` aspect](#the-checkusercanonlyaccessowndata-aspect)
- [`User`-management privileges](#user-management-privileges)
- [CSRF, CORS, multipart](#csrf-cors-multipart)
- [TLS](#tls)
- [Where to look in the code](#where-to-look-in-the-code)
- [Tenancy: what the model is for](#tenancy-what-the-model-is-for)
- [Known limits](#known-limits)

## Diagram — ownership-aspect sequence

```mermaid
sequenceDiagram
    autonumber
    participant Browser as Browser (Hilla TS client)
    participant Hilla as Vaadin Hilla dispatcher
    participant Aspect as CheckUserCanOnlyAccessOwnDataAspect
    participant Auth as AuthenticatedUser
    participant Repo as CrudRepository (entity)
    participant Service as @BrowserCallable Service<br/>(e.g. SampleService)

    Browser->>Hilla: POST /connect/SampleService/get<br/>body: { id: 42 }
    Hilla->>Service: invoke get(42)
    Note over Aspect: @Before pointcut matches<br/>annotated class/method + first arg
    Service->>Aspect: joinPoint with arg=Long 42

    Aspect->>Aspect: isAdmin()? (ROLE_ADMIN)
    alt admin
        Aspect-->>Service: pass-through (no check)
    else non-admin
        Aspect->>Repo: findById(42)
        Repo-->>Aspect: Sample (DataWithOwner)
        Aspect->>Auth: get() current User
        Auth-->>Aspect: User
        alt entity.owner == null<br/>or owner.id == user.id
            Aspect-->>Service: allow (returns; method runs)
        else mismatch
            Aspect--xBrowser: throw SecurityException<br/>"User can only access their own data"
        end
    end

    Service->>Repo: actual data access
    Repo-->>Service: result
    Service-->>Hilla: return value
    Hilla-->>Browser: JSON response
```

Source: [`doc/diagrams/src/ownership-aspect.mmd`](../../diagrams/src/ownership-aspect.mmd).

## Narrative

### Authentication chain

* **Login form** lives in
  `cms/src/main/frontend/views/login.tsx`. Vaadin's stock login view posts to
  Spring Security's form-login endpoint.
* **`SecurityConfiguration`**
  (`cms/src/main/java/ch/rupfizupfi/deck/security/SecurityConfiguration.java`)
  is `@EnableWebSecurity`, declares a bcrypt `PasswordEncoder` bean, and owns
  a single `SecurityFilterChain` bean that:
  * adds no CSRF exemption of its own — `VaadinSecurityConfigurer` exempts
    only the login form and Vaadin's internal requests, so the upload at
    `/api/files/upload` must carry the token
    ([CSRF, CORS, multipart](#csrf-cors-multipart));
  * permits `/tests`, `/images/*.png`, `/line-awesome/**` and
    `/api/ControllerEndpoint/**` anonymously, requires a session for
    `/api/files/**` and `/api/DownloadEndpoint/**`, and leaves any other
    `/api` path to Vaadin's closing `denyAll()`;
  * applies the Vaadin defaults via
    `.with(VaadinSecurityConfigurer.vaadin(), v -> v.loginView("/login"))`.

  > Vaadin 25 has no `VaadinWebSecurity`; examples written against
  > `extends VaadinWebSecurity` + `super.configure(http)` do not apply.
* **`UserDetailsServiceImpl`**
  (`cms/.../security/UserDetailsServiceImpl.java:17`) loads
  `User` rows by username and maps the `Set<Role>` enum to Spring authorities
  prefixed `ROLE_` (so `Role.ADMIN` -> `ROLE_ADMIN`).
* **`AuthenticatedUser`**
  (`cms/.../security/AuthenticatedUser.java:13`) wraps Vaadin's
  `AuthenticationContext` and re-fetches the current `User` JPA entity by
  username.

### `/api/**` needs a session, except the button box

Only `/api/ControllerEndpoint/**` is anonymous: the hardware button box has
no session. File upload/download (`/api/files/**`) and the results export
(`/api/DownloadEndpoint/**`) are called by the browser, which always sends
the session cookie, so they require authentication; an unlisted `/api` path
is denied. The per-class `@AnonymousAllowed` / `@PermitAll` annotations on the
REST controllers document rather than enforce — the filter chain decides.

The export resolves its project through the owner-scoped
`ProjectService.get`, so another owner's project is a 404. A shared project
(`owner = null`) still holds every owner's results, so the rows go through
the same scope: `TestResultRepository.inProject` ANDed with
`OwnerDataHelper.addOwnerCriteriaToSpec` for a non-admin, unscoped for an
admin. The download name travels as an RFC 5987 `filename*` parameter.

`/api/files/image/{fileName}` serves bytes only through a `FileMetadata` row:
`FileMetadataService.findReadable` looks the stored name up
(`FileMetadataRepository.findByFilePath`) and applies the service's read
rule. No row, a row the caller may not read and a row whose bytes are gone
all answer 404, so a guessed `<id>.<ext>` discloses nothing. An upload not
yet attached to a test result stays readable by every logged-in user, as
everywhere else in that service.

A new endpoint that needs a caller identity belongs on a `@BrowserCallable`
service, or it needs its own matcher in `SecurityConfiguration`.

The file name a caller sends is the whole input: `FileService` (upload directory) and `CSVStoreService` (result
directory) each resolve a name against their base and refuse it with a
`SecurityException` when the result lands outside — a `..` segment and an
absolute name alike.

### Role model

`enum Role { USER, ADMIN }`
(`cms/src/main/java/ch/rupfizupfi/deck/data/Role.java`); Spring authorities
add the `ROLE_` prefix. Under `dev`, `DevUserSeeder` grants `USER` to both
seeded accounts and `ADMIN` only to `admin`; other profiles seed no account
(see [`ops/docker-and-profiles.md`](../../ops/docker-and-profiles.md)).

A Hilla view declares `rolesAllowed` as `Role` enum names — `views/admin/user`
and `views/system/setting` (cms) and `views/system/faults` (command-deck). Vaadin 25.2's
server-side route check compares those strings to granted authorities verbatim,
so `SecurityConfiguration` registers its own `isSecuredHillaRoute` rule ahead of
Vaadin's and prefixes each name with `ROLE_` the way `isUserInRole` does; a name
that already carries the prefix is left alone.

### How services are protected

Each `@BrowserCallable` carries one of these; it is the only authorisation
surface for Hilla calls (Vaadin denies anonymous ones otherwise). The REST
rows are annotation only — see the `/api/**` policy above:

| Annotation | Where |
|---|---|
| `@PermitAll` (any logged-in user) | `ProjectService`, `SampleService`, `TestParameterService`, `TestResultService`, `CustomerService`, `MaterialService`, `GearTypeService`, `GearStandardService`, `FileMetadataService`, plus `TestRunnerService` / `DeviceInfoService` / `SuckService` in command-deck |
| `@RolesAllowed("ADMIN")` | `UserService`, `SettingService` — the directory settings decide where the server writes, so `save` also refuses an unknown key, a mistyped value, and a relative or `..` path or one inside the working directory, `loader.path` or the classpath |
| `@AnonymousAllowed` | `ControllerEndpoint`, `FileEndpoint` (REST controllers under `/api/**`) |
| `@PermitAll` (REST) | `DownloadResults` (under `/api/**`) |

Which rows a non-admin then reaches is decided per service, by one of two
mechanisms or by none:

| Service | Mechanism | Scoped operations |
|---|---|---|
| `ProjectService`, `TestResultService`, `SampleService`, `TestParameterService`, `CustomerService` | extend `CrudRepositoryServiceForOwnerData` (below) | `get`, `list`, `count`, `exists`, `delete`, `deleteAll`, `save`, `saveAll` |
| `FileMetadataService` | hand-rolled through the file's `TestResult`, since `FileMetadata` is no `DataWithOwner` | `get`, `list`, `count`, `exists`, `save`, `saveAll`, `delete`, `deleteAll`, `connectToTestResult`, and `findReadable` behind the image endpoint; a file with no result, or one whose result has no owner, is readable by everyone. `filePath` and `fileName` are server-owned: an update keeps the stored ones, a new row gets no path |
| `MaterialService`, `GearTypeService`, `GearStandardService` | none — unscoped by design | reference data, shared by every logged-in user |

### The `@CheckUserCanOnlyAccessOwnData` aspect

**Specification injection is the default — `CrudRepositoryServiceForOwnerData`.**
`cms/.../hilla/crud/CrudRepositoryServiceForOwnerData.java` overrides
`get`, `list`, `count`, `exists`, `delete`, `deleteAll`, `save` and `saveAll`
and, for non-admins, AND-s an extra `Specification` onto the query:

```java
spec.and((root, q, cb) -> cb.or(
    cb.equal(root.get("owner"), authenticatedUser),
    cb.isNull(root.get("owner"))));
```

So a `list(Pageable, Filter)` returns only owner-matching or owner-null rows.
Every owned CRUD service extends this class.

**Reading and writing follow different rules** (owner, 2026-10-13). One
non-admin rule set, held in `OwnerDataHelper` — the query predicate above and
the static `isReadableBy`, `isOwnedBy` and `isAssignableOwner`:

| Operation (non-admin) | Allowed when |
|---|---|
| read — `get`, `list`, `count`, `exists` | the row's owner is the caller **or null** (shared) |
| update — `save` with an id | the **stored** row's owner is the caller; the payload's owner must be self or null |
| delete — `delete`, `deleteAll` | the **stored** row's owner is the caller |
| create — `save` without an id | the payload's owner is self **or null**: anyone may share a new row |

So a shared row (owner = null) is **read-only** for a non-admin: it can be
neither edited, deleted nor claimed by naming oneself as its owner. The stored
row is loaded unscoped (`findById`) and its owner compared by id — the read
predicate's `or owner is null` must not decide a write. A non-admin may still
turn their own row into a shared one, after which only an admin can change it.
Admins bypass all of it.

A grid filter is client input turned into joins, so the cms
`CrudRepositoryService.toSpec` refuses, for admins too, a property path naming
`hashedPassword`/`newPassword` or reaching past `owner` to anything but
`username` or `name` (the two `ownerGridColumn` filters).

`save` and `saveAll` are scoped by the same class: a non-admin payload's owner
must be self or null, and a payload carrying an id must name a stored row the
caller owns, so an id alone never buys write access to another owner's row or
to a shared one. Every `@ManyToOne`/`@OneToOne` reference to a `DataWithOwner`
(a sample's project, a result's sample and parameter) must pass the *read*
rule, via `OwnerDataHelper.isVisible` — referencing a shared row is fine. The
owner is stored exactly as sent — the frontend's `OwnerSelector` chooses it,
and null means a shared row. `deleteAll` checks every id against the write
rule first, so a batch is refused whole.

A refusal is an `OwnerAccessDeniedException`, a Hilla `EndpointException`, so
its message reaches the browser's error toast: a shared row answers that it is
read-only and only an administrator can change it; a missing row and another
owner's row get the same "no permission" message, so nothing about a row the
caller cannot read leaks. The forms themselves still offer editing; the toast
is the feedback.

**A shared project may only reference a shared customer** (owner,
2026-10-13). A shared project is readable by everyone, so its customer must be
too. Two `checkInvariants` overrides hold it, for admins as well, after the
owner checks so a refusal never describes a row the caller cannot read:
`ProjectService` refuses a project with owner null whose *stored* customer has
an owner, and `CustomerService` refuses giving an owner to a customer a shared
project references (`ProjectRepository.existsByCustomer_IdAndOwnerIsNull`).
Each is an `EndpointException` whose message starts with the field it names
(`customer:` / `owner:`), so it reaches the toast. An owned project may
reference its owner's customer or a shared one — the ordinary reference rule.

`TestResultService.listCSVResults` and `readCSVData` reach files rather than
rows, so they gate on the scoped `get(id)` themselves before calling
`CSVStoreService`.

**The aspect is for a custom, non-CRUD method** whose *first* argument is the
owned entity or its `Long` id — no CRUD service uses it today.
`CheckUserCanOnlyAccessOwnDataAspect`
(`cms/.../security/CheckUserCanOnlyAccessOwnDataAspect.java:26`) matches the
annotation on a method or on the enclosing type, binds that first argument as
`value`, logs the intercepted method name at debug, and:

* returns immediately for `ROLE_ADMIN`;
* re-binds a `Long` to the entity
  `crudRepositoryService.getCrudRepository().findById(id)` loads, and throws
  `IllegalStateException` naming the target class when that target is no
  `CrudRepositoryService` — an annotation that cannot resolve its id fails on
  first use instead of passing;
* throws `SecurityException("User can only access their own data")` when the
  entity carries a non-null owner that is somebody else — the *read* rule,
  `OwnerDataHelper.isReadableBy`. A shared row passes, so the aspect must not
  guard a method that changes the row; that needs the write rule above.

It never sees `list(Pageable, Filter)` — there is no single argument to
inspect — so collection scoping stays the base class's job.

### `User`-management privileges

`UserService` is `@RolesAllowed("ROLE_ADMIN")`
(`cms/.../api/services/UserService.java:12`). It overrides `save(User)` to
hash a `newPassword` (transient field on `User`) with bcrypt before delegating
to the parent CRUD method. The frontend admin view at
`cms/src/main/frontend/views/admin/user.tsx` is the only UI consumer.

### CSRF, CORS, multipart

Spring Security checks a CSRF token on every state-changing request outside
the login form and Vaadin's internal requests; no path of ours is exempt. The
token lives in the HTTP session (Spring's default repository — no
`XSRF-TOKEN` cookie in this app) and is checked by Spring's default
`XorCsrfTokenRequestAttributeHandler`, which expects the **masked** per-request
value, not the raw session one.

* **Hilla calls.** Vaadin writes the request's (masked) token into the
  `_csrf` / `_csrf_header` meta tags of the index page; Hilla's connect client
  sends it, and its `login()` rewrites the tags from the login response's
  `Spring-CSRF-token` header, since login rotates the token.
* **`<vaadin-upload>`.** It posts with a plain XHR outside the Hilla client,
  so `views/result/{resultId}/image.tsx` passes `headers={csrfHeaders()}`.
  `cms/src/main/frontend/util/csrf.ts` mirrors Hilla's own lookup (the
  `XSRF-TOKEN` cookie before the `_csrf` meta tag, header name from
  `_csrf_header`; Hilla does not export it) and sends the meta value
  unchanged. An upload without the token answers 403. The view also sets
  `uploadFormat="multipart"`: Vaadin 25's `<vaadin-upload>` sends a raw body
  by default, which `FileEndpoint`'s `@RequestParam("file")` cannot read —
  the request then fails and its error dispatch answers 403 too, so a 403
  here is not always CSRF.
* **Session cookie.** `server.servlet.session.cookie.same-site=lax` in both
  modules' `application.properties` keeps the cookie off cross-site POSTs, a
  second line behind the token.

Pinned by `ApiSecurityTest`: an authenticated upload without a token is 403,
one with the masked request token is 200, and one with the raw session token
is 403 — the real token path, not spring-security-test's `csrf()`, which
skips the check. `src/test/frontend/csrf.test.ts` pins the lookup order.

No CORS configuration: frontend and backend share an origin in dev (Vite
proxied through Spring) and production (one JAR).

### TLS

Only the `docker` profile enables TLS (PKCS12 keystore, self-signed by
`startup.sh` when absent — the intended operating mode). Dev runs plain HTTP on `localhost:8080`. Settings and rationale:
[`ops/docker-and-profiles.md`](../../ops/docker-and-profiles.md).

## Where to look in the code

| Concern | File |
|---|---|
| HTTP security config | `cms/src/main/java/ch/rupfizupfi/deck/security/SecurityConfiguration.java:27` (the `SecurityFilterChain` bean) |
| User loader | `cms/src/main/java/ch/rupfizupfi/deck/security/UserDetailsServiceImpl.java:17` |
| Authenticated principal -> JPA `User` | `cms/src/main/java/ch/rupfizupfi/deck/security/AuthenticatedUser.java:23` |
| Role check helper | `cms/src/main/java/ch/rupfizupfi/deck/security/UserUtils.java:9` |
| Marker interface | `cms/src/main/java/ch/rupfizupfi/deck/security/DataWithOwner.java:6` |
| Aspect | `cms/src/main/java/ch/rupfizupfi/deck/security/CheckUserCanOnlyAccessOwnDataAspect.java:26` |
| Spec injection | `cms/src/main/java/ch/rupfizupfi/deck/hilla/crud/CrudRepositoryServiceForOwnerData.java:24` |
| Spec helper | `cms/src/main/java/ch/rupfizupfi/deck/hilla/crud/OwnerDataHelper.java:22` |
| Annotation | `cms/src/main/java/ch/rupfizupfi/deck/security/CheckUserCanOnlyAccessOwnData.java` |
| Seed users | `cms/src/main/java/ch/rupfizupfi/deck/security/DevUserSeeder.java` (dev), `InitialAdminBootstrap.java` (docker) |

## Tenancy: what the model is for

Ownership is an **access-control requirement on the cloud cms**: its users
may belong to different organisations, so owner-scoped records (customers, projects,
samples, test parameters, results) must not leak between them.

Live hardware telemetry is the exception. The `deck` deployment drives one
physical tester, so `/topic/load-cell`, `/topic/frequency-inverter-info`
and `/topic/logs` are shared: every operator watching that machine sees the
same readings. Revisit this if a deck instance ever fronts more than one
tester.

**Shared, not public.** The STOMP handshake at `/status` carries its own
authorization rule in `SecurityConfiguration` — `authenticated()`, never
`permitAll()`. The rule has to be explicit because `VaadinSecurityConfigurer`
closes the chain with `anyRequest().denyAll()`: a path that is neither a Vaadin
route, a Hilla endpoint nor framework-internal answers **403**, even to a
logged-in user. Any new non-Vaadin endpoint needs its own rule.

**Listen-only.** The handshake rule says who may connect, not what a connected
client may send. `WebSocketConfig` therefore registers `BrokerInboundGuard` on
the client inbound channel: CONNECT and SUBSCRIBE need a user on the STOMP
session (Spring copies it from the authenticated handshake request), SUBSCRIBE
is admitted under `/topic/` only, and **every SEND is refused** — no
`@MessageMapping` exists, and without the guard the simple broker relayed a
client's SEND to `/topic/test-state` or `/topic/load-cell` to every subscriber,
forging the safety display. A future `@MessageMapping` has to open its
destination in the guard deliberately. Server publishing goes through
`SimpMessagingTemplate` on the broker channel and never meets the guard.
`spring-security-messaging`'s `@EnableWebSocketSecurity` was not used: its
CONNECT CSRF check rejects the `@stomp/rx-stomp` client. Pinned by
`BrokerInboundGuardTest` and `BrokerInboundGuardIntegrationTest` (real
handshake, ERROR frame on SEND).

## Known limits

- `FileMetadataService` hand-rolls its scoping through the file's `TestResult` on every entry point
  ([`hilla-services.md`](hilla-services.md#hilla-service-catalogue)): `FileMetadata` is no
  `DataWithOwner`, so `CrudRepositoryServiceForOwnerData` cannot carry it. Every other owned service
  extends that class (OQ-37).
