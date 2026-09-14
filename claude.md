# Mike's Better Villagers

Full docs live in `docs/` — read `docs/README.md` first. This file is just
the short version for quick reference.

**Platform**: Fabric, Minecraft **26.2**, **Java 25**, compiled directly
against Mojang's official mappings (no Yarn, no `modImplementation`) — see
`docs/decisions-log.md` for why, and for several non-obvious gotchas
(vanilla `Slot` positions can't be moved, `EditBox` focus needs
`Screen.setInitialFocus`, etc.) worth reading before touching the GUI or
menu code.

**GUI**: built with owo-lib (`docs/architecture.md`), a Skyrim-style
bottom-anchored dialogue bar — not a vanilla-textured window, not a
floating modal. Free-text chat input, still a blocking `Screen`.

**Menu**: `VillagerTalkMenu` is deliberately slot-less (`AbstractContainerMenu`,
not `MerchantMenu`) — trade execution is a direct validated request/apply
round trip (`VillagerTradeC2S`/`ModRegistry.executeTrade`), not vanilla's
drag-into-slots flow. See `docs/architecture.md`.

**LLM + memory** (`docs/llm-integration.md`, `docs/memory-database.md`):
planned, not yet implemented.

## Core invariants (do not violate these when extending the mod)

- **Never trust model output against the game economy.** Any trade the LLM
  proposes goes through grammar-constrained decoding + a Java-side
  validator (item whitelist, count/price bounds, emerald-anchored sanity
  check) before it ever becomes a real, executable offer. A rejection must
  look like "nothing happened," never a crash or an exploit.
- **Never block the server (or client) thread on an LLM or database call.**
  Both are async; results only touch game state via `server.execute(...)`.
- **Degrade gracefully.** Missing model file, dead llama-server process,
  request timeout, missing database — every one of these must leave the
  game fully playable (no chat that turn, a clear "not talking right now"
  message), never a hang or a crash.
- **Ambient speech bubbles are mostly not live LLM calls** — a pre-generated
  pooled-line system, refilled occasionally, with live calls reserved for
  genuinely notable one-off events. (Not yet built.)

## Build order

Phases 0–2 (scaffold boots, villager interception, the dialogue-bar GUI) are
done and playtested. Next: `docs/llm-integration.md`'s own build order
(llama-server lifecycle → basic chat → SQLite memory → trade
negotiation+guardrails → remember/gossip actions → gesture control).

Verify with `./gradlew runClient` after every change — never leave the tree
in a non-booting state between commits.
