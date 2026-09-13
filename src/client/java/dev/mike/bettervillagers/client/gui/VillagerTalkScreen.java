package dev.mike.bettervillagers.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import dev.mike.bettervillagers.screen.VillagerTalkMenu;

/**
 * A single merged vanilla-styled window: chat feed (with trade offers shown
 * as inline cards) over an input box, on the real vanilla villager trade
 * panel background. Phase 2: chat is a local echo only, trades still run
 * through the inherited vanilla MerchantMenu execution path.
 */
public class VillagerTalkScreen extends AbstractContainerScreen<VillagerTalkMenu> {
    private static final Identifier VILLAGER_LOCATION = Identifier.withDefaultNamespace("textures/gui/container/villager.png");
    private static final Identifier TRADE_ARROW_SPRITE = Identifier.withDefaultNamespace("container/villager/trade_arrow");
    private static final Identifier TRADE_ARROW_OUT_OF_STOCK_SPRITE = Identifier.withDefaultNamespace("container/villager/trade_arrow_out_of_stock");
    private static final int GLFW_KEY_ENTER = 257;
    private static final int GLFW_KEY_ESCAPE = 256;
    private static final int MAX_CHAT_LINES = 100;
    private static final int MAX_VISIBLE_TRADE_CARDS = 4;
    private static final int TRADE_CARD_HEIGHT = 18;
    private static final int INPUT_HEIGHT = 16;

    private final List<String> chatLines = new ArrayList<>();
    private final List<int[]> tradeCardHitboxes = new ArrayList<>(); // {top, bottom, offerIndex}
    private EditBox chatInput;

