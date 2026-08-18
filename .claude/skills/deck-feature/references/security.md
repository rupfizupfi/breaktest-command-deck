# Ownership and security

Code: `cms/src/main/java/ch/rupfizupfi/deck/security/` and `hilla/crud/`. Background: `doc/03-backend/security-and-tenancy.md`.

## The model

- Owned entities implement `DataWithOwner` (`@Nullable User getOwner()`), e.g. `Sample`, `Project`, `TestResult`, `TestParameter`.
- **`owner == null` means shared with everyone** (load-bearing in `OwnerDataHelper`). Don't "fix" null owners.
- Admins (`ROLE_ADMIN`, via `UserUtils.isAdmin()`) bypass ownership checks.

## Two enforcement mechanisms — know which one protects what

**1. AOP aspect** `@CheckUserCanOnlyAccessOwnData` (method or class level, `CheckUserCanOnlyAccessOwnDataAspect`):
- Only fires when the **first argument** of the method is the owned entity or its `Long` id (id is re-loaded via the service's repository).
- Consequence: it protects `save`/`delete`/`get(id)` but **never filters `list(Pageable, Filter)`** — Pageable is the first arg.

**2. Query-level filtering** `CrudRepositoryServiceForOwnerData`:
- Overrides `get()`, `list()`, `delete()` and ANDs `owner == currentUser OR owner IS NULL` into the JPA Specification (`OwnerDataHelper`).
- Use this base class whenever list results must be scoped to the owner.

## When writing a custom @BrowserCallable method

Checklist:
1. Does it take an owned entity or id? Put it as the **first parameter** and annotate the method (or class) with `@CheckUserCanOnlyAccessOwnData` — otherwise the aspect silently doesn't apply.
2. Does it return lists of owned data? Filter with `OwnerDataHelper.userIsOwnerOrDataWithoutOwner(...)` in the Specification, or go through a `CrudRepositoryServiceForOwnerData`.
3. Does it reach owned data indirectly (files, child rows)? The aspect can't see that — enforce manually (example: `FileMetadataService` uses a subquery on `TestResult`; it still carries a `@TODO implement security`).
4. Class-level auth is still required: `@PermitAll`, `@RolesAllowed("ROLE_ADMIN")`, or `@AnonymousAllowed`. Hilla denies unannotated endpoints by default.

## Spring Security config facts

- `SecurityConfiguration` uses the Vaadin 25 style `VaadinSecurityConfigurer.vaadin()` (the old `VaadinWebSecurity` base class is removed).
- `/api/**` REST endpoints are `permitAll` and excluded from Vaadin routing; CSRF is disabled for `/api/files/upload*`.
- Frontend route guarding is `loginRequired: true` in each view's `ViewConfig` plus `.protect()` in the deck's `routes.tsx` — UI-level only; real enforcement is the endpoint annotations above.
- Remember: `:command-deck` exposes **all** cms `@BrowserCallable` services too — a new cms endpoint is automatically reachable in the deck deployment.
