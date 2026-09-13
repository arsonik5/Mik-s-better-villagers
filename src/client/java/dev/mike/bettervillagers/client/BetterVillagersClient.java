package dev.mike.bettervillagers.client;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screens.MenuScreens;

import dev.mike.bettervillagers.client.gui.VillagerTalkScreen;
import dev.mike.bettervillagers.screen.VillagerTalkMenu;

public class BetterVillagersClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MenuScreens.register(VillagerTalkMenu.TYPE, VillagerTalkScreen::new);
    }
}
