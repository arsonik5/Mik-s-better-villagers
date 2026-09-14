# LLM integration plan

Status: **planned, not implemented.** This is the design for the next block of work: wiring a real local LLM behind the dialogue bar, giving it tools to actually act on the world (propose trades, remember things, gesture), and feeding it memory from the SQLite database (see `memory-database.md`).

## Goals and constraints

- Runs locally on the player's own GPU, alongside the game, on a **2–4B class model**. Latency budget: first token under ~400ms, a full reply under ~2s. This shapes almost every decision below (short prompts, no multi-round tool-call loops, aggressive KV-cache prefix reuse).
- The model is never trusted to directly mutate game state. Every action it proposes goes through the same validate-then-apply pipeline already built for trade execution (`ModRegistry.executeTrade`) — see "Tool-call action schema" below.
- Chat, trades, memory, and (later) gestures all come from **one conversation turn**, not a back-and-forth agentic loop calling tools and re-prompting the model. A single structured response per turn is what the latency budget allows.

## Process lifecycle: bundled `llama-server`

The mod bundles and manages its own `llama-server` (llama.cpp's HTTP server binary) as a subprocess — it does not talk to the user's separate LM Studio setup, and does not require the player to run anything themselves.

- **Binary**: resolved on first run in this order — (1) an explicit `llamaServerPath` config override, (2) an already-present binary under `<gameDir>/bettervillagers/bin/<os>-<arch>/`, sha256-checked against a bundled manifest, (3) a first-run download of a pinned llama.cpp release asset over HTTPS, verified against that manifest, unpacked, and marked executable on non-Windows. Never bundle a CUDA build inside the mod jar itself (100s of MB) — it's a separate download, with a progress toast; the mod stays fully playable (chat/trades degraded, everything else normal) while it happens.
- **Model**: same pattern — a small instruct GGUF (2–4B, Q4_K_M ballpark), path/URL/sha256 in config, never bundled in the jar. If no model is configured or the file is missing, degrade cleanly: no chat, a clear in-dialogue message, never a crash.
- **Launch**: `LlamaServerProcess` (singleton, server-side only — this must never exist in `src/client`), started on `ServerLifecycleEvents.SERVER_STARTING`, stopped on `SERVER_STOPPING` plus a JVM shutdown hook as backstop. Binds an ephemeral local port (`new ServerSocket(0)` probe-then-release) so it never collides with anything else the user has running (like their own LM Studio on 1234). `--host 127.0.0.1` only.
- **Health**: poll `GET /health` until ready (states `STARTING → READY → DEGRADED → STOPPED`); every request short-circuits to a fallback while not `READY`.
- **Draining stdout/stderr on a dedicated daemon thread is mandatory** — an undrained pipe will deadlock the child process.

## Async request path

One shared `java.net.http.HttpClient` on a small dedicated executor. Every call is async; results only ever touch game state via `server.execute(...)` — the server (or client) thread must never block on an HTTP round trip.

A `RequestGovernor` enforces: a global semaphore matching llama-server's `--parallel` slot count, one in-flight request per villager (a new request for the same villager replaces the queued one, never queues both), and priority ordering — an open dialogue's chat request always outranks an ambient/idle-line request (see below).

## Prompt structure

Ordered most-stable → least-stable, so llama-server's `--cache-reuse` actually hits the KV cache on turn 2+ of a conversation:

1. **System prompt** — identical, byte-for-byte, across every villager. States the rules: you are a Minecraft villager, reply in 1–2 short sentences, stay in character, never mention being an AI, never invent items that don't exist, and — critically — describes the exact JSON response shape (see below) and the fixed vocabulary of allowed action types.
2. **Persona** — per-villager, stable for its lifetime: name, profession, a couple of personality traits seeded deterministically from the villager's UUID (so the same villager is the same person across restarts, at zero storage cost beyond the UUID itself).
3. **Memory context** — assembled from SQLite (see `memory-database.md`): the villager's own rolling summary + a handful of recent personal facts, a couple of relevant village-scoped rumors, and rarely (only for genuinely major world events) a world-scoped fact. Kept to a hard token budget — this is a summarized/curated *injection*, not a raw dump of every row.
4. **Live context frame** — compact `key=value` lines, not prose: time of day, weather, raid active y/n, player reputation (from vanilla's own `VillagerGossips`/`GossipType`), the player's held item, a short filtered/whitelisted slice of their inventory (never all 36 slots), distance from the villager's home.
5. **History** — last ~3 turns verbatim; beyond that, a single rolling summary the model itself produces lazily in the background once history gets long, stored back into the villager's SQLite row.
6. **The player's new message** — length-capped client-side (already enforced: `EditBox.setMaxLength(160)`) and re-validated server-side.

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

1. `LlamaServerProcess` + health-check + shutdown lifecycle. Verify: boots, degrades cleanly with no model configured, no orphaned process after quitting the game (check Task Manager).
2. `LlamaClient` + `PromptBuilder` (system + persona + live context frame + history, no memory injection yet) + wire the dialogue bar's "You: ..." submit into a real request instead of the current `"(...)"` placeholder echo. Verify: a real streamed reply appears in the dialogue bar.
3. The SQLite memory layer (`memory-database.md`) + inject it into step 2's prompt. Verify: say something memorable, close the game, reopen, the villager still "remembers" it.
4. The grammar-constrained JSON response shape + `TradeValidator` + wiring `propose_trade` back into `VillagerOffersS2C`/`VillagerTradeC2S`. Verify: negotiate a trade in conversation, see it appear, execute it, confirm a hostile/malformed proposal (fixture-tested) never reaches the player as a real offer.
5. `remember` / `gossip` actions writing to SQLite. Verify with the adversarial fixtures from step 4's validator, generalized.
6. `gesture` as the first "controls the villager" action, client-visible.
