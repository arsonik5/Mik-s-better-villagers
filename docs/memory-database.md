# Memory database

Status: **planned, not implemented.**

## One file per world, three logical scopes

The user's brief was "per villager db, per village, and per world." This is implemented as **one SQLite database file per Minecraft world save**, with three logical scopes as separate tables (not three separate physical `.db` files) — a single file with proper foreign keys is simpler to back up, migrate, and query across scopes (e.g. "what does this villager know, plus what's true for their village") than juggling cross-file joins, and SQLite has no real concurrency benefit from splitting it up for our access pattern (one write-heavy server process, no sharing across processes).

- **Location**: `<world save dir>/bettervillagers/memory.db` — travels with the save, deleted/copied/backed-up along with it naturally, and is trivially wiped by deleting one file if something goes wrong.
- **Driver**: `org.xerial:sqlite-jdbc` (currently `3.49.1.0`), added as a plain `implementation` dependency (see `decisions-log.md` on why not `modImplementation`) — it's a pure JDBC driver jar, no need to jar-in-jar it, but it does bundle native SQLite binaries per-platform internally, which is exactly why we pick it over hand-rolling anything.
- **Access**: all reads/writes happen off the server thread via a small dedicated executor (SQLite JDBC calls are blocking I/O) — same "never block the server thread" rule as the LLM HTTP calls. A simple single-writer-at-a-time discipline (one connection, `PRAGMA journal_mode=WAL` for read/write concurrency) is enough; this is not a high-throughput system.

## Schema

```sql
-- World scope: facts true for the whole save, not tied to any one village.
-- Kept deliberately small — most "world state" (raid active, time, weather)
-- is read live from the game itself, not stored here. This table is for
-- things that persist because they happened, not because they're currently true.
CREATE TABLE world_facts (
    id          INTEGER PRIMARY KEY,
    fact        TEXT NOT NULL,
    importance  INTEGER NOT NULL DEFAULT 1,   -- 1=minor, 5=major; gates whether it's ever worth the token budget to inject
    created_at  INTEGER NOT NULL              -- game-time tick, for recency ordering
);

-- A village is our own concept (vanilla has no first-class "village" entity):
-- a cluster of villagers sharing a bell or workstation cluster.
CREATE TABLE villages (
    id          INTEGER PRIMARY KEY,
    name        TEXT,                          -- optional, player- or LLM-assigned
    center_x    INTEGER NOT NULL,
    center_y    INTEGER NOT NULL,
    center_z    INTEGER NOT NULL,
    dimension   TEXT NOT NULL
);

CREATE TABLE village_facts (
    id          INTEGER PRIMARY KEY,
    village_id  INTEGER NOT NULL REFERENCES villages(id) ON DELETE CASCADE,
    fact        TEXT NOT NULL,
    about_player TEXT,                         -- player UUID string, nullable — rumors are often about someone specific
    sentiment   TEXT,                          -- e.g. 'positive' | 'negative' | 'neutral', nullable
    importance  INTEGER NOT NULL DEFAULT 1,
    created_at  INTEGER NOT NULL
);

CREATE TABLE villagers (
    uuid        TEXT PRIMARY KEY,               -- villager entity UUID, stable across chunk unload/reload
    village_id  INTEGER REFERENCES villages(id) ON DELETE SET NULL,
    name        TEXT,                           -- vanilla custom name if set, else profession-based display name
    profession  TEXT NOT NULL,
    persona_seed INTEGER NOT NULL,              -- derived once from the UUID; drives deterministic personality traits
    summary     TEXT,                           -- rolling conversation summary, refreshed lazily once history gets long
    updated_at  INTEGER NOT NULL
);

CREATE TABLE villager_memories (
    id          INTEGER PRIMARY KEY,
    villager_uuid TEXT NOT NULL REFERENCES villagers(uuid) ON DELETE CASCADE,
    fact        TEXT NOT NULL,
    about_player TEXT,                          -- player UUID string, nullable
    importance  INTEGER NOT NULL DEFAULT 1,
    created_at  INTEGER NOT NULL
);

CREATE TABLE conversation_turns (
    id          INTEGER PRIMARY KEY,
    villager_uuid TEXT NOT NULL REFERENCES villagers(uuid) ON DELETE CASCADE,
    player      TEXT NOT NULL,                  -- player UUID string
    role        TEXT NOT NULL,                  -- 'player' | 'villager'
    text        TEXT NOT NULL,
    created_at  INTEGER NOT NULL
);

CREATE INDEX idx_village_facts_village ON village_facts(village_id, created_at DESC);
CREATE INDEX idx_villager_memories_villager ON villager_memories(villager_uuid, created_at DESC);
CREATE INDEX idx_conversation_turns_villager ON conversation_turns(villager_uuid, created_at DESC);
```

Notes:

- `importance` exists on every fact table because token budget, not storage, is the real constraint — retrieval always orders by `importance DESC, created_at DESC` and takes the top few, never "everything."
- `about_player`/`sentiment` on `village_facts` and `about_player` on `villager_memories` let a future reputation/rumor system filter "what does this village think of *this specific player*" without a separate table.
- Nothing here duplicates vanilla's own reputation system (`VillagerGossips`/`GossipType`) — that stays authoritative for the numeric reputation value used in the prompt's live context frame (see `llm-integration.md`); this database is for the *narrative* facts and rumors layered on top of it.

## Village clustering

Villages aren't a vanilla concept, so we derive them ourselves: on world load (and periodically thereafter), villager entities within some radius of each other (or sharing a bell / workstation cluster, matching vanilla's own POI-based village heuristics if convenient to reuse) get grouped into a `villages` row, keyed by a rounded center point. A villager whose cluster changes (walked far away, joined a new settlement) gets its `village_id` updated — this is a cheap, occasional background task, not something that needs to run every tick.

## Pruning

Every fact table needs a cap, or a long-running world's save file (and the token budget) grows without bound:

- `villager_memories`: cap per villager (e.g. 50 rows), evict lowest `importance` then oldest when over.
- `village_facts`: cap per village (e.g. 200 rows), same eviction order.
- `world_facts`: cap globally (e.g. 500 rows) — this table should stay small by design (see "kept deliberately small" above).
- `conversation_turns`: only the last ~20 per villager are ever queried; older rows beyond a larger cap (e.g. 200) can be deleted outright, since the `summary` column on `villagers` is what preserves anything from further back.
- Villagers not seen in the world for a long time (dead, or the chunk hasn't loaded in N real-world days) are candidates for full row deletion via `ON DELETE CASCADE` from `villagers`, keeping the whole database bounded even in a world played for hundreds of hours.

## How this feeds the prompt

At the start of building a prompt (`PromptBuilder`, see `llm-integration.md`), the memory layer does three small queries:

1. `SELECT summary FROM villagers WHERE uuid = ?` — the villager's own rolling summary.
2. `SELECT fact FROM villager_memories WHERE villager_uuid = ? ORDER BY importance DESC, created_at DESC LIMIT 3`
3. `SELECT fact FROM village_facts WHERE village_id = ? ORDER BY importance DESC, created_at DESC LIMIT 2`

World facts are only pulled in the rare case `importance >= 4` and recent — most turns inject none at all. All of this is capped hard enough that the whole memory injection block stays well under 100 tokens, matching the token budget already set out in `llm-integration.md`.
