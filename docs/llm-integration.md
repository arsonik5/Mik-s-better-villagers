# LLM integration plan

Status: **basic chat is implemented and working (build-order steps 1–2 below); memory injection, the structured action schema, and tool-calling are still planned, not implemented.** See `architecture.md`'s "LLM integration" section for what actually exists today. This doc is the design for the rest of it: SQLite memory (`memory-database.md`), the `say` + `actions` response schema, and giving the model real (guardrailed) ways to act on the world.

## Goals and constraints

- Runs locally on the player's own GPU, alongside the game, on a **2–4B class model**. Latency budget: first token under ~400ms, a full reply under ~2s. This shapes almost every decision below (short prompts, no multi-round tool-call loops, aggressive KV-cache prefix reuse).
- The model is never trusted to directly mutate game state. Every action it proposes goes through the same validate-then-apply pipeline already built for trade execution (`ModRegistry.executeTrade`) — see "Tool-call action schema" below.
- Chat, trades, memory, and (later) gestures all come from **one conversation turn**, not a back-and-forth agentic loop calling tools and re-prompting the model. A single structured response per turn is what the latency budget allows.
- **The whole orchestration layer (memory DB, prompt building, action parsing/validation) is Java, not Python.** This was explicitly considered and rejected: a Python layer would mean either requiring players to have Python installed (breaks the zero-dependency goal — see `architecture.md`) or bundling a second portable runtime alongside llama-server (real extra packaging/lifecycle work for no new capability, since Java already does SQLite via JDBC, JSON via GSON, and HTTP just as well, in the same process, with lower latency than a second process hop).

## Process lifecycle: bundled `llama-server`

