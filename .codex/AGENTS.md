# AGENTS.md

## Eval Tasks

Place new evaluation reports in `docs/experiment/`. Use a separate filename for each follow-up report so earlier results remain available for comparison.

Before designing, running, scoring, or comparing an Agent Eval, read [evals/METHODOLOGY.md](../evals/METHODOLOGY.md), then [evals/PLAN.md](../evals/PLAN.md). Record the methodology, dataset, rubric, and tested Agent versions; freeze criteria before trials and follow the documented revision process. Keep unverified reference facts and thresholds explicitly marked. Do not call paid models or run live evaluations until the user has confirmed the course materials and AI budget.

## Engineering Principles

- Do not preserve backward compatibility. Remove obsolete paths instead of adding compatibility layers, fallbacks, or migrations.
- Choose the simplest implementation that fully meets the current requirements. Avoid speculative abstractions, configuration, and indirection.
- Grow the system in layers. Start from the smallest version that works end to end, and add each new capability on top of a product that already works. Never trade a working product for unfinished complexity.
- Keep components modular and concerns clearly separated.
- Prefer established, well‑maintained libraries when they reduce overall complexity or improve reliability. Do not reimplement common functionality without a clear reason.
- Lean on the dependencies already in the project before writing your own implementation or adding packages. Do not assume a library lacks a capability without checking its documentation and types.
- Make architectural decisions for the long term. Do not accept a stopgap that only works for now and is meant to be replaced later.
- After every repository change, append a concise entry to the root `ASYNC_ANALYSIS_REFACTOR.md` with the date, what changed, validation result, and commit hash. Keep this local changelog brief and Git-ignored.
