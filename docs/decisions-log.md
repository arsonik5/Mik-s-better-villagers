# Decisions log

Things discovered the hard way this session, kept here so nobody re-derives (or re-breaks) them.

## Minecraft moved to year-based version numbers

As of mid-2026, Mojang's launcher displays releases as `26.1`, `26.2`, etc. instead of `1.21.x`. The old `1.21.x` numbering still exists as an internal jar/mapping version for a while (e.g. `1.21.11` ≈ the same build as `26.1`), but the current Fabric tooling (Loom, fabric-api, the official `fabric-example-mod` template) has already switched to addressing Minecraft directly by the year-based number (`minecraft_version=26.2`), and that's what `gradle.properties` uses here. Don't "fix" this back to a `1.21.x` string — check `https://meta.fabricmc.net/v2/versions/game` or the live `FabricMC/fabric-example-mod` repo if a future Minecraft update means this needs revisiting.

## No more Yarn mappings, no more `modImplementation`

Current Fabric Loom for this Minecraft version compiles directly against **Mojang's official mappings** — there is no Yarn/intermediary remapping step at all. Concretely:

- `build.gradle` has **no** `mappings "net.fabricmc:yarn:..."` line. Adding one fails with `Could not find method mappings()` — the DSL method doesn't exist in this pipeline.
- `fabric-loader` and `fabric-api` are declared with plain `implementation`, not `modImplementation`.
- Third-party mod dependencies (owo-lib) *also* use plain `implementation` here — `modImplementation` isn't a valid configuration in this project at all (`Could not find method modImplementation()`), presumably because there's no intermediary namespace left to remap between.
- Java package names now generally match Mojang's own naming (`net.minecraft.world.entity.npc.villager.Villager`, `net.minecraft.world.inventory.MerchantMenu`, `net.minecraft.resources.Identifier`, etc.) rather than Yarn's community names. **Always verify against real decompiled sources** (`./gradlew genClientOnlySources`, read the `-sources.jar` under `.gradle/loom-cache/minecraftMaven/...`) before writing code against a remembered Yarn class name — several were wrong on the first pass this session (`net.minecraft.util.ActionResult` → `net.minecraft.world.InteractionResult`, `net.minecraft.util.Identifier` → `net.minecraft.resources.Identifier`, etc.).
- This machine has no JDK 21 installed, and this Minecraft version requires **Java 25** anyway (not 21) — set `JAVA_HOME` to a JDK 25 before running `gradlew`, and don't add a Foojay toolchain resolver expecting to provision 21.

## `MerchantMenu`'s slots can't be hidden or moved

`net.minecraft.world.inventory.Slot.x` / `.y` are `public final int` — set once in the constructor, no setter. Any menu that extends `MerchantMenu` (or otherwise adds slots at vanilla's hardcoded coordinates) will always render the player's 36-slot inventory grid and 3 payment/result slots at their fixed vanilla positions, regardless of what your own screen draws on top. There is no way to relocate or suppress them short of not creating them in the first place — hence `VillagerTalkMenu` extends `AbstractContainerMenu` directly with zero slots (see `architecture.md`).

Related: `MerchantMenu`'s constructors hardcode `MenuType.MERCHANT`. A subclass must override `getType()` to report its own registered type, or Fabric's `ExtendedMenuProvider` open path throws `[Fabric] Non-extended menu ... must not be opened with an ExtendedMenuProvider!`. Moot now that we don't extend `MerchantMenu`, but it cost a debugging cycle.

## `EditBox`/text-field keyboard focus

Setting a text widget's own `setFocused(true)` is **not** the same as registering it as the screen's actual input-routing target. `Screen` (via `ContainerEventHandler`) tracks focus separately through `setFocused(GuiEventListener)`/`getFocused()`; use `Screen.setInitialFocus(GuiEventListener)` (or, for a container screen, let the UI framework's own focus handler own this — see owo-lib's `FocusHandler`) rather than only flipping the widget's internal flag. Get this wrong and stray keystrokes (like `e`) fall through to vanilla hotkeys (opening the inventory) mid-conversation, because vanilla's own hotkey-checking code has no idea a text field is "really" focused.

## `AbstractContainerScreen.extractLabels` still fires under a custom UI

Even when a screen's visual content is entirely custom (owo-lib or hand-rolled), the base `AbstractContainerScreen` will still call its own `extractLabels` pass (drawing the vanilla title + "Inventory" label) unless the subclass overrides it to a no-op. It rendered as faint ghost text behind the owo panel until this was overridden.

## Where to find real API signatures fast

When in doubt, don't guess from memory — three things helped a lot this session:

1. `./gradlew genClientOnlySources` / `genCommonSources` (Loom task) decompiles the exact Minecraft jar this project compiles against into `.gradle/loom-cache/minecraftMaven/net/minecraft/*-sources.jar`. Read the real vanilla source (e.g. `MerchantScreen.java`) before reimplementing something similar.
2. `javap -p <path-to-.class>` on a class extracted from a jar (`unzip -j` it out first — running `javap` directly against `-cp some.jar ClassName` intermittently fails with a spurious "class not found" for some jars in this environment; extracting the `.class` file and pointing `javap` straight at it always works).
3. For a third-party library (owo-lib) whose default branch may be ahead of its last tagged release, `gh api repos/<org>/<repo>/contents/<path>` reads the *actual current* source straight from GitHub — much more reliable than guessing from an older cached memory of the library's API.
