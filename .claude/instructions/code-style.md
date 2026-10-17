# Code Style

Ported from my-intelligence; the relational rules (table names) are replaced by MongoDB ones.

## MongoDB naming

- Platform collections are prefixed with `_` (`_apps`, `_history`, `_deps`); app data lives in the
  shared `docs` collection partitioned by `space` (see handoff §5).
- Field names are camelCase (`updatedBy`, `lastUsedAt`), matching the JSON the API returns.
- Indexes and TTLs are created at application startup (`MongoTemplate.indexOps`), never by manual
  scripts.

## `var` usage
Only use `var` when the type is immediately obvious from the right-hand side (constructor calls,
literals, factory methods with clear names). Otherwise write the explicit type.

```java
// good
var apps = new ArrayList<AppSummary>();

// bad — return type of getAuthentication() is not obvious
var auth = SecurityContextHolder.getContext().getAuthentication();

// good
Authentication auth = SecurityContextHolder.getContext().getAuthentication();
```

## Boolean naming — no `is` prefix

Boolean fields (records, documents, DTOs) are a plain word or adjective: `archived`, `builder`,
`enabled` — not `isArchived`. Jackson strips `is` from accessor names anyway, so an `is` prefix only
makes the field name and the JSON key disagree.

## Avoid inlining method calls as arguments

Extract a method call to a well-named local variable before passing it as an argument.

```java
// bad
return appStore.deploy(request, AuthenticationUtils.currentUser().email());

// good
String owner = AuthenticationUtils.currentUser().email();
return appStore.deploy(request, owner);
```

## Frontend

- Every HTTP call goes through `frontend/src/api/client.ts`; it is the only place an absolute URL is
  assembled.
- Server state lives in TanStack Query hooks in `api/queries.ts`, not in component state.
- Colours come from `styles/tokens.css` custom properties (light + dark); no hard-coded colours in
  components.
