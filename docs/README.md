# Documentation Structure

- `docs/spec.md` — Confirmed specification. Authoritative baseline integrating design decisions and change history. Records "what is currently decided" concisely. Covers the existing Android app (parent + child roles) only.
- `docs/detailed-spec.md` — Detailed specification. Expansion of `spec.md` to a level of detail usable for per-feature implementation plans (`docs/plans/<feature-name>/PLAN.md`). Covers the existing Android app only.
- `docs/pc-client-spec.md` / `docs/pc-client-detailed-spec.md` — Companion confirmed/detailed specification pair for the Windows PC viewer/controller client, an additional client type alongside the Android child app. Same house style and confirmed-vs-detailed split as the pair above, kept as separate documents rather than appended sections; cross-references `docs/spec.md`/`docs/detailed-spec.md` by section number wherever it depends on or reuses existing content (wire protocol, pairing model, connection-state timeouts, etc.) instead of duplicating it.
- `docs/DECISIONS.md` — Background of decisions, comparative analysis, rejected alternatives, detailed reference information.
- `docs/QA.md` — Questions requiring the user's own decision, raised during implementation planning.
- `docs/RESOLVED-ISSUES.md` — Log of closed GitHub issues (issue number, title, labels, PR link).
- `docs/plans/<feature-name>/PLAN.md` — Per-feature implementation plans, created by developer-lite as each feature is planned.
