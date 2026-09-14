package dev.mike.bettervillagers.client.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps each villager's chat transcript alive across closing and reopening
 * the dialogue screen, for the lifetime of the client session. Keyed by
 * entity id (stable for a given world session).
 */
final class ClientChatHistoryStore {
    record Entry(boolean fromPlayer, String text) {
    }

    private static final Map<Integer, List<Entry>> HISTORY = new HashMap<>();

    private ClientChatHistoryStore() {
    }

    static List<Entry> get(int villagerEntityId) {
        return HISTORY.computeIfAbsent(villagerEntityId, id -> new ArrayList<>());
    }
}
