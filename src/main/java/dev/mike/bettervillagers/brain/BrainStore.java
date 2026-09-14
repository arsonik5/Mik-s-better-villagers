package dev.mike.bettervillagers.brain;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BrainStore {
    private static final Map<UUID, VillagerBrain> BRAINS = new ConcurrentHashMap<>();

    private BrainStore() {
    }

    public static VillagerBrain get(UUID villagerUuid) {
        return BRAINS.computeIfAbsent(villagerUuid, VillagerBrain::new);
    }
}
