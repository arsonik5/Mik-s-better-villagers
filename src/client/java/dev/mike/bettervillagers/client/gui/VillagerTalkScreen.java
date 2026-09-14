package dev.mike.bettervillagers.client.gui;

import java.util.ArrayList;
import java.util.List;

import io.wispforest.owo.ui.base.BaseOwoContainerScreen;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.ScrollContainer;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.core.UIComponent;
import io.wispforest.owo.ui.core.VerticalAlignment;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import dev.mike.bettervillagers.net.VillagerChatC2S;
import dev.mike.bettervillagers.screen.VillagerTalkMenu;

/**
 * A Skyrim-style dialogue bar anchored to the bottom of the screen instead
 * of a floating GUI window: villager name, a scrollable transcript over a
 * gently blurred backdrop, and a borderless input line. No vanilla trades
 * are shown here — trade offers only ever come from a negotiated
 * conversation (Phase 4), never villager.getOffers(). The transcript
 * persists across closing/reopening the same villager for the session (see
 * ClientChatHistoryStore).
 */
public class VillagerTalkScreen extends BaseOwoContainerScreen<FlowLayout, VillagerTalkMenu> {
    private static final int GLFW_KEY_ENTER = 257;
    private static final int GLFW_KEY_ESCAPE = 256;
    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int LINES_HEIGHT = 70;
    private static final float BAR_WIDTH_FRACTION = 0.42f;
    private static final int BAR_PADDING = 14;

    private static final int COLOR_NAME = 0xFFFFFFFF;
    private static final int COLOR_LINE_LATEST = 0xFFE0E0E0;
    private static final int COLOR_LINE_OLD = 0xFFA6A6AC;
    private static final int COLOR_PLAYER_LINE = 0xFFAAAAAA;
    private static final int COLOR_INPUT_UNDERLINE = 0xFF555555;
    private static final int COLOR_BACKDROP = 0x50000000;

    private static VillagerTalkScreen current;

    private final List<ClientChatHistoryStore.Entry> chatEntries;
    private FlowLayout lines;
    private ScrollContainer<FlowLayout> linesScroll;
    private FlowLayout dialogueBar;
    private TextBoxComponent chatInput;
    private int pendingReplyIndex = -1;

    public VillagerTalkScreen(VillagerTalkMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.chatEntries = ClientChatHistoryStore.get(menu.getVillagerEntityId());
        current = this;
    }

    public static VillagerTalkScreen current() {
        return current;
    }

    @Override
    public void removed() {
        super.removed();
        if (current == this) {
            current = null;
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // Suppress vanilla's title/"Inventory" label pass — we draw our own.
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        // owo's own routing only intercepts keys the focused widget explicitly
        // claims (backspace, arrows, ...) and falls through to vanilla's
        // hotbar/close-inventory handling otherwise, which is how a stray "e"
        // closed the screen. While the chat box is actually focused, claim
        // everything ourselves except Escape.
        if (this.uiAdapter != null && this.uiAdapter.rootComponent.focusHandler().focused() == this.chatInput) {
            if (input.key() == GLFW_KEY_ESCAPE) {
                return super.keyPressed(input);
            }
            // onKeyPress (not keyPressed!) is the real entry point — it both
            // handles the widget's own editing AND fires the keyPress()
            // event stream our Enter-to-submit subscription relies on.
            this.chatInput.onKeyPress(input);
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        root.surface(Surface.BLANK);
        root.horizontalAlignment(HorizontalAlignment.CENTER);
        root.verticalAlignment(VerticalAlignment.BOTTOM);

        this.dialogueBar = UIContainers.verticalFlow(Sizing.fill((int) (BAR_WIDTH_FRACTION * 100)), Sizing.content());
        this.dialogueBar.surface(Surface.blur(2, 4).and(Surface.flat(COLOR_BACKDROP)));
        this.dialogueBar.padding(Insets.of(BAR_PADDING));
        this.dialogueBar.gap(2);

        this.dialogueBar.child(UIComponents.label(this.title).color(Color.ofRgb(COLOR_NAME)));

        this.lines = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        this.lines.gap(2);
        this.linesScroll = UIContainers.verticalScroll(Sizing.fill(100), Sizing.fixed(LINES_HEIGHT), this.lines);
        this.dialogueBar.child(this.linesScroll);
        rebuildLines();

        this.chatInput = UIComponents.textBox(Sizing.fill(100));
        this.chatInput.setBordered(false);
        this.chatInput.setMaxLength(MAX_MESSAGE_LENGTH);
        this.chatInput.keyPress().subscribe(input -> {
            if (input.key() == GLFW_KEY_ENTER) {
                submitChatMessage();
                return true;
            }
            return false;
        });

        FlowLayout inputRow = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        inputRow.margins(Insets.top(8));
        inputRow.child(this.chatInput);
        inputRow.child(UIComponents.box(Sizing.fill(100), Sizing.fixed(2))
                .color(Color.ofArgb(COLOR_INPUT_UNDERLINE)));
        this.dialogueBar.child(inputRow);

        this.dialogueBar.margins(Insets.bottom(24));
        root.child(this.dialogueBar);

        // Auto-focus the chat box so typing works immediately without
        // clicking into it first. owo tracks focus through its own
        // FocusHandler, not vanilla's Screen-level focus.
        root.focusHandler().focus(this.chatInput, UIComponent.FocusSource.KEYBOARD_CYCLE);
    }

    private void submitChatMessage() {
        String message = this.chatInput.getValue().trim();
        if (message.isEmpty()) {
            return;
        }
        this.chatEntries.add(new ClientChatHistoryStore.Entry(true, message));
        this.chatEntries.add(new ClientChatHistoryStore.Entry(false, "..."));
        this.pendingReplyIndex = this.chatEntries.size() - 1;
        this.chatInput.text("");
        rebuildLines();

        ClientPlayNetworking.send(new VillagerChatC2S(this.menu.getVillagerEntityId(), message));
    }

    /** Called by the client network handler when the server's reply arrives. */
    public void onReply(String reply) {
        if (this.pendingReplyIndex >= 0 && this.pendingReplyIndex < this.chatEntries.size()) {
            this.chatEntries.set(this.pendingReplyIndex, new ClientChatHistoryStore.Entry(false, reply));
            this.pendingReplyIndex = -1;
            rebuildLines();
        }
    }

    private void rebuildLines() {
        this.lines.clearChildren();
        // Wrap to the bar's actual current width, not a guessed constant —
        // this.width changes with the window/GUI scale, the bar is a fixed
        // fraction of it.
        int wrapWidth = Math.max(60, (int) (this.width * BAR_WIDTH_FRACTION) - BAR_PADDING * 2 - 4);

        List<ClientChatHistoryStore.Entry> snapshot = new ArrayList<>(this.chatEntries);
        for (int i = 0; i < snapshot.size(); i++) {
            ClientChatHistoryStore.Entry entry = snapshot.get(i);
            boolean latest = i == snapshot.size() - 1;
            int color = entry.fromPlayer() ? COLOR_PLAYER_LINE : (latest ? COLOR_LINE_LATEST : COLOR_LINE_OLD);
            String prefix = entry.fromPlayer() ? "You: " : "";
            this.lines.child(UIComponents.label(Component.literal(prefix + entry.text()))
                    .color(Color.ofRgb(color))
                    .maxWidth(wrapWidth));
        }
        if (this.linesScroll != null) {
            this.linesScroll.scrollTo(1.0);
        }
    }
}
