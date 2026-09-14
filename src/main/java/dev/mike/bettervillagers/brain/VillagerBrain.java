package dev.mike.bettervillagers.brain;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * A villager's short-term conversation memory. In-memory only for now — the
 * SQLite-backed persistent memory layer (world/village/villager scopes) is
 * planned separately, see docs/memory-database.md.
 */
public final class VillagerBrain {
    private static final int MAX_HISTORY = 6;

    private final UUID uuid;
    private final long personaSeed;
    private final Deque<Turn> history = new ArrayDeque<>();

    public record Turn(boolean fromPlayer, String text) {
    }

    public VillagerBrain(UUID uuid) {
        this.uuid = uuid;
        this.personaSeed = uuid.getLeastSignificantBits();
    }

    public UUID uuid() {
        return uuid;
    }

    public long personaSeed() {
        return personaSeed;
    }

    public synchronized void addTurn(boolean fromPlayer, String text) {
        history.addLast(new Turn(fromPlayer, text));
        while (history.size() > MAX_HISTORY) {
            history.removeFirst();
        }
    }

    public synchronized List<Turn> history() {
        return List.copyOf(history);
    }
}
