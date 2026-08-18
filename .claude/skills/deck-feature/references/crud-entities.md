# Adding a CRUD entity

The repo has one dominant pattern for CRUD entities. Follow the `Sample` chain exactly. Background: `doc/03-backend/hilla-services.md`.

## Checklist

```
- [ ] 1. Entity in cms/src/main/java/ch/rupfizupfi/deck/data/
- [ ] 2. Repository (JpaRepository + JpaSpecificationExecutor)
- [ ] 3. Service in api/services/ (pick base class — see table)
- [ ] 4. Run bootRun to regenerate TypeScript
- [ ] 5. AutoCrud factory in cms/src/main/frontend/components/autocrud/
- [ ] 6. View shell in cms/src/main/frontend/views/
- [ ] 7. Patch empty-value creation if entity has relations
```

## 1. Entity — `cms/src/main/java/ch/rupfizupfi/deck/data/<Entity>.java`

Conventions (copy from `Sample.java`):
- **Public fields**, no getters/setters (Hilla reads fields directly). Extend `AbstractEntity` (provides `id`, `@Version`, equals/hashCode).
- If user-owned: `implements DataWithOwner`, add an `owner` field with `@JsonSerialize(using = OwnerSerializer.class)` and a `@Nullable public User getOwner()`. **A null owner means "shared / visible to everyone".**
- Nullability drives Hilla's TS optionals: the `data/` package declares `@NonNullApi` in `package-info.java`. Mark optional fields `@Nullable`. **A new package needs its own `package-info.java`.**

## 2. Repository

```java
public interface XRepository extends JpaRepository<X, Long>, JpaSpecificationExecutor<X> {}
```

`JpaSpecificationExecutor` is **required** — Hilla grid filters use Specifications.

## 3. Service — `cms/src/main/java/ch/rupfizupfi/deck/api/services/<Entity>Service.java`

Pick the base class:

| Situation | Pattern | Example |
|---|---|---|
| Owned entity, per-item ownership check | `CrudRepositoryService` + `@CheckUserCanOnlyAccessOwnData` | `SampleService` |
| Owned entity, lists must be filtered to owner | extend `CrudRepositoryServiceForOwnerData` | `ProjectService`, `TestResultService` |
| Shared master data, no ownership | plain `CrudRepositoryService` | `MaterialService` |
| Admin-only | `@RolesAllowed("ROLE_ADMIN")`, override `save()` if needed | `UserService` |

Typical service is 4 lines:

```java
@BrowserCallable
@PermitAll
@CheckUserCanOnlyAccessOwnData
public class XService extends CrudRepositoryService<X, XRepository> {}
```

Caveat: the `@CheckUserCanOnlyAccessOwnData` aspect only fires when the entity or its `Long` id is the **first method argument** — it does NOT filter `list()`. If list results must be owner-scoped, use `CrudRepositoryServiceForOwnerData` instead. Details: [security.md](security.md).

## 4. Regenerate TypeScript

Run `./gradlew :cms:bootRun` (or a build) — Hilla writes `generated/<X>Service.ts`, `generated/ch/rupfizupfi/deck/data/<X>.ts` + `<X>Model.ts`, and re-exports via `generated/endpoints.ts`.

## 5. AutoCrud factory — `cms/src/main/frontend/components/autocrud/<entity>.tsx`

Keep column/field config here, not in the view. Copy `components/autocrud/sample.tsx`:
- `createEmptyValueProxy(XModel)` if the entity has an owner (pre-fills current user).
- Relations → `AutoComboBox` (`@ManyToOne`) or `MultiSelectComboBox` (`@ManyToMany`) with `createAutoComboBoxService(RelatedService, "name")`.
- Owner column → `OwnerGridView` renderer; owner field → `OwnerSelector` (imported from `cms/components/owner/OnwerSelector` — the filename typo is intentional/established).

## 6. View — `cms/src/main/frontend/views/<entity>.tsx`

Six-line shell (see `views/sample.tsx`):

```tsx
export const config: ViewConfig = {menu: {order: N, icon: 'line-awesome/svg/....svg'}, title: 'X', loginRequired: true};
export default function XView() { return buildAutoCrud(XService); }
```

Routing rules, module split, and import aliases: [frontend-views.md](frontend-views.md).

## 7. Relations and empty values

If the entity has relations, `Model.createEmptyValue()` recurses into them and breaks the form. Register the model in `cms/src/main/frontend/model/init.ts` via `makeObjectEmptyValueCreatorIgnoreRelation()` like the existing entities.

## Verify

`./gradlew :cms:bootRun`, log in as `user`/`user` (seeded by `data.sql`), open the new view, create/edit/copy/delete a row.
