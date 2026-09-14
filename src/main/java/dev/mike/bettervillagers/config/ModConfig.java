package dev.mike.bettervillagers.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

import dev.mike.bettervillagers.BetterVillagers;

/**
 * Persisted mod settings, hand-edited for now (a ModMenu-based in-game
 * config screen for this — model picker, GPU layers, etc. — is planned but
 * not built yet).
 */
public final class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("bettervillagers.json");

    private static ModConfig instance;

    /** Empty = auto-resolve/download modelDownloadUrl if not already present under models/. */
    public String modelPath = "";
    /**
     * Which model to use. Change this (or modelPath, for a local file) to
     * switch models — a URL that isn't already downloaded gets fetched
     * automatically next launch. A future in-game settings menu will edit
     * this file directly rather than requiring hand-editing.
     */
    public String modelDownloadUrl =
            "https://huggingface.co/lmstudio-community/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf";
    /** Empty = auto-resolve/download the bundled llama-server binary. */
    public String llamaServerPath = "";
    /** 999 offloads everything to the GPU; set to 0 to force CPU-only. */
    public int gpuLayers = 999;
    public int contextSize = 4096;
    public int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
    public int maxTokens = 96;
    public float temperature = 0.8f;
    public int requestTimeoutSeconds = 30;

    public static synchronized ModConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static ModConfig load() {
        if (Files.exists(PATH)) {
            try {
                String json = Files.readString(PATH, StandardCharsets.UTF_8);
                ModConfig loaded = GSON.fromJson(json, ModConfig.class);
                if (loaded != null) {
                    return loaded;
                }
            } catch (IOException e) {
                BetterVillagers.LOGGER.warn("Could not read {}, using defaults", PATH, e);
            }
        }
        ModConfig fresh = new ModConfig();
        fresh.save();
        return fresh;
    }

    public void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            BetterVillagers.LOGGER.warn("Could not write {}", PATH, e);
        }
    }
}