    public VillagerTalkScreen(VillagerTalkMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 276, 166);
        this.chatLines.add("(the villager hasn't said anything yet)");
    }

    @Override
    protected void init() {
        super.init();
        int xo = (this.width - this.imageWidth) / 2;
        int yo = (this.height - this.imageHeight) / 2;
        int inputY = yo + this.imageHeight - 8 - INPUT_HEIGHT;

        this.chatInput = this.addRenderableWidget(new EditBox(this.font, xo + 8, inputY, this.imageWidth - 16, INPUT_HEIGHT,
                Component.translatable("bettervillagers.gui.chat.placeholder")));
        this.chatInput.setMaxLength(160);
        this.chatInput.setResponder(text -> {
        });

        this.setInitialFocus(this.chatInput);
    }

    private void submitChatMessage() {
        String message = this.chatInput.getValue().trim();
        if (!message.isEmpty()) {
            this.chatLines.add("You: " + message);
            // Phase 3 will replace this with a real request to the local LLM.
            this.chatLines.add("Villager: (...)");
            while (this.chatLines.size() > MAX_CHAT_LINES) {
                this.chatLines.remove(0);
            }
            this.chatInput.setValue("");
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (this.chatInput.isFocused()) {
            if (event.key() == GLFW_KEY_ENTER) {
                submitChatMessage();
                return true;
            }
            if (this.chatInput.keyPressed(event)) {
                return true;
            }
            if (event.key() == GLFW_KEY_ESCAPE) {
                return super.keyPressed(event);
            }
            // Swallow every other key (hotbar numbers, "e", etc.) while chatting
            // so vanilla hotkeys never fire out from under the text field.
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (int[] hitbox : this.tradeCardHitboxes) {
            if (event.y() >= hitbox[0] && event.y() < hitbox[1]) {
                int offerIndex = hitbox[2];
                this.menu.setSelectionHint(offerIndex);
                this.menu.tryMoveItems(offerIndex);
                this.minecraft.getConnection().send(new ServerboundSelectTradePacket(offerIndex));
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        graphics.text(this.font, this.title, 49 + this.imageWidth / 2 - this.font.width(this.title) / 2, 6, -12566464, false);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        int xo = (this.width - this.imageWidth) / 2;
        int yo = (this.height - this.imageHeight) / 2;
        graphics.blit(RenderPipelines.GUI_TEXTURED, VILLAGER_LOCATION, xo, yo, 0.0F, 0.0F, this.imageWidth, this.imageHeight, 512, 256);
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractContents(graphics, mouseX, mouseY, a);

        int xo = (this.width - this.imageWidth) / 2;
        int yo = (this.height - this.imageHeight) / 2;
        int panelLeft = xo + 8;
        int panelWidth = this.imageWidth - 16;
        int inputY = yo + this.imageHeight - 8 - INPUT_HEIGHT;
        int contentBottom = inputY - 4;
        int contentTop = yo + 20;

        MerchantOffers offers = this.menu.getOffers();
        int visibleTrades = Math.min(MAX_VISIBLE_TRADE_CARDS, offers.size());
        int tradesHeight = visibleTrades * TRADE_CARD_HEIGHT;
        int tradesTop = contentBottom - tradesHeight;

        this.tradeCardHitboxes.clear();
        int cardY = tradesTop;
        for (int i = 0; i < visibleTrades; i++) {
            extractTradeCard(graphics, offers.get(i), i, panelLeft, cardY, panelWidth, mouseX, mouseY);
            this.tradeCardHitboxes.add(new int[]{cardY, cardY + TRADE_CARD_HEIGHT, i});
            cardY += TRADE_CARD_HEIGHT;
        }

        extractChatFeed(graphics, panelLeft, contentTop, panelWidth, tradesTop);
    }

    private void extractChatFeed(GuiGraphicsExtractor graphics, int left, int top, int width, int bottom) {
        List<FormattedCharSequence> wrapped = new ArrayList<>();
        for (String line : this.chatLines) {
            wrapped.addAll(this.font.split(FormattedText.of(line), width));
        }

        int lineHeight = this.font.lineHeight + 1;
        int maxLines = Math.max(0, (bottom - top) / lineHeight);
        int start = Math.max(0, wrapped.size() - maxLines);
        int y = bottom - (Math.min(maxLines, wrapped.size() - start)) * lineHeight;
        for (int i = start; i < wrapped.size(); i++) {
            graphics.text(this.font, wrapped.get(i), left, y, -12566464, false);
            y += lineHeight;
        }
    }

    private void extractTradeCard(GuiGraphicsExtractor graphics, MerchantOffer offer, int index, int left, int top,
                                   int width, int mouseX, int mouseY) {
        boolean hovered = mouseX >= left && mouseX < left + width && mouseY >= top && mouseY < top + TRADE_CARD_HEIGHT;
        int background = hovered ? 0x60FFFFFF : 0x30000000;
        graphics.fill(left, top, left + width, top + TRADE_CARD_HEIGHT, background);

        ItemStack costA = offer.getCostA();
        ItemStack costB = offer.getCostB();
        ItemStack result = offer.getResult();

        int itemY = top + 1;
        int x = left + 2;
        graphics.fakeItem(costA, x, itemY);
        graphics.itemDecorations(this.font, costA, x, itemY);
        x += 18;

        if (!costB.isEmpty()) {
            graphics.fakeItem(costB, x, itemY);
            graphics.itemDecorations(this.font, costB, x, itemY);
            x += 18;
        }

        Identifier arrowSprite = offer.isOutOfStock() ? TRADE_ARROW_OUT_OF_STOCK_SPRITE : TRADE_ARROW_SPRITE;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, arrowSprite, x + 2, top + 4, 10, 9);
        x += 16;

        graphics.fakeItem(result, x, itemY);
        graphics.itemDecorations(this.font, result, x, itemY);

        if (hovered) {
            if (mouseX < left + 18) {
                graphics.setTooltipForNextFrame(this.font, costA, mouseX, mouseY);
            } else if (!costB.isEmpty() && mouseX < left + 36) {
                graphics.setTooltipForNextFrame(this.font, costB, mouseX, mouseY);
            } else if (mouseX >= left + width - 18) {
                graphics.setTooltipForNextFrame(this.font, result, mouseX, mouseY);
            }
        }
    }
}
