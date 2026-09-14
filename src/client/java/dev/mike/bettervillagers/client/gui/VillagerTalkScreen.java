package dev.mike.bettervillagers.client.gui;

import java.util.ArrayList;
import java.util.List;

import io.wispforest.owo.ui.base.BaseOwoContainerScreen;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
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
 * of a floating GUI window: villager name, a short scrollback of recent
 * lines fading toward the top, and a borderless input line. No vanilla
 * trades are shown here — trade offers only ever come from a negotiated
 * conversation (Phase 4), never villager.getOffers().
 */
public class VillagerTalkScreen extends BaseOwoContainerScreen<FlowLayout, VillagerTalkMenu> {
    private static final int GLFW_KEY_ENTER = 257;
    private static final int GLFW_KEY_ESCAPE = 256;
    private static final int MAX_VISIBLE_LINES = 5;
    private static final int MAX_MESSAGE_LENGTH = 2000;

    private static final int COLOR_NAME = 0xFFFFFFFF;
    private static final int COLOR_LINE_LATEST = 0xFFE0E0E0;
    private static final int COLOR_LINE_OLD = 0xFF808080;
    private static final int COLOR_PLAYER_LINE = 0xFFAAAAAA;
    private static final int COLOR_INPUT_UNDERLINE = 0xFF555555;

    private static VillagerTalkScreen current;

    private record ChatEntry(boolean fromPlayer, String text) {
    }

    private final List<ChatEntry> chatEntries = new ArrayList<>();
    private FlowLayout lines;
    private FlowLayout dialogueBar;
    private TextBoxComponent chatInput;
    private int pendingReplyIndex = -1;

    public VillagerTalkScreen(VillagerTalkMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
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

        this.dialogueBar = UIContainers.verticalFlow(Sizing.fill(42), Sizing.content());
        this.dialogueBar.surface(Surface.BLANK);
        this.dialogueBar.padding(Insets.of(14));
        this.dialogueBar.gap(2);

        this.dialogueBar.child(UIComponents.label(this.title).color(Color.ofRgb(COLOR_NAME)));

        this.lines = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        this.lines.gap(2);
        this.dialogueBar.child(this.lines);
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
    }

    private void submitChatMessage() {
        String message = this.chatInput.getValue().trim();
        if (message.isEmpty()) {
            return;
        }
        this.chatEntries.add(new ChatEntry(true, message));
        this.chatEntries.add(new ChatEntry(false, "..."));
        this.pendingReplyIndex = this.chatEntries.size() - 1;
        this.chatInput.text("");
        rebuildLines();

        ClientPlayNetworking.send(new VillagerChatC2S(this.menu.getVillagerEntityId(), message));
    }

    /** Called by the client network handler when the server's reply arrives. */
    public void onReply(String reply) {
        if (this.pendingReplyIndex >= 0 && this.pendingReplyIndex < this.chatEntries.size()) {
            this.chatEntries.set(this.pendingReplyIndex, new ChatEntry(false, reply));
            this.pendingReplyIndex = -1;
            rebuildLines();
        }
    }

    private void rebuildLines() {
        this.lines.clearChildren();
        int start = Math.max(0, this.chatEntries.size() - MAX_VISIBLE_LINES);
        for (int i = start; i < this.chatEntries.size(); i++) {
            ChatEntry entry = this.chatEntries.get(i);
            boolean latest = i == this.chatEntries.size() - 1;
            int color = entry.fromPlayer() ? COLOR_PLAYER_LINE : (latest ? COLOR_LINE_LATEST : COLOR_LINE_OLD);
            String prefix = entry.fromPlayer() ? "You: " : "";
            this.lines.child(UIComponents.label(Component.literal(prefix + entry.text()))
                    .color(Color.ofRgb(color))
                    .maxWidth(400));
        }
    }
}
