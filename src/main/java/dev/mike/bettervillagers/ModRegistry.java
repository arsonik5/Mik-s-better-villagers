package dev.mike.bettervillagers;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import dev.mike.bettervillagers.net.VillagerOffersS2C;
import dev.mike.bettervillagers.net.VillagerTradeC2S;
import dev.mike.bettervillagers.screen.VillagerTalkMenu;

public final class ModRegistry {
    private ModRegistry() {
    }

    public static void register() {
        VillagerTalkMenu.register();

        PayloadTypeRegistry.serverboundPlay().register(VillagerTradeC2S.TYPE, VillagerTradeC2S.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(VillagerOffersS2C.TYPE, VillagerOffersS2C.STREAM_CODEC);

        ServerPlayNetworking.registerGlobalReceiver(VillagerTradeC2S.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> executeTrade(player, payload.villagerEntityId(), payload.offerIndex()));
        });
    }

    private static void executeTrade(ServerPlayer player, int villagerEntityId, int offerIndex) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        Entity entity = level.getEntity(villagerEntityId);
        if (!(entity instanceof Villager villager)) {
            return;
        }
        if (!(player.containerMenu instanceof VillagerTalkMenu menu) || menu.getVillagerEntityId() != villagerEntityId) {
            return;
        }

        MerchantOffers offers = villager.getOffers();
        if (offerIndex < 0 || offerIndex >= offers.size()) {
            return;
        }
        MerchantOffer offer = offers.get(offerIndex);
        if (offer.isOutOfStock()) {
            return;
        }

        ItemStack costA = offer.getCostA();
        ItemStack costB = offer.getCostB();
        if (!hasEnough(player.getInventory(), costA) || (!costB.isEmpty() && !hasEnough(player.getInventory(), costB))) {
            return;
        }

        takeItems(player.getInventory(), costA);
        if (!costB.isEmpty()) {
            takeItems(player.getInventory(), costB);
        }
        player.getInventory().add(offer.getResult().copy());
        offer.increaseUses();
        villager.notifyTrade(offer);
        villager.notifyTradeUpdated(offer.getResult());

        menu.setOffers(offers);
        ServerPlayNetworking.send(player, new VillagerOffersS2C(villagerEntityId, offers));
    }

    private static boolean hasEnough(Inventory inventory, ItemStack cost) {
        int needed = cost.getCount();
        for (int i = 0; i < inventory.getContainerSize() && needed > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, cost)) {
                needed -= stack.getCount();
            }
        }
        return needed <= 0;
    }

    private static void takeItems(Inventory inventory, ItemStack cost) {
        int remaining = cost.getCount();
        for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, cost)) {
                int take = Math.min(remaining, stack.getCount());
                inventory.removeItem(i, take);
                remaining -= take;
            }
        }
    }
}
