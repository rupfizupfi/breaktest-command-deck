> Branch: `dev-split` — captured 2026-04-25.

# Security and tenancy

> **Exempt from the 250-line split limit**: the login chain, the two ownership
> mechanisms and the `/api/**` policy are one reasoning thread — a reader who
> follows only part of it draws the wrong conclusion about what is protected.

## Purpose

How a user logs in, how a request reaches a `@BrowserCallable` service, and
the two independent mechanisms that stop one non-admin user reaching another's
rows — the `@CheckUserCanOnlyAccessOwnData` aspect for single-row access, and
a query-level ownership `Specification` for collections.

## Contents

- [Diagram — ownership-aspect sequence](#diagram--ownership-aspect-sequence)
- [Authentication chain](#authentication-chain)
- [`/api/**` is anonymous — standing policy](#api-is-anonymous--standing-policy)
- [Role model](#role-model)
- [How services are protected](#how-services-are-protected)
- [The `@CheckUserCanOnlyAccessOwnData` aspect](#the-checkusercanonlyaccessowndata-aspect)
- [`User`-management privileges](#user-management-privileges)
- [CSRF, CORS, multipart](#csrf-cors-multipart)
- [TLS](#tls)
- [Where to look in the code](#where-to-look-in-the-code)
- [Tenancy: what the model is actually for](#tenancy-what-the-model-is-actually-for-decided-2026-08-16)
- [Open questions](#open-questions)

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
        alt owner == null OR owner.id == user.id
            Aspect-->>Service: allow (returns; method runs)
        else mismatch
            Aspect--xBrowser: throw SecurityException
        end
    end

    Service->>Repo: actual data access
    Repo-->>Service: result
    Service-->>Hilla: return value
    Hilla-->>Browser: JSON response
```

Source: [`doc/diagrams/src/ownership-aspect.mmd`](../diagrams/src/ownership-aspect.mmd).

## Narrative

### Authentication chain

* **Login form** lives in
  `cms/src/main/frontend/views/login.tsx`. Vaadin's stock login view posts to
  Spring Security's form-login endpoint.
* **`SecurityConfiguration`**
  (`cms/src/main/java/ch/rupfizupfi/deck/security/SecurityConfiguration.java`)
  is `@EnableWebSecurity`, declares a bcrypt `PasswordEncoder` bean, and owns
  a single `SecurityFilterChain` bean that:
  * disables CSRF for `/api/files/uploads` and `/api/files/upload` (the only
    multipart endpoints), so the React file-uploader can `POST` without a
    token;
  * permits `/tests`, `/images/*.png`, `/line-awesome/**`, and **`/api/**`**
    anonymously — any REST controller mounting under `/api/...` is therefore
    unauthenticated unless it adds its own checks;
  * applies the Vaadin defaults via
    `.with(VaadinSecurityConfigurer.vaadin(), v -> v.loginView("/login"))`.

  > Vaadin 25 **removed `VaadinWebSecurity`**. The defaults that class used to
  > contribute by inheritance are now applied into an application-owned filter
  > chain by `VaadinSecurityConfigurer`. Anything written against the old
  > `extends VaadinWebSecurity` + `super.configure(http)` shape no longer
  > applies here.
* **`UserDetailsServiceImpl`**
  (`cms/.../security/UserDetailsServiceImpl.java:17`) loads
  `User` rows by username and maps the `Set<Role>` enum to Spring authorities
  prefixed `ROLE_` (so `Role.ADMIN` -> `ROLE_ADMIN`).
* **`AuthenticatedUser`**
  (`cms/.../security/AuthenticatedUser.java:13`) wraps Vaadin's
  `AuthenticationContext` and re-fetches the current `User` JPA entity by
  username so callers get a fully-managed entity, not just a `UserDetails`.

### `/api/**` is anonymous — standing policy

`SecurityConfiguration` permits all of `/api/**` without a session
(`cms/src/main/java/ch/rupfizupfi/deck/security/SecurityConfiguration.java:39`),
and that is the intended division of labour: the REST surface — file
upload/download and the hardware button box — is called by clients that have
no Vaadin session, while Hilla `@BrowserCallable` services are the
authenticated surface.

The cost is real and accepted. File upload and download are unauthenticated,
and the per-class `@AnonymousAllowed` / `@PermitAll` annotations on those REST
controllers are documentation rather than enforcement, because the filter
chain has already let the request through — a typo'd annotation there cannot
cause a regression, since nothing on those paths was ever enforced.

**Do not add anything owner-scoped under `/api/**`.** A new endpoint that
needs a caller identity belongs on a `@BrowserCallable` service, or it needs
its own matcher ahead of the `permitAll()` rule.

### Role model

`enum Role { USER, ADMIN }`
(`cms/src/main/java/ch/rupfizupfi/deck/data/Role.java`); Spring authorities
add the `ROLE_` prefix. `data.sql` grants `USER` to both seeded accounts and
`ADMIN` only to `admin` (id 2).

### How services are protected

Each `@BrowserCallable` chooses one of three patterns; this is the *only*
authorisation surface for Hilla calls (anonymous Hilla calls are otherwise
denied by Vaadin):

| Annotation | Where |
|---|---|
| `@PermitAll` (any logged-in user) | `ProjectService`, `SampleService`, `TestParameterService`, `TestResultService`, `CustomerService`, `MaterialService`, `GearTypeService`, `GearStandardService`, `FileMetadataService`, `SettingService`, plus `TestRunnerService` / `DeviceInfoService` / `SuckService` in command-deck |
| `@RolesAllowed("ROLE_ADMIN")` | `UserService` (`cms/.../api/services/UserService.java:12`) |
| `@AnonymousAllowed` | `ControllerEndpoint`, `FileEndpoint`, `DownloadResults` (REST controllers under `/api/**`) |

Services that hold owner-scoped data wear `@CheckUserCanOnlyAccessOwnData`
in addition to `@PermitAll`:

* `SampleService` (`cms/.../api/services/SampleService.java:13`)
* `TestParameterService` (`cms/.../api/services/TestParameterService.java:12`)

`ProjectService` and `TestResultService` rely on extending
`CrudRepositoryServiceForOwnerData` (below) instead of the aspect.

### The `@CheckUserCanOnlyAccessOwnData` aspect

Two-layer enforcement, because the AOP aspect alone cannot stop a `list`/
`findAll` returning other users' rows.

**Layer 1 — AOP (`@Before` interceptor).**
`CheckUserCanOnlyAccessOwnDataAspect`
(`cms/.../security/CheckUserCanOnlyAccessOwnDataAspect.java:19`) matches
either the annotation on a method or on the enclosing type, binding the
*first* method argument as `value`:

* If the caller is `ROLE_ADMIN`, return immediately.
* If `value` is a `Long`, the aspect treats it as a primary key, calls
  `crudRepositoryService.getCrudRepository().findById(id)`, and re-binds
  `value` to the loaded entity.
* If `value` (now) implements `DataWithOwner` and the entity has a non-null
  owner whose id differs from the authenticated user's id, throw
  `SecurityException("User can only access their own data")`.

This covers `get(id)`, `delete(id)`, `save(entity)`, etc. It does **not**
cover `list(pageable, filter)` — there is no single argument to inspect.

**Layer 2 — Specification injection (`CrudRepositoryServiceForOwnerData`).**
`cms/.../hilla/crud/CrudRepositoryServiceForOwnerData.java:17` overrides
`get`, `list`, `delete` and, for non-admins, AND-s an extra
`Specification` onto the query:

```java
spec.and((root, q, cb) -> cb.or(
    cb.equal(root.get("owner"), authenticatedUser),
    cb.isNull(root.get("owner"))));
```

So a `list(Pageable, Filter)` returns only owner-matching or owner-null rows —
a call the aspect never sees. **The aspect rejects single-row access by id;
the specification scrubs collection results.** Neither covers the other.

### `User`-management privileges

`UserService` is `@RolesAllowed("ROLE_ADMIN")`
(`cms/.../api/services/UserService.java:12`). It overrides `save(User)` to
hash a `newPassword` (transient field on `User`) with bcrypt before delegating
to the parent CRUD method. The frontend admin view at
`cms/src/main/frontend/views/admin/user.tsx` is the only UI consumer.

### CSRF, CORS, multipart

Vaadin handles CSRF for Hilla calls via the connect-client session token; it
is disabled only for the two `FileEndpoint` multipart uploads. There is no
CORS configuration because frontend and backend share an origin — the Vaadin
dev-server proxies through Spring in dev, and it's one JAR in production.

### TLS

Only the `docker` profile enables TLS (PKCS12 keystore, self-signed by
`startup.sh` when absent — which is the intended operating mode, not a
fallback). Dev runs plain HTTP on `localhost:8080`. Settings and rationale:
[`05-ops/docker-and-profiles.md`](../05-ops/docker-and-profiles.md).

## Where to look in the code

| Concern | File |
|---|---|
| HTTP security config | `cms/src/main/java/ch/rupfizupfi/deck/security/SecurityConfiguration.java:27` (the `SecurityFilterChain` bean) |
| User loader | `cms/src/main/java/ch/rupfizupfi/deck/security/UserDetailsServiceImpl.java:17` |
| Authenticated principal -> JPA `User` | `cms/src/main/java/ch/rupfizupfi/deck/security/AuthenticatedUser.java:23` |
| Role check helper | `cms/src/main/java/ch/rupfizupfi/deck/security/UserUtils.java:9` |
| Marker interface | `cms/src/main/java/ch/rupfizupfi/deck/security/DataWithOwner.java:6` |
| Aspect | `cms/src/main/java/ch/rupfizupfi/deck/security/CheckUserCanOnlyAccessOwnDataAspect.java:19` |
| Spec injection | `cms/src/main/java/ch/rupfizupfi/deck/hilla/crud/CrudRepositoryServiceForOwnerData.java:21` |
| Spec helper | `cms/src/main/java/ch/rupfizupfi/deck/hilla/crud/OwnerDataHelper.java:22` |
| Annotation | `cms/src/main/java/ch/rupfizupfi/deck/security/CheckUserCanOnlyAccessOwnData.java` |
| Seed users | `cms/src/main/resources/data.sql:1-5` |

## Tenancy: what the model is actually for (decided 2026-08-16)

Ownership is a **real access-control requirement on the cloud cms**, not
just tidiness. The cms instance is reachable by users who may not belong
to the same organisation, so owner-scoped records (projects, samples, test
parameters, results) must not leak between them.

Live hardware telemetry is the exception. The `deck` deployment drives one
physical tester, so `/topic/load-cell`, `/topic/frequency-converter-info`
and `/topic/logs` are inherently shared: every operator watching that
machine sees the same force readings, and per-user filtering on those
topics would be meaningless. Per-user filtering on those topics is
therefore deliberately absent — a property of the single-machine
deployment, which would need revisiting if a deck instance ever fronted
more than one tester.

**Shared, not public.** The STOMP handshake at `/status` carries its own
authorization rule in `SecurityConfiguration` — `authenticated()`, never
`permitAll()`. The rule has to be explicit because `VaadinSecurityConfigurer`
closes the chain with `anyRequest().denyAll()`: a path that is neither a Vaadin
route, a Hilla endpoint nor framework-internal is denied outright, which is why
the socket answered **403** to logged-in operators until the rule was added. The
same applies to any future non-Vaadin endpoint — it needs a rule, and the
default of no rule fails closed.

## Open questions

1. **`@CheckUserCanOnlyAccessOwnData` covers only two services.**
   `SampleService` and `TestParameterService` carry it; `ProjectService`,
   `TestResultService` and `FileMetadataService` rely entirely on
   `CrudRepositoryServiceForOwnerData`. Given the cloud cms needs genuine
   isolation, the gap matters: audit each owner-scoped service and decide
   per service whether the base class alone is sufficient, then make the
   answer uniform. (OQ-37)
2. **The aspect silently no-ops on non-CRUD targets.** `getEntityById`
   only resolves the entity when the AOP target is a
   `CrudRepositoryService<?, ?>`; applied to a plain `@Service`, the `Long`
   argument goes unresolved and the check passes. A stricter pointcut or a
   loud failure would stop a future annotation from being decorative.
   (OQ-36)
3. **`System.out.println` in the aspect** (`CheckUserCanOnlyAccessOwnDataAspect.java:22`
   and `:40`) prints on every owner-scoped call. Replace with SLF4J at
   debug. (OQ-38)
