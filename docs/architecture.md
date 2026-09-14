# Architecture

## Platform

- **Fabric**, Minecraft **26.2** (Mojang's year-based version; the jar/mappings/Loom coordinate is `26.2` directly — there is no separate "1.21.x" number for this release, see `decisions-log.md`).
- **Java 25** (required by this Minecraft version itself, not a project choice).
- Compiled directly against **Mojang's official mappings** — no Yarn, no remapping step, and no `mod*`-prefixed Gradle configurations exist in this Loom setup at all (`modImplementation`, `modCompileOnly`, etc. all fail with "could not find method"). Third-party dependencies use plain `implementation`/`compileOnly`.
- Fabric Loom `1.17-SNAPSHOT` (tracks `1.17.20` internally as of this writing), Gradle `9.5.1` (via the wrapper — don't hand-install Gradle).
- UI built with **owo-lib** (`io.wispforest:owo-lib`), a declarative Fabric UI framework — not hand-rolled `fill()`/`text()` calls. See "GUI" below for why.
- **Zero-dependency goal**: a player should be able to download just this mod's jar (plus the completely standard Fabric Loader + Fabric API every Fabric mod needs) and have it work — no separate library downloads, no Python, no manual model setup. Concretely: owo-lib is `include`d (jar-in-jar bundled inside our own jar, verified via `unzip -l` showing `META-INF/jars/owo-lib-*.jar` in the built jar) since it's a niche library most players won't already have; ModMenu is `compileOnly` + listed under `"suggests"` (not `"depends"`) in `fabric.mod.json` since it's optional polish for players who already have it, and the game must still launch and the mod must still fully work without it; the llama-server binary and the default model are downloaded automatically on first world load (see "LLM integration" below), not bundled (a 30MB binary and a 1-3GB model have no business inside the mod jar itself, but nothing about getting them is a manual step for the player).

Run `./gradlew runClient` to launch a dev client; `./gradlew build` to compile. Set `JAVA_HOME` to a JDK 25 install before invoking either (this machine: `C:\Program Files\Microsoft\jdk-25.0.4.7-hotspot`).

## Module layout

```
src/main/java/dev/mike/bettervillagers/
  BetterVillagers.java         ModInitializer — villager-interception hook, starts/stops the LLM process
  ModRegistry.java              Registers menu type + network payloads; server-side trade execution + chat handling
  config/
    ModConfig.java              GSON-backed settings file (model, GPU layers, context size, ...)
  screen/
    VillagerTalkMenu.java       Slot-less AbstractContainerMenu (see "Menu" below)
    VillagerInteraction.java    Opens the menu when a player right-clicks a villager
  brain/
    VillagerBrain.java          Per-villager in-memory conversation history (not yet SQLite-backed)
    BrainStore.java             UUID -> VillagerBrain registry
  llm/
    LlamaServerProcess.java     Owns the bundled llama-server subprocess: resolve/download, launch, health, shutdown
    NativeAssets.java           Resolves/downloads/extracts the llama-server binary for the current OS
    ModelAssets.java            Resolves/downloads the configured model, reports progress to ModelDownloadState
    ModelDownloadState.java     Shared download-progress state the settings screen polls
    LlamaClient.java            Async HTTP client for llama-server's OpenAI-compatible /v1/chat/completions
    PromptBuilder.java          System prompt + persona + history -> message list
  util/
    VillagerNaming.java         Assigns each villager a persistent, deterministic display name
  net/
    VillagerChatC2S.java / VillagerChatS2C.java     Player message <-> villager reply
    VillagerTradeC2S.java                            Client -> server: "execute this trade index"
    VillagerOffersS2C.java                           Server -> client: current trade offers (unused until trade negotiation exists)
src/client/java/dev/mike/bettervillagers/client/
  BetterVillagersClient.java    ClientModInitializer — registers the screen + chat/offers receivers
  BetterVillagersModMenu.java   Optional ModMenu entrypoint (only invoked if the player has ModMenu installed)
  gui/
    VillagerTalkScreen.java     The owo-lib Skyrim-style dialogue bar
    ModSettingsScreen.java      LM-Studio-style settings: HF model browser, local model manager, live status
    ClientChatHistoryStore.java Per-villager transcript kept alive across closing/reopening the dialogue screen
  model/
    HuggingFaceSearch.java      Client-only Hugging Face search + file-listing for the model browser
```

## Villager interception

`UseEntityCallback.EVENT` (Fabric API, `fabric-events-interaction-v0`) intercepts right-clicking a `Villager`, returning `InteractionResult.SUCCESS` before vanilla's own trade-menu logic runs. No mixin needed.

## Menu: deliberately slot-less

`VillagerTalkMenu extends AbstractContainerMenu` directly — **not** `MerchantMenu`. This was a real pivot: the original design extended `MerchantMenu` to get vanilla's trade-offer sync and slot machinery "for free," but that machinery comes with vanilla's 36-slot player-inventory grid and 3 payment/result slots baked into fixed, unchangeable (`Slot.x`/`Slot.y` are `public final`) positions — impossible to hide or relocate, and it kept visually leaking through every custom screen we tried. Since trades will ultimately be model-proposed and directly applied (not dragged into slots) anyway, we cut the slot machinery entirely:

- `VillagerTalkMenu` has zero `Slot`s. `quickMoveStack` returns `ItemStack.EMPTY`, `stillValid` just checks the player is alive.
- Registered via Fabric API's `ExtendedMenuType<VillagerTalkMenu, Integer>` (`net.fabricmc.fabric.api.menu.v1`), carrying the villager's entity id as the "opening data" so the client knows which villager it's talking to.
- **Important gotcha**: `MerchantMenu`'s constructors hardcode `MenuType.MERCHANT` internally. Even a subclass that never touches slots would still report the wrong menu type unless it overrides `getType()` to return its own registered type — Fabric's extended-menu-provider check silently refuses to open otherwise. Long since moot for us (we don't extend `MerchantMenu` anymore) but worth knowing if a future contributor is tempted to go back to that approach.

## Trade execution: a direct request, not a drag-and-drop

Since there are no slots, "executing a trade" is a custom round trip:

1. Client sends `VillagerTradeC2S(villagerEntityId, offerIndex)`.
2. Server (`ModRegistry.executeTrade`) re-resolves the villager entity, re-validates the offer still exists and isn't out of stock, checks the player's inventory actually has the cost items (`ItemStack.isSameItemSameComponents` for matching, manual `removeItem` for taking them), then adds the result and calls `offer.increaseUses()`.
3. Nothing is trusted from the client beyond "which index" — the server is the only place that touches inventory contents.

This is the same shape the eventual LLM-driven trade system needs (a validated, directly-applied trade — see `llm-integration.md`), so building it this way now isn't wasted effort.

**Current status**: this path is wired up but currently unreachable — `VillagerInteraction.open` no longer sends `villager.getOffers()` to the client (vanilla's auto-generated trades are intentionally off; see `llm-integration.md` for why), so no trade cards exist yet for a player to click. It'll come back once negotiated offers exist (see the `propose_trade` action type planned in `llm-integration.md`).

## Villager naming

Vanilla villagers have no name by default (just a profession label). `VillagerNaming.ensureNamed` assigns a real name — deterministically picked from a fixed list, seeded by the villager's UUID — the first time a player opens a conversation with them, via vanilla's own `setCustomName`/`setCustomNameVisible` (so it also shows as a normal floating nametag, not something bespoke to draw ourselves). **The UUID, not the name, is the actual identity used everywhere** — `BrainStore`/`VillagerBrain` are keyed by UUID, never by name, so two villagers could in principle share a name without any memory/identity confusion.

## LLM integration (implemented, chat only — see llm-integration.md for what's still planned)

The mod bundles and manages its own `llama-server` (llama.cpp's HTTP server binary) — no external dependency on LM Studio, Ollama, or anything else the player might have running. Talking to the *user's* own separately-installed local-model server was considered and explicitly rejected: the mod needs to work for anyone who downloads it, not just on a machine that happens to have LM Studio configured a particular way.

- **`LlamaServerProcess`** (`src/main`, server-side only) is a singleton started on `ServerLifecycleEvents.SERVER_STARTING` and stopped on `SERVER_STOPPING`. It resolves the binary (`NativeAssets`) and model (`ModelAssets`) — downloading either on first run if missing — binds an ephemeral local port, launches the subprocess, drains its stdout on a daemon thread (mandatory: an undrained pipe deadlocks the child process), and polls `/health` until ready. State is one of `STOPPED` / `STARTING` / `READY` / `DEGRADED`; every chat request short-circuits to a graceful in-character "not listening right now" (or "still gathering their thoughts," while `STARTING`) message unless state is `READY`.
- **Binary resolution** (`NativeAssets`): downloads the matching Vulkan-accelerated llama.cpp release archive for the current OS (Windows/Linux: Vulkan build for broad GPU-vendor compatibility without picking CUDA vs ROCm; macOS uses Metal automatically, no separate backend flag needed) from `github.com/ggml-org/llama.cpp/releases`, extracts it (a hand-rolled pure-Java `ustar` tar reader for the Linux/macOS `.tar.gz` archives — verified against the real archive layout, see `decisions-log.md`), and locates the `llama-server`/`llama-server.exe` executable by walking the extracted tree rather than hardcoding a path (the archives' top-level directory name embeds the llama.cpp build number, which changes).
- **Model resolution** (`ModelAssets`): the target filename is derived from the configured download URL itself, so pointing the config at a different model downloads a new file alongside any previous ones rather than colliding with a fixed name — this is also what lets the settings screen's model manager list multiple installed models side by side. Progress is published to `ModelDownloadState` (bytes downloaded / total, status) for the settings screen to poll and render as a progress bar.
- **`LlamaClient`**: async (`java.net.http.HttpClient`, never blocks the server thread) calls to `POST /v1/chat/completions` (llama-server's OpenAI-compatible API). Parses `usage.completion_tokens` plus measured wall-clock time into a tokens/sec figure, fed into `LlamaServerProcess`'s rolling average for the settings screen's live stats display.
- **`PromptBuilder`**: a fixed system prompt (identical across all villagers, for llama-server's KV-cache prefix reuse) + a per-villager persona line (profession + a personality trait picked deterministically from the villager's UUID) + recent history from `VillagerBrain`. **Not yet wired to the SQLite memory layer** (`docs/memory-database.md`) — that's the next concrete step, along with the structured `say` + `actions` response schema and its guardrail validation described in `llm-integration.md`.
- **Chat network round trip**: `VillagerChatC2S(villagerEntityId, message)` → `ModRegistry.handleChat` resolves the villager + brain, calls `LlamaClient`, replies with `VillagerChatS2C(villagerEntityId, reply)`. The client (`VillagerTalkScreen`) shows a `"..."` placeholder immediately and swaps it for the real reply on arrival — never blocks input while waiting.

## Settings: `ModSettingsScreen` + ModMenu

An in-game settings screen (owo-lib `BaseOwoScreen`, not container-based since it isn't tied to a menu) covers what `docs/llm-integration.md` originally deferred to "a future in-game settings menu": live LLM status and tokens/sec, GPU layers/context size/threads/max-tokens/temperature, and an LM-Studio-style **Hugging Face model browser** (`HuggingFaceSearch`, client-only — searches `huggingface.co/api/models?filter=gguf`, lists a selected repo's `.gguf` files, and starts a download on click) plus a **local model manager** (lists everything already downloaded under `bettervillagers/models/`, with "Use"/"Delete" per file). Reachable from ModMenu's mods list when ModMenu is installed (`BetterVillagersModMenu implements ModMenuApi`); the mod is fully configurable by hand-editing `bettervillagers.json` when it isn't.

**Known layout gotcha**: don't wrap settings content in an intermediate fixed-pixel-size `FlowLayout` panel (`Sizing.fixed(420), Sizing.fixed(360)`) nested inside a centered root — in practice the fixed sizing wasn't respected and content spanned the full window instead. The fix (and the pattern owo's own reference `TestConfigScreen` uses) is simpler: put content directly as children of the root `FlowLayout`, sized as **percentages of the screen** (e.g. a scroll container at `Sizing.fill(80), Sizing.fill(65)`), with no intermediate fixed-size wrapper.

## GUI: owo-lib, not hand-rolled rendering

Three iterations were tried before landing here:

1. **Vanilla-textured GUI** (the original ask) — reusing `textures/gui/container/villager.png` and vanilla sprites. Abandoned once it became clear Minecraft 26.2's actual vanilla UI is itself a flat/modern redesign, not the classic brown-pixel GUI, and the user's direction shifted accordingly.
2. **Hand-rolled custom GUI** — raw `GuiGraphicsExtractor.fill()`/`text()` calls inside `extractBackground`/`extractContents` overrides. Functionally worked but kept hitting the same class of bug: keyboard focus wasn't correctly registered with the screen's input router (`EditBox.setFocused(true)` sets the *widget's own* flag, but `Screen`/`ContainerEventHandler` routes input to whatever `setFocused(GuiEventListener)` — a different, container-level method — was last given), so typing would leak through to vanilla hotkeys (`e` toggling inventory mid-sentence). Rebuilding this focus/layout system by hand every time a design changed was the actual complaint behind "the UI looks like shit."
3. **owo-lib** (current) — a mature declarative UI library already published for game version `26.2`. Gets correct focus routing, flex-style layout (`Sizing`/`Insets`/`FlowLayout`), and even genuine GPU-blurred surfaces (`Surface.blur(quality, size)`) for free. Screen extends `BaseOwoContainerScreen<FlowLayout, VillagerTalkMenu>`; the whole UI is a declarative component tree built in `build(FlowLayout root)`.

The current visual direction (after several rounds of feedback) is a **Skyrim-style dialogue bar**: bottom-anchored, no boxy modal window, no background gradient, villager name + a short fading scrollback + a borderless input line. Still a blocking `Screen` (cursor unlocked, movement stops) with free-text typing — not a numbered-dialogue-option system.

**Known gotcha**: `AbstractContainerScreen`'s own `extractLabels` (vanilla's title + "Inventory" label pass) still fires underneath owo's rendering unless explicitly overridden to a no-op — it was bleeding through as ghost text behind the owo panel until `VillagerTalkScreen` overrode it.

**Dependency setup**: `io.wispforest:owo-lib` needs both `https://maven.wispforest.io` (the library itself) and `https://jitpack.io` (its `kdl4j` transitive dependency) as repositories, `implementation` **and** `include` (bundled into our own jar — see "Zero-dependency goal" above).

**Chat transcript**: persists across closing/reopening the same villager for the client session (`ClientChatHistoryStore`, keyed by entity id — was a fresh empty list every time the screen re-opened before this), lives in a bounded (`Sizing.fixed(150)`) `verticalScroll` container rather than growing unbounded or truncating to a fixed line count (fixes both "cut off on a small window" and "can't scroll back"), auto-scrolls to the bottom on a new message (`ScrollContainer.scrollTo(1.0)`), and the dialogue bar's backdrop uses `Surface.blur(4, 8).and(Surface.flat(...))` for a genuine (not faked) blur behind the text.
