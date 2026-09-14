# Mike's Better Villagers — documentation index

- [architecture.md](architecture.md) — what the mod actually is right now: modloader/version, module layout, the villager-interception + custom-menu design, the network protocol.
- [decisions-log.md](decisions-log.md) — non-obvious platform facts and pivots discovered while building this, so nobody re-derives them from scratch. Read this first if something in the code looks like it should've been done "the normal way" and wasn't.
- [llm-integration.md](llm-integration.md) — the plan for wiring in the local LLM: process lifecycle, prompt/context design, the tool-call action schema, guardrails.
- [memory-database.md](memory-database.md) — the SQLite schema for per-world / per-village / per-villager memory, and how it feeds the LLM prompt.

Status as of this writing: the interaction/GUI layer (Phases 0–2 of the original build order) is done and playtested. The LLM, memory, and tool-calling layer (this doc set) is planned but not yet implemented.
