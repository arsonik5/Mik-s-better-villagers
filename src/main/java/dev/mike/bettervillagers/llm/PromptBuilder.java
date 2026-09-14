package dev.mike.bettervillagers.llm;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.entity.npc.villager.Villager;

import dev.mike.bettervillagers.brain.VillagerBrain;

/**
 * Builds the message list sent to the model: a fixed system prompt (shared
 * across all villagers, so llama-server's KV prefix cache actually reuses
 * work turn-to-turn), a per-villager persona, then recent history and the
 * new message. Memory injection (SQLite-backed) is planned but not wired in
 * yet — see docs/memory-database.md.
 */
public final class PromptBuilder {
    private static final String SYSTEM_PROMPT = """
            You are a villager living in a Minecraft world, speaking directly to a player. \
            Reply in 1-2 short, in-character sentences. Never use markdown or lists. \
            Never mention being an AI, a language model, or a game. \
            Never invent items that don't exist in Minecraft.""";

    private static final String[] TRAITS = {
            "cheerful and talkative", "gruff but fair", "nervous and easily startled",
            "proud of their work", "quietly curious about the player", "a little forgetful",
            "warm and welcoming", "blunt and to the point"
    };

    private PromptBuilder() {
    }

    public static List<LlamaClient.Message> build(Villager villager, VillagerBrain brain, String newMessage) {
        List<LlamaClient.Message> messages = new ArrayList<>();
        messages.add(new LlamaClient.Message("system", SYSTEM_PROMPT));

        String profession = villager.getVillagerData().profession().unwrapKey()
                .map(key -> key.identifier().getPath())
                .orElse("villager");
        String trait = TRAITS[(int) Math.floorMod(brain.personaSeed(), TRAITS.length)];
        String name = villager.getDisplayName().getString();
        messages.add(new LlamaClient.Message("system",
                "You are " + name + ", a " + profession + ". Personality: " + trait + "."));

        for (VillagerBrain.Turn turn : brain.history()) {
            messages.add(new LlamaClient.Message(turn.fromPlayer() ? "user" : "assistant", turn.text()));
        }

        messages.add(new LlamaClient.Message("user", newMessage));
        return messages;
    }
}
