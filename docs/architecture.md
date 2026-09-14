# Architecture

## Platform

- **Fabric**, Minecraft **26.2** (Mojang's year-based version; the jar/mappings/Loom coordinate is `26.2` directly — there is no separate "1.21.x" number for this release, see `decisions-log.md`).
- **Java 25** (required by this Minecraft version itself, not a project choice).
- Compiled directly against **Mojang's official mappings** — no Yarn, no remapping step. Third-party mod dependencies use plain `implementation`, not `modImplementation`/`include`, unless the dependency ships something that genuinely needs jar-in-jar bundling.
- Fabric Loom `1.17-SNAPSHOT` (tracks `1.17.20` internally as of this writing), Gradle `9.5.1` (via the wrapper — don't hand-install Gradle).
- UI built with **owo-lib** (`io.wispforest:owo-lib`), a declarative Fabric UI framework — not hand-rolled `fill()`/`text()` calls. See "GUI" below for why.

Run `./gradlew runClient` to launch a dev client; `./gradlew build` to compile. Set `JAVA_HOME` to a JDK 25 install before invoking either (this machine: `C:\Program Files\Microsoft\jdk-25.0.4.7-hotspot`).

## Module layout

```
src/main/java/dev/mike/bettervillagers/
  BetterVillagers.java        ModInitializer — registers the villager-interception hook
  ModRegistry.java             Registers menu type + network payloads; server-side trade execution
  screen/
    VillagerTalkMenu.java      Slot-less AbstractContainerMenu (see "Menu" below)
    VillagerInteraction.java   Opens the menu when a player right-clicks a villager
  net/
    VillagerTradeC2S.java      Client -> server: "execute this trade index"
    VillagerOffersS2C.java     Server -> client: current trade offers (unused until Phase 4 — see llm-integration.md)
src/client/java/dev/mike/bettervillagers/client/
  BetterVillagersClient.java   ClientModInitializer — registers the screen + offers receiver
  gui/VillagerTalkScreen.java  The owo-lib dialogue-bar screen
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

**Current status**: this path is wired up but currently unreachable — `VillagerInteraction.open` no longer sends `villager.getOffers()` to the client (vanilla's auto-generated trades are intentionally off; see `llm-integration.md` for why), so no trade cards exist yet for a player to click. It'll come back once negotiated offers exist.

## GUI: owo-lib, not hand-rolled rendering

Three iterations were tried before landing here:

1. **Vanilla-textured GUI** (the original ask) — reusing `textures/gui/container/villager.png` and vanilla sprites. Abandoned once it became clear Minecraft 26.2's actual vanilla UI is itself a flat/modern redesign, not the classic brown-pixel GUI, and the user's direction shifted accordingly.
2. **Hand-rolled custom GUI** — raw `GuiGraphicsExtractor.fill()`/`text()` calls inside `extractBackground`/`extractContents` overrides. Functionally worked but kept hitting the same class of bug: keyboard focus wasn't correctly registered with the screen's input router (`EditBox.setFocused(true)` sets the *widget's own* flag, but `Screen`/`ContainerEventHandler` routes input to whatever `setFocused(GuiEventListener)` — a different, container-level method — was last given), so typing would leak through to vanilla hotkeys (`e` toggling inventory mid-sentence). Rebuilding this focus/layout system by hand every time a design changed was the actual complaint behind "the UI looks like shit."
3. **owo-lib** (current) — a mature declarative UI library already published for game version `26.2`. Gets correct focus routing, flex-style layout (`Sizing`/`Insets`/`FlowLayout`), and even genuine GPU-blurred surfaces (`Surface.blur(quality, size)`) for free. Screen extends `BaseOwoContainerScreen<FlowLayout, VillagerTalkMenu>`; the whole UI is a declarative component tree built in `build(FlowLayout root)`.

The current visual direction (after several rounds of feedback) is a **Skyrim-style dialogue bar**: bottom-anchored, no boxy modal window, no background gradient, villager name + a short fading scrollback + a borderless input line. Still a blocking `Screen` (cursor unlocked, movement stops) with free-text typing — not a numbered-dialogue-option system.

**Known gotcha**: `AbstractContainerScreen`'s own `extractLabels` (vanilla's title + "Inventory" label pass) still fires underneath owo's rendering unless explicitly overridden to a no-op — it was bleeding through as ghost text behind the owo panel until `VillagerTalkScreen` overrode it.

**Dependency setup**: `io.wispforest:owo-lib` needs both `https://maven.wispforest.io` (the library itself) and `https://jitpack.io` (its `kdl4j` transitive dependency) as repositories, and plain `implementation` — not `modImplementation` (see `decisions-log.md` on why `modImplementation` doesn't exist in this project's Loom setup at all).
