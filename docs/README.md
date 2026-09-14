# Mike's Better Villagers — documentation index

- [architecture.md](architecture.md) — what the mod actually is right now: platform, module layout, the villager-interception + custom-menu design, the network protocol, the LLM integration, and the settings screen.
- [decisions-log.md](decisions-log.md) — non-obvious platform facts and pivots discovered while building this, so nobody re-derives them from scratch. Read this first if something in the code looks like it should've been done "the normal way" and wasn't.
- [llm-integration.md](llm-integration.md) — chat with the local LLM is implemented; this doc covers what's still planned: SQLite memory injection, the structured `say`+`actions` response schema, and tool-calling (trade negotiation, remember/gossip, gesture control).
- [memory-database.md](memory-database.md) — the SQLite schema for per-world / per-village / per-villager memory, and how it feeds the LLM prompt. Not yet implemented.

## Status as of this writing

- **Done and playtested**: villager interception + the owo-lib dialogue-bar GUI (Phases 0–2 of the original build order); real LLM chat over a bundled, auto-installing `llama-server` (no external dependency on LM Studio/Ollama/Python); a full in-game settings screen (LLM status, tokens/sec, Hugging Face model browser + local model manager, GPU/context/thread/temperature controls); persistent villager names; owo-lib bundled into the mod jar and ModMenu made a genuinely optional soft dependency, so the mod meets the "download the jar, no other installs" goal.
- **Not yet implemented**: SQLite-backed persistent memory, LLM-driven trade negotiation (vanilla auto-trades are intentionally off in the meantime), `remember`/`gossip`/`gesture` tool-calling, ambient speech bubbles.

Keep this doc set in sync with the code as it changes — update the relevant file(s) in the same pass as the code, not as an afterthought.
