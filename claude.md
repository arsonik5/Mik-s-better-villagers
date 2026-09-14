# Mike's Better Villagers

Full docs live in `docs/` — read `docs/README.md` first. This file is just
the short version for quick reference.

**Platform**: Fabric, Minecraft **26.2**, **Java 25**, compiled directly
against Mojang's official mappings (no Yarn, no `mod*`-prefixed Gradle
configs anywhere in this project — `include` still works for jar-in-jar
bundling though) — see `docs/decisions-log.md` for why, and for several
non-obvious gotchas (vanilla `Slot` positions can't be moved, text-field
focus needs `Screen.setInitialFocus`/owo's `onKeyPress` not `keyPressed`,
`Minecraft.setScreen` is now `setScreenAndShow`, etc.) worth reading before
touching the GUI or menu code.

**Zero-dependency goal**: a player downloads just this mod's jar (plus the
standard Fabric Loader + Fabric API every Fabric mod needs) and it works —
no separate library installs, no Python, no manual model setup. owo-lib is
bundled (`include`) inside our jar; ModMenu is optional (`"suggests"`, not
`"depends"` — the mod fully works without it); the llama-server binary and
model download automatically on first world load. See `docs/architecture.md`.

**GUI**: built with owo-lib, a Skyrim-style bottom-anchored dialogue bar
with a blurred backdrop and a scrollable, persistent-per-villager
transcript — not a vanilla-textured window, not a floating modal. Free-text
chat input, still a blocking `Screen`. A separate `ModSettingsScreen`
(reachable via ModMenu if installed) covers LLM status/tokens-per-second,
a Hugging Face model search-and-download browser, a local model manager,
and GPU/context/thread/temperature settings.

**Menu**: `VillagerTalkMenu` is deliberately slot-less (`AbstractContainerMenu`,
not `MerchantMenu`) — trade execution is a direct validated request/apply
round trip (`VillagerTradeC2S`/`ModRegistry.executeTrade`), not vanilla's
drag-into-slots flow.

**LLM chat**: implemented and working — `LlamaServerProcess` bundles and
manages its own `llama-server` subprocess (never the player's own LM
Studio/Ollama setup), `LlamaClient` talks to it over its OpenAI-compatible
API, `PromptBuilder` assembles a system prompt + per-villager persona +
short in-memory history. **Not yet implemented**: SQLite-backed persistent
memory (`docs/memory-database.md`), the structured `say`+`actions` response
schema, and tool-calling (trade negotiation, remember/gossip, gesture
control) — all planned in `docs/llm-integration.md`, confirmed to stay pure
Java (no Python layer, to preserve the zero-dependency goal above).

## Core invariants (do not violate these when extending the mod)

- **Never trust model output against the game economy.** Any trade the LLM
  proposes goes through grammar-constrained decoding + a Java-side
  validator (item whitelist, count/price bounds, emerald-anchored sanity
  check) before it ever becomes a real, executable offer. A rejection must
  look like "nothing happened," never a crash or an exploit. (Not yet
  built — today there's no LLM-driven trade generation at all, vanilla
  auto-trades are off, and this invariant applies once that lands.)
- **Never block the server (or client) thread on an LLM or database call.**
  Both are async; results only touch game state via `server.execute(...)`
  (client-side: `this.minecraft.execute(...)`).
- **Degrade gracefully.** Missing model file, dead llama-server process,
  request timeout, missing database — every one of these must leave the
  game fully playable (no chat that turn, a clear "not listening"/"still
  gathering their thoughts" message), never a hang or a crash.
- **Ambient speech bubbles are mostly not live LLM calls** — a pre-generated
  pooled-line system, refilled occasionally, with live calls reserved for
  genuinely notable one-off events. (Not yet built.)
- **Keep docs/ in sync as the code changes** — the user has explicitly
  flagged this as very important. When architecture or plans change,
  update `docs/architecture.md` (what exists), `docs/decisions-log.md`
  (gotchas discovered), and the relevant plan doc (`llm-integration.md`/
  `memory-database.md`) in the same pass as the code, not as an afterthought.

## Build order

Phases 0–2 (scaffold boots, villager interception, the dialogue-bar GUI) are
done and playtested. LLM chat (steps 1–2 of `docs/llm-integration.md`'s own
build order) is done. Next: SQLite memory → structured trade
negotiation+guardrails → remember/gossip actions → gesture control.

Verify with `./gradlew runClient` after every change — never leave the tree
in a non-booting state between commits.