The mod bundles and manages its own `llama-server` (llama.cpp's HTTP server binary) as a subprocess — it does not talk to the user's separate LM Studio setup, and does not require the player to run anything themselves.

- **Binary** (implemented, `NativeAssets`): resolved on first run in this order — (1) an explicit `llamaServerPath` config override, (2) an already-present binary anywhere under `<gameDir>/bettervillagers/bin/` (found by walking the tree for the executable name, not a hardcoded path — see `decisions-log.md`), (3) a first-run download of a pinned llama.cpp release (the Vulkan-accelerated build, for broad GPU-vendor compatibility) from GitHub, extracted, executable bit set on non-Windows. **Not yet implemented from the original plan**: sha256 verification against a manifest — currently any successful download is trusted as-is. The mod stays fully playable (chat degraded, everything else normal) while downloading.
- **Model** (implemented, `ModelAssets`): same pattern, via `ModConfig.modelPath` (local file override) or `modelDownloadUrl` (auto-download, filename derived from the URL). No sha256 verification here either, same caveat as the binary. Downloads are **resumable** (HTTP `Range`, verified against the real Hugging Face URL — see `decisions-log.md`) rather than restarting from byte 0 on every interrupted attempt. Download progress is published to `ModelDownloadState` for the settings screen. If no model resolves, degrades cleanly — no chat, a clear in-dialogue message ("doesn't seem to be listening"/"still gathering their thoughts"), never a crash.
- **Launch** (implemented, `LlamaServerProcess`): singleton, server-side only, started on `ServerLifecycleEvents.SERVER_STARTING`, stopped on `SERVER_STOPPING`. Binds an ephemeral local port (`new ServerSocket(0)` probe-then-release) so it never collides with anything else running locally (LM Studio, Ollama, ...). `--host 127.0.0.1` only. **Stale-process recovery** (implemented, see `decisions-log.md`): writes the child's PID to `bettervillagers/llama-server.pid`, and on the next launch kills any still-alive process from that file before starting a new one — a forceful kill of the parent (crash, `-Force`, etc.) otherwise orphans the subprocess indefinitely, still holding GPU memory. A JVM shutdown hook is also registered as a backstop for graceful exits.
- **Health** (implemented): polls `GET /health` until ready (`STARTING → READY → DEGRADED → STOPPED`); every request short-circuits to a fallback while not `READY`.
- **Draining stdout on a dedicated daemon thread** (implemented) — mandatory, an undrained pipe deadlocks the child process.

## Async request path

**Implemented**: one shared `java.net.http.HttpClient` (`LlamaClient`) on a small dedicated executor; results only touch game state via `server.execute(...)`.

**Not yet implemented**: the `RequestGovernor` described below. Right now a second chat request while one is already in flight for the same villager is *not* deduplicated or queued — it would just fire a second concurrent HTTP call. Fine for a single player having one conversation at a time (today's only real usage pattern) but worth building before ambient bubbles or multiplayer make concurrent requests per villager actually happen:

A `RequestGovernor` should enforce: a global semaphore matching llama-server's `--parallel` slot count, one in-flight request per villager (a new request for the same villager replaces the queued one, never queues both), and priority ordering — an open dialogue's chat request always outranks an ambient/idle-line request (see below).

## Prompt structure

Ordered most-stable → least-stable, so llama-server's KV cache actually gets reused turn-to-turn (note: `--cache-reuse` / `--cont-batching` flags are set on launch, but prefix-reuse hit rate hasn't been explicitly measured yet — worth verifying once memory injection makes prompts more complex).

**Implemented** (`PromptBuilder`): system prompt (fixed, identical across villagers) → persona line (name from `VillagerNaming`, profession, one trait picked deterministically from the villager's UUID) → recent history from `VillagerBrain` (in-memory only, capped at 6 turns, lost on server restart) → the new message.

**Not yet implemented** — the rest of this section is still the plan:

1. ~~System prompt~~ *(done, see above — though it doesn't yet describe a JSON response shape/action vocabulary, since that schema isn't built yet either)*.
2. ~~Persona~~ *(done, see above)*.
3. **Memory context** — assembled from SQLite (see `memory-database.md`): the villager's own rolling summary + a handful of recent personal facts, a couple of relevant village-scoped rumors, and rarely (only for genuinely major world events) a world-scoped fact. Kept to a hard token budget — this is a summarized/curated *injection*, not a raw dump of every row.
4. **Live context frame** — compact `key=value` lines, not prose: time of day, weather, raid active y/n, player reputation (from vanilla's own `VillagerGossips`/`GossipType`), the player's held item, a short filtered/whitelisted slice of their inventory (never all 36 slots), distance from the villager's home.
5. **History beyond ~3 turns** — a single rolling summary the model itself produces lazily in the background once history gets long, stored back into the villager's SQLite row (today: just a hard cap at 6 in-memory turns, no summarization).
6. **Message length limit** — client-side is currently a generous `2000` chars (`VillagerTalkScreen`/`ModSettingsScreen`, raised from an earlier `160` that was too restrictive for actual conversation); server-side re-validation of length isn't explicit yet beyond the network codec's own cap.

## Response shape and the tool-call action schema

The model returns **one JSON object per turn**, decoded with a GBNF grammar built at request time from the live whitelist (same mechanism already planned for trade validation) so malformed output is structurally impossible, not just "usually fine":

```json
{
  "say": "Oh, hello again! Need something?",
  "actions": [
    { "type": "propose_trade", "give_item": "minecraft:bread", "give_count": 3, "receive_item": "minecraft:emerald", "receive_count": 1 },
    { "type": "remember", "fact": "player said they're building a new farm near the river" },
    { "type": "gesture", "id": "wave" }
  ]
}
```

`actions` is optional and usually empty or has one entry — this is not a general agent loop, just a small fixed vocabulary the model can reach for:

| Action | Effect | Validated by |
|---|---|---|
| `propose_trade` | Adds a candidate trade offer to the villager's current, in-memory offer list, which is what actually gets sent to the client via `VillagerOffersS2C` and executed via the existing `VillagerTradeC2S` round trip. | The trade-validator pipeline: item whitelist + hard blacklist, count bounds, an emerald-anchored economic sanity ratio (reject or clamp), require at least one side of the trade to be `minecraft:emerald`. Any failure silently drops the proposed action — the model's `say` text still comes through. |
| `remember` | Inserts one row into `villager_memories` (see `memory-database.md`). | Length-capped, de-duplicated against very-similar recent entries, capped count per villager (oldest pruned). |
| `gossip` | Inserts a village-scoped rumor, optionally about a named player, with a simple sentiment tag. | Same caps as `remember`, scoped to the village table instead. |
| `gesture` | Triggers a short client-visible animation/emote on the villager entity (wave, nod, shrug, point) — this is the "controlling the villager itself" hook, kept intentionally small. | Fixed enum of known gesture ids; unknown ids are ignored, never passed through as arbitrary data. |
| `walk_to` *(later)* | Nudges the villager's brain toward a point of interest it already knows about (its bed, job site, the bell) — **not** arbitrary coordinates from the model. | Restricted to POIs the villager's own `Brain` already tracks; anything else is ignored. |

Every action is optional-by-design: if the model omits `actions` entirely, or emits one that fails validation, the conversation still works — the player just doesn't see a new trade/gesture that turn. This mirrors the existing "fallback to vanilla trades" philosophy from earlier design work, generalized: a failure here always degrades to "nothing happened," never to a crash or an exploit.

## Ambient behavior (later, not the next milestone)

Speech-bubble-style ambient lines (villagers occasionally saying something while the player is nearby but not in conversation) should **not** cost one LLM call per bubble — that was already identified as a latency/scale problem in the original design pass. Batch-generate a small pool of short idle lines per (profession, time-of-day, weather) bucket during idle time, refill the pool when it runs low, and only trigger a real live call for genuinely notable one-off events (first meeting, raid start, a trade just happened). This is out of scope for the current milestone (which is: get one real conversation working end-to-end with memory and one action type) but the `RequestGovernor` priority system above already leaves room for it.

## Build order for this milestone

1. ✅ `LlamaServerProcess` + health-check + shutdown lifecycle. Verified: boots, downloads binary+model on first run, degrades cleanly when not ready.
2. ✅ `LlamaClient` + `PromptBuilder` (system + persona + history, no memory injection yet) + real chat over the network (`VillagerChatC2S`/`S2C`) replacing the earlier `"(...)"` placeholder echo. Verified: a real model reply appears in the dialogue bar. *(Not streamed — non-streaming `/v1/chat/completions`, since the "..." placeholder swap already hides the latency reasonably well; revisit if replies feel slow once memory injection adds prompt overhead.)*
3. **Next**: the SQLite memory layer (`memory-database.md`) + inject it into step 2's prompt. Verify: say something memorable, close the game, reopen, the villager still "remembers" it.
4. The grammar-constrained JSON response shape + `TradeValidator` + wiring `propose_trade` back into `VillagerOffersS2C`/`VillagerTradeC2S`. Verify: negotiate a trade in conversation, see it appear, execute it, confirm a hostile/malformed proposal (fixture-tested) never reaches the player as a real offer.
5. `remember` / `gossip` actions writing to SQLite. Verify with the adversarial fixtures from step 4's validator, generalized.
6. `gesture` as the first "controls the villager" action, client-visible.

**Also picked up along the way, not originally in this list**: `ModConfig` (persisted settings), `VillagerNaming` (real persistent names instead of generic profession labels), and `ModSettingsScreen` (a full in-game settings UI — LLM status, tokens/sec, GPU/context/thread/temperature controls, a Hugging Face model search/browse/download flow, and a local model manager with delete/switch) — originally deferred to "a future ModMenu integration," built now instead since it was needed to actually pick/swap models without hand-editing JSON. See `architecture.md`.
