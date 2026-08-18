---
name: deck-feature
description: Builds application features in this repo — JPA entities with full Hilla CRUD stack, React views in the file-based router, and the ownership/security model. Use when creating an entity, table, CRUD screen, page/view/route, shared React component, or custom @BrowserCallable endpoint, or when changing navigation, access control, roles, or multi-tenancy.
---

# Building a feature (cms / command-deck)

Sibling skills: `deck-hardware` owns the device layer and test engine; `deck-run` verifies the result in the real UI (run its `shots` + `crud-smoke` before calling feature work done); `deck-commit` gates what gets staged. This skill owns the application-feature patterns only.

Route to the right reference — read only what the task needs:

| Task | Read |
|---|---|
| New entity / table / CRUD screen (full chain) | [references/crud-entities.md](references/crud-entities.md) |
| New or changed view, routing, menu, `cms/` alias, shared component | [references/frontend-views.md](references/frontend-views.md) |
| Custom endpoint, access control, ownership, roles, Spring Security | [references/security.md](references/security.md) |

A new CRUD entity usually needs all three: the entity chain first, then the view rules, then a security pass.

## Rules that always apply

- **Document what you build, in the house style** — comments only for why/constraints (never narration), and a lean reference-heavy doc when a feature or mechanism warrants one (`doc/06-feature-work/<item>/README.md` for user stories, `doc/03-backend|04-frontend/` for mechanisms). The canonical rules: `.claude/skills/deck-doc/references/writing-style.md`.

- **Never edit anything under `src/main/frontend/generated/`** — Hilla regenerates it on every `bootRun`/build.
- Jackson 3 package split (Spring Boot 4): annotations from `com.fasterxml.jackson.annotation`, databind types from `tools.jackson.databind`. Wrong package compiles, fails at runtime.
- `:command-deck` includes all cms views and `@BrowserCallable` services — anything added to cms is live in the deck app too.
- Deep architecture docs live in `doc/03-backend/` and `doc/04-frontend/` — link targets are listed in each reference file.

Before staging changes, use the `deck-commit` skill (generated-file churn is a repo-specific trap).

## Gotchas (session-earned — append with a date when a session teaches one)

- (none yet — add facts here that a work session proved and the docs don't hold)
