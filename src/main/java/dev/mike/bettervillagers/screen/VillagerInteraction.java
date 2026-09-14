package dev.mike.bettervillagers.screen;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

import dev.mike.bettervillagers.util.VillagerNaming;

public final class VillagerInteraction {
    private VillagerInteraction() {
    }

    public static void open(ServerPlayer player, Villager villager) {
        VillagerNaming.ensureNamed(villager);
        villager.setTradingPlayer(player);
        int villagerEntityId = villager.getId();

        // Deliberately never sends villager.getOffers() here: vanilla's
        // auto-generated trades are off. Real offers only ever come from a
        // negotiated conversation (see the trade-generation work in Phase 4),
        // pushed via VillagerOffersS2C once that exists.
        player.openMenu(new ExtendedMenuProvider<Integer>() {
            @Override
            public Component getDisplayName() {
                return villager.getDisplayName();
            }

            @Override
            public AbstractContainerMenu createMenu(int syncId, Inventory inventory, Player player) {
                return new VillagerTalkMenu(syncId, inventory, villagerEntityId);
            }

            @Override
            public Integer getScreenOpeningData(ServerPlayer serverPlayer) {
                return villagerEntityId;
            }
        });
    }
}
