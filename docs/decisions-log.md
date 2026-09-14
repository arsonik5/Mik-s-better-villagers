# Decisions log

Things discovered the hard way this session, kept here so nobody re-derives (or re-breaks) them.

## Orphaned `llama-server.exe` processes pile up across restarts

A forceful kill of the Java process (crash, `taskkill /F`, `Stop-Process -Force` — anything that doesn't let the JVM run its normal shutdown path) never reaches `LlamaServerProcess.stop()`, so the child `llama-server` subprocess is orphaned and keeps running indefinitely, holding GPU memory. This isn't a hypothetical: repeated dev-test relaunches during this session left **5 orphaned `llama-server.exe` processes** running simultaneously (confirmed via `tasklist`), each holding 1-3.5GB, and was the actual root cause behind "LLM stuck on STARTING" and "same download shows every launch" bug reports — the *real* server the mod just launched was starved of GPU memory by its own zombie predecessors.

**Fix**: `LlamaServerProcess` now writes its child's PID to `<gameDir>/bettervillagers/llama-server.pid` on launch, and on the *next* launch checks that file first — if a process with that PID is still alive and its command line mentions `llama-server`, it's killed before a new one starts. A JVM shutdown hook is also registered as a backstop for graceful exits (window close, `Ctrl+C`), though note a `-Force`-style kill bypasses shutdown hooks entirely on Windows — the PID-file check on next launch is the mechanism that actually matters here.

**Verified end-to-end**, not just by reasoning about it: ran the mod headlessly via `./gradlew runServer` (no GUI clicking needed — `ServerLifecycleEvents.SERVER_STARTING` fires on dedicated-server boot same as singleplayer world load), confirmed `LlamaServerProcess` reached `READY` normally, force-killed the parent `java.exe`, confirmed via `tasklist` the `llama-server.exe` orphan survived, relaunched, and confirmed both the log line `"Found a stale llama-server (pid ...) from a previous session, stopping it"` and `tasklist` showing exactly one `llama-server.exe` afterward.

`./gradlew runServer` (needs `run/eula.txt` containing `eula=true`, gitignored — a real dev-testing convenience) is generally the fastest way to exercise server-side-only logic (anything gated on `ServerLifecycleEvents`) without touching the client GUI at all.

## Downloads must be resumable, not just retryable

`ModelAssets` originally opened its destination file with `TRUNCATE_EXISTING` unconditionally, so any interrupted download (closing the game, a crash, or — during dev testing — relaunching the client, which happens *constantly*) threw away all progress and restarted from byte 0 next time. For a 1-3GB model file this effectively means the download never finishes under normal dev-test cadence, and looks exactly like "it's stuck downloading" to a real player who just closes the game once mid-download.

**Fix**: check existing `.part` file size first; if non-zero, send `Range: bytes=<existing>-` and append instead of truncating; fall back to a full restart only if the server doesn't honor the range (no `206` response). **Verified** (not assumed) that Hugging Face's CDN honors `Range` requests through its redirect chain, and that `java.net.http.HttpClient` correctly forwards the `Range` header across `HttpClient.Redirect.ALWAYS` redirects — both confirmed with a real request against the actual model URL this mod downloads by default, getting back `206 Partial Content` with a correct `Content-Range` and exact expected body length.

## Minecraft moved to year-based version numbers

As of mid-2026, Mojang's launcher displays releases as `26.1`, `26.2`, etc. instead of `1.21.x`. The old `1.21.x` numbering still exists as an internal jar/mapping version for a while (e.g. `1.21.11` ≈ the same build as `26.1`), but the current Fabric tooling (Loom, fabric-api, the official `fabric-example-mod` template) has already switched to addressing Minecraft directly by the year-based number (`minecraft_version=26.2`), and that's what `gradle.properties` uses here. Don't "fix" this back to a `1.21.x` string — check `https://meta.fabricmc.net/v2/versions/game` or the live `FabricMC/fabric-example-mod` repo if a future Minecraft update means this needs revisiting.

## No more Yarn mappings, no more `modImplementation`

Current Fabric Loom for this Minecraft version compiles directly against **Mojang's official mappings** — there is no Yarn/intermediary remapping step at all. Concretely:

- `build.gradle` has **no** `mappings "net.fabricmc:yarn:..."` line. Adding one fails with `Could not find method mappings()` — the DSL method doesn't exist in this pipeline.
- `fabric-loader` and `fabric-api` are declared with plain `implementation`, not `modImplementation`.
- Third-party mod dependencies (owo-lib, ModMenu) *also* use plain `implementation`/`compileOnly` here — every `mod*`-prefixed configuration (`modImplementation`, `modCompileOnly`, ...) fails with `Could not find method ...()`, presumably because there's no intermediary namespace left to remap between. **`include` (plain, not `mod`-prefixed) still works fine** for jar-in-jar bundling, though — confirmed by `unzip -l` on the built jar showing `META-INF/jars/owo-lib-*.jar` after adding `include "io.wispforest:owo-lib:${version}"` alongside the `implementation` line. Don't assume `include` is broken just because `modImplementation` is.
- Java package names now generally match Mojang's own naming (`net.minecraft.world.entity.npc.villager.Villager`, `net.minecraft.world.inventory.MerchantMenu`, `net.minecraft.resources.Identifier`, etc.) rather than Yarn's community names. **Always verify against real decompiled sources** (`./gradlew genClientOnlySources`, read the `-sources.jar` under `.gradle/loom-cache/minecraftMaven/...`) before writing code against a remembered Yarn class name — several were wrong on the first pass this session (`net.minecraft.util.ActionResult` → `net.minecraft.world.InteractionResult`, `net.minecraft.util.Identifier` → `net.minecraft.resources.Identifier`, etc.).
- This machine has no JDK 21 installed, and this Minecraft version requires **Java 25** anyway (not 21) — set `JAVA_HOME` to a JDK 25 before running `gradlew`, and don't add a Foojay toolchain resolver expecting to provision 21.

## `MerchantMenu`'s slots can't be hidden or moved

`net.minecraft.world.inventory.Slot.x` / `.y` are `public final int` — set once in the constructor, no setter. Any menu that extends `MerchantMenu` (or otherwise adds slots at vanilla's hardcoded coordinates) will always render the player's 36-slot inventory grid and 3 payment/result slots at their fixed vanilla positions, regardless of what your own screen draws on top. There is no way to relocate or suppress them short of not creating them in the first place — hence `VillagerTalkMenu` extends `AbstractContainerMenu` directly with zero slots (see `architecture.md`).

Related: `MerchantMenu`'s constructors hardcode `MenuType.MERCHANT`. A subclass must override `getType()` to report its own registered type, or Fabric's `ExtendedMenuProvider` open path throws `[Fabric] Non-extended menu ... must not be opened with an ExtendedMenuProvider!`. Moot now that we don't extend `MerchantMenu`, but it cost a debugging cycle.

## `EditBox`/text-field keyboard focus

Setting a text widget's own `setFocused(true)` is **not** the same as registering it as the screen's actual input-routing target. `Screen` (via `ContainerEventHandler`) tracks focus separately through `setFocused(GuiEventListener)`/`getFocused()`; use `Screen.setInitialFocus(GuiEventListener)` (or, for a container screen, let the UI framework's own focus handler own this — see owo-lib's `FocusHandler`) rather than only flipping the widget's internal flag. Get this wrong and stray keystrokes (like `e`) fall through to vanilla hotkeys (opening the inventory) mid-conversation, because vanilla's own hotkey-checking code has no idea a text field is "really" focused.

## `AbstractContainerScreen.extractLabels` still fires under a custom UI

Even when a screen's visual content is entirely custom (owo-lib or hand-rolled), the base `AbstractContainerScreen` will still call its own `extractLabels` pass (drawing the vanilla title + "Inventory" label) unless the subclass overrides it to a no-op. It rendered as faint ghost text behind the owo panel until this was overridden.

## More Mojang-mapping renames worth knowing

Beyond the ones in the previous section, hit while building the LLM/config/naming layer:

- `net.minecraft.client.Minecraft` has **no `screen` field and no `setScreen(Screen)` method** anymore — the replacement is `setScreenAndShow(Screen)`. There's also no public getter for "the current screen" at all; if you need to reach a specific open screen from elsewhere (e.g. a network packet handler), track the instance yourself with a static field set in the screen's constructor and cleared in `removed()`, rather than querying `Minecraft` for it (see `VillagerTalkScreen.current()`).
- `ResourceKey<T>.location()` doesn't exist — it's `ResourceKey<T>.identifier()` now (matches the `Identifier` rename from `ResourceLocation` covered above).
- `VillagerData` is a Java record: the profession accessor is `profession()`, not `getProfession()` (returns `Holder<VillagerProfession>`; get a readable string via `.unwrapKey().map(k -> k.identifier().getPath())`).

## owo-lib: `onKeyPress`, not `keyPressed`, and `verticalScroll`'s `scrollTo`

Two owo-lib specifics that cost debugging cycles:

- When manually routing a key event to a focused `TextBoxComponent` (e.g. from a screen-level `keyPressed` override that needs to claim every key except Escape, to stop `e` leaking through to vanilla hotkeys — see the `EditBox` gotcha above, which applies here too even with owo), call **`component.onKeyPress(input)`**, not `component.keyPressed(input)`. `onKeyPress` is the real entry point declared on `UIComponent` itself — it both performs the widget's own editing behavior *and* fires the `keyPress()` `EventStream` that an `Enter`-to-submit `.keyPress().subscribe(...)` hook relies on. Calling `keyPressed(...)` directly handles the editing but silently skips notifying that event stream, so a manually-added Enter handler stops firing (while typing still visually works) — an easy thing to not notice until you specifically test submitting.
- `ScrollContainer<C>` has `scrollTo(UIComponent)` and `scrollTo(double progress)` (0.0–1.0) — **no** `scrollTo(component, boolean)` overload. To auto-scroll a transcript to the latest message, call `scrollContainer.scrollTo(1.0)` after rebuilding its content.

## A fixed-size nested panel doesn't get respected in an owo screen

Wrapping settings-screen content in an intermediate `FlowLayout` sized `Sizing.fixed(420), Sizing.fixed(360)`, itself a child of a centered-alignment root, did not actually constrain anything in practice — content rendered at full window width regardless, spilling past where the fixed box should have ended. owo's own reference example (`TestConfigScreen` in the library's testmod) never does this: it puts content directly as children of the root, sized as **percentages of the screen** (root already fills the screen via `OwoUIAdapter`), e.g. a scroll container at `Sizing.fill(80), Sizing.fill(65)`. Following that exact pattern fixed it. Root cause not fully diagnosed (a `Sizing.fixed` parent nested inside content-sized ancestors may not correctly clamp `Sizing.fill(100)` descendants in every configuration) — the takeaway is just: mirror owo's own proven screen-layout pattern rather than layering in an extra fixed-size wrapper `FlowLayout`.

## Model choice: went through three before landing on one

Started with `Qwen2.5-1.5B-Instruct` (small, fast to validate the pipeline end-to-end) → swapped to a locally-available `NVIDIA-Nemotron-3-Nano-4B` (copied straight from the user's existing LM Studio models folder, `~/.lmstudio/models/...`, rather than re-downloading) → that turned out too heavy for the available VRAM (a 6GB card already near capacity) and produced very slow first replies → landed on **`Qwen3.5-4B`** (`lmstudio-community/Qwen3.5-4B-GGUF`, `Q4_K_M`), a current-generation (2026) dense instruct model from Qwen's own team, picked over niche community "roleplay finetune" models in the same size class because general instruction-following/narrative-consistency quality at this size matters more than an NSFW-oriented finetune's specialization, and it's the credible, actively-maintained option. `ModelAssets` derives its on-disk filename from the download URL, so switching models via config (or the settings screen's model browser) never collides with a previously-downloaded file — old ones just sit alongside the new one until deleted via the model manager.

## Where to find real API signatures fast

When in doubt, don't guess from memory — three things helped a lot this session:

1. `./gradlew genClientOnlySources` / `genCommonSources` (Loom task) decompiles the exact Minecraft jar this project compiles against into `.gradle/loom-cache/minecraftMaven/net/minecraft/*-sources.jar`. Read the real vanilla source (e.g. `MerchantScreen.java`) before reimplementing something similar.
2. `javap -p <path-to-.class>` on a class extracted from a jar (`unzip -j` it out first — running `javap` directly against `-cp some.jar ClassName` intermittently fails with a spurious "class not found" for some jars in this environment; extracting the `.class` file and pointing `javap` straight at it always works).
3. For a third-party library (owo-lib) whose default branch may be ahead of its last tagged release, `gh api repos/<org>/<repo>/contents/<path>` reads the *actual current* source straight from GitHub — much more reliable than guessing from an older cached memory of the library's API.
