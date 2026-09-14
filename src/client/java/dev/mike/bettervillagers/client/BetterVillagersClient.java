package dev.mike.bettervillagers.client;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.MenuScreens;

import dev.mike.bettervillagers.client.gui.ModSettingsScreen;
import dev.mike.bettervillagers.client.gui.VillagerTalkScreen;
import dev.mike.bettervillagers.net.VillagerChatS2C;
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

        ClientPlayNetworking.registerGlobalReceiver(VillagerChatS2C.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    VillagerTalkScreen screen = VillagerTalkScreen.current();
                    if (screen != null) {
                        screen.onReply(payload.reply());
                    }
                }));

        // Standalone settings hotkey — works with or without ModMenu installed.
        // Default: 'B' (unbound elsewhere by vanilla). Change under Options ->
        // Controls -> Key Binds -> Misc like any other keybind.
        KeyMapping settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.bettervillagers.settings",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_B,
                KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // No public "current screen" accessor exists on Minecraft in this
            // version to check against — harmless in practice, since an open
            // screen normally consumes keyboard input before global
            // keybindings see it at all.
            while (settingsKey.consumeClick()) {
                client.setScreenAndShow(new ModSettingsScreen(null));
            }
        });
    }
}
