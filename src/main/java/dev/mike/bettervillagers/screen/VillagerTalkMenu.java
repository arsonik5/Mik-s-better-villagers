package dev.mike.bettervillagers.screen;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffers;

import dev.mike.bettervillagers.BetterVillagers;

/**
 * Deliberately slot-less: no player-inventory grid, no payment/result slots
 * inherited from vanilla's MerchantMenu. Trade execution is a direct
 * request/validate/apply round trip (see VillagerTradeC2S + ModRegistry)
 * instead of vanilla's drag-items-into-slots flow, which keeps the screen
 * free of vanilla container chrome for a fully custom UI and doubles as the
 * direct-apply model the eventual LLM-driven trade system needs anyway.
 */
public class VillagerTalkMenu extends AbstractContainerMenu {
    public static ExtendedMenuType<VillagerTalkMenu, Integer> TYPE;

    private final int villagerEntityId;
    private MerchantOffers offers = new MerchantOffers();

    public VillagerTalkMenu(int syncId, Inventory playerInventory, int villagerEntityId) {
        super(TYPE, syncId);
        this.villagerEntityId = villagerEntityId;
    }

    public int getVillagerEntityId() {
        return villagerEntityId;
    }

    public MerchantOffers getOffers() {
        return offers;
    }

    public void setOffers(MerchantOffers offers) {
        this.offers = offers;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isAlive();
    }

    public static void register() {
        TYPE = Registry.register(
                BuiltInRegistries.MENU,
                Identifier.fromNamespaceAndPath(BetterVillagers.MOD_ID, "villager_talk"),
                new ExtendedMenuType<>(
                        (syncId, inventory, entityId) -> new VillagerTalkMenu(syncId, inventory, entityId),
                        ByteBufCodecs.VAR_INT
                )
        );
    }
}
