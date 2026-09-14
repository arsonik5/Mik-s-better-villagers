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

import dev.mike.bettervillagers.brain.BrainStore;
import dev.mike.bettervillagers.brain.VillagerBrain;
import dev.mike.bettervillagers.config.ModConfig;
import dev.mike.bettervillagers.llm.LlamaClient;
import dev.mike.bettervillagers.llm.LlamaServerProcess;
import dev.mike.bettervillagers.llm.PromptBuilder;
import dev.mike.bettervillagers.net.VillagerChatC2S;
import dev.mike.bettervillagers.net.VillagerChatS2C;
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
        PayloadTypeRegistry.serverboundPlay().register(VillagerChatC2S.TYPE, VillagerChatC2S.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(VillagerChatS2C.TYPE, VillagerChatS2C.STREAM_CODEC);

        ServerPlayNetworking.registerGlobalReceiver(VillagerTradeC2S.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> executeTrade(player, payload.villagerEntityId(), payload.offerIndex()));
        });

        ServerPlayNetworking.registerGlobalReceiver(VillagerChatC2S.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> handleChat(player, payload.villagerEntityId(), payload.message()));
        });
    }

    private static void handleChat(ServerPlayer player, int villagerEntityId, String message) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (!(level.getEntity(villagerEntityId) instanceof Villager villager)) {
            return;
        }
        if (!(player.containerMenu instanceof VillagerTalkMenu menu) || menu.getVillagerEntityId() != villagerEntityId) {
            return;
        }

        LlamaServerProcess llm = LlamaServerProcess.instance();
        if (llm.state() != LlamaServerProcess.State.READY) {
            String name = villager.getDisplayName().getString();
            String status = llm.state() == LlamaServerProcess.State.STARTING
                    ? "(" + name + " is still gathering their thoughts — try again in a few seconds.)"
                    : "(" + name + " doesn't seem to be listening right now.)";
            ServerPlayNetworking.send(player, new VillagerChatS2C(villagerEntityId, status));
            return;
        }

        VillagerBrain brain = BrainStore.get(villager.getUUID());
        brain.addTurn(true, message);
        var prompt = PromptBuilder.build(villager, brain, message);

        ModConfig config = ModConfig.get();
        LlamaClient.chat(llm.port(), prompt, config.maxTokens, config.temperature, config.requestTimeoutSeconds)
                .whenComplete((result, error) -> level.getServer().execute(() -> {
                    String text;
                    if (error != null) {
                        text = "(...trails off, distracted by something.)";
                    } else {
                        text = result.text();
                        llm.recordTokensPerSecond(result.tokensPerSecond());
                    }
                    brain.addTurn(false, text);
                    ServerPlayNetworking.send(player, new VillagerChatS2C(villagerEntityId, text));
                }));
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
