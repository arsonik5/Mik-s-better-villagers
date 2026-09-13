package dev.mike.bettervillagers;

public final class ModRegistry {
    private ModRegistry() {
    }

    public static void register() {
        dev.mike.bettervillagers.screen.VillagerTalkMenu.register();
    }
}
