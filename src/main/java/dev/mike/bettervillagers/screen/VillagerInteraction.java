package dev.mike.bettervillagers.screen;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

public final class VillagerInteraction {
    private VillagerInteraction() {
    }

    public static void open(ServerPlayer player, Villager villager) {
        villager.setTradingPlayer(player);
        player.openMenu(new ExtendedMenuProvider<Integer>() {
            @Override
            public Component getDisplayName() {
                return villager.getDisplayName();
            }

            @Override
            public AbstractContainerMenu createMenu(int syncId, Inventory inventory, Player player) {
                return new VillagerTalkMenu(syncId, inventory, villager);
            }

            @Override
            public Integer getScreenOpeningData(ServerPlayer serverPlayer) {
                return villager.getId();
            }
        });
    }
}
