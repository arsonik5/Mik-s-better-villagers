package dev.mike.bettervillagers.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import dev.mike.bettervillagers.client.gui.ModSettingsScreen;

public class BetterVillagersModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ModSettingsScreen::new;
    }
}
