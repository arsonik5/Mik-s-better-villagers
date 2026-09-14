package dev.mike.bettervillagers.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.MenuScreens;

import dev.mike.bettervillagers.client.gui.VillagerTalkScreen;
import dev.mike.bettervillagers.net.VillagerOffersS2C;
import dev.mike.bettervillagers.screen.VillagerTalkMenu;

public class BetterVillagersClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MenuScreens.register(VillagerTalkMenu.TYPE, VillagerTalkScreen::new);

        // Kept wired up for Phase 4 (negotiated trade offers pushed to the
        // client); the screen itself doesn't display anything from this yet.
        ClientPlayNetworking.registerGlobalReceiver(VillagerOffersS2C.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    if (context.player().containerMenu instanceof VillagerTalkMenu menu
                            && menu.getVillagerEntityId() == payload.villagerEntityId()) {
                        menu.setOffers(payload.offers());
                    }
                }));
    }
}
