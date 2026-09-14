package dev.mike.bettervillagers.util;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * Gives villagers a real, persistent name (shown as a normal vanilla
 * nametag) instead of the generic "Villager"/profession-only label. The
 * name is deterministic from the villager's UUID — which stays the actual
 * identity used everywhere else (memory, conversation history) regardless
 * of what name is displayed.
 */
public final class VillagerNaming {
    private static final String[] NAMES = {
            "Aldric", "Bramwell", "Cordelia", "Doran", "Elowen", "Fenwick", "Gideon", "Hilda",
            "Ivo", "Jarret", "Kessia", "Lior", "Merek", "Nessa", "Osric", "Perrin",
            "Quillan", "Rowena", "Soren", "Tamsin", "Ursin", "Vesper", "Wren", "Yolanda"
    };

    private VillagerNaming() {
    }

    /** Assigns a name the first time we see this villager; a no-op if it already has one. */
    public static void ensureNamed(Villager villager) {
        if (villager.hasCustomName()) {
            return;
        }
        long seed = villager.getUUID().getMostSignificantBits();
        String name = NAMES[(int) Math.floorMod(seed, NAMES.length)];
        villager.setCustomName(Component.literal(name));
        villager.setCustomNameVisible(true);
    }
}
