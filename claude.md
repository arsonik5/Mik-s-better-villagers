# Mike's Better Villagers — mod architecture notes

Fabric mod, pinned to **Minecraft 26.2** (Mojang switched to year-based
version numbers during 2026; this is the version Fabric's own current
`gradle.properties` convention calls `26.2` directly — no separate "1.21.x"
jar version anymore). **Java 25** (Minecraft itself now requires it — this
machine has JDK 25 installed at `C:\Program Files\Microsoft\jdk-25.0.4.7-hotspot`,
set `JAVA_HOME` to it before running `gradlew`). **No explicit Yarn mappings
dependency** — current Fabric Loom (`1.17-SNAPSHOT` as of this writing) no
longer exposes a `mappings()` DSL method; mod code is written directly
against whatever Loom now resolves internally, confirmed by mirroring the
live `FabricMC/fabric-example-mod` template exactly (cloned and diffed
during setup — don't reintroduce a `mappings "net.fabricmc:yarn:..."` line,
it will fail with "Could not find method mappings()"). Regular
`implementation`, not `modImplementation`, is what the current template uses
for `fabric-loader`/`fabric-api` too — mirror that, don't "fix" it back.
Split environment source sets: `src/main` is common/server logic, `src/client`
is client-only rendering. LLM and trade-validation code must stay in
`src/main` (server-authoritative) — never in `src/client`.

## Core design invariants (do not violate these when extending the mod)

- **Never trust model output against the game economy.** Trade generation
  goes through grammar-constrained decoding (GBNF) + a Java-side
  `TradeValidator` (whitelist, bounds, economic sanity clamp) before any
  `TradeOffer` is applied. Any rejection falls back to vanilla's own
  profession/level trade tables — a failure must look like "ordinary
  vanilla trades," never a crash or an exploit.
- **Never block the server thread on an LLM call.** All HTTP calls to
  llama-server are async (`java.net.http.HttpClient` on a small dedicated
  executor); results only touch game state via `server.execute(...)`.
- **Reuse vanilla textures/widgets for all GUI** — no custom art. The
  villager talk screen is modeled directly on vanilla `MerchantScreen` and
  must look indistinguishable from it (panel, buttons, scroller, tooltips)
  at every GUI scale and under a resource pack.
- **`VillagerTalkScreenHandler extends MerchantScreenHandler`** is
  load-bearing: the client's trade-offer sync packet handling accepts any
  `instanceof MerchantScreenHandler`, so subclassing gets vanilla trade sync,
  slot handling, and price computation for free. Don't reinvent that plumbing.
- **Degrade gracefully.** Missing model file, dead llama-server process,
  request timeout — every one of these must leave the game fully playable
  (vanilla-equivalent trades, a clear "not talking right now" message), never
  a hang or a crash.
- **Ambient speech bubbles are mostly not live LLM calls.** They're drawn
  from a pre-generated, cached line pool per (profession, time-of-day,
  weather, raid-state) bucket, refilled by occasional batched calls. Only
  rare, genuinely notable events (raid start, first meeting, trade done)
  warrant a live low-priority call.

## Build order

0. Scaffold boots (`./gradlew runClient` reaches the main menu with the mod
   loaded).
1. Villager interception (`UseEntityCallback`) + empty custom screen —
   vanilla trade GUI must never open.
2. Full vanilla-look GUI with static/fake data (tabs, trades list, chat
   echo) — verify visually against real `MerchantScreen`.
3. Wire in llama-server (bundled subprocess) for streamed chat.
4. Trade generation + full guardrail pipeline (grammar + `TradeValidator`).
5. Speech bubbles (scheduler, pooled lines, world-space rendering).
6. Polish: config screen, translations, sounds, commands, dedicated-server
   testing.

Verify with `./gradlew runClient` after every phase — never leave the tree
in a non-booting state between phases.
