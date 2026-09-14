package dev.mike.bettervillagers.client.gui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.core.VerticalAlignment;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.mike.bettervillagers.client.model.HuggingFaceSearch;
import dev.mike.bettervillagers.config.ModConfig;
import dev.mike.bettervillagers.llm.LlamaServerProcess;
import dev.mike.bettervillagers.llm.ModelAssets;
import dev.mike.bettervillagers.llm.ModelDownloadState;

/**
 * Settings for the local LLM: a Hugging Face model browser + local model
 * manager (LM-Studio-style), GPU/context settings, and live status.
 */
public class ModSettingsScreen extends BaseOwoScreen<FlowLayout> {
    private static final int COLOR_TEXT = 0xFFE0E0E0;
    private static final int COLOR_DIM = 0xFF9A9AA0;
    private static final int COLOR_READY = 0xFF6FCF6F;
    private static final int COLOR_STARTING = 0xFFE0C05A;
    private static final int COLOR_ERROR = 0xFFE05252;
    private static final int COLOR_ACCENT = 0xFF6FA8DC;

    private final Screen parent;

    private TextBoxComponent searchBox;
    private FlowLayout searchResults;
    private FlowLayout filesForSelectedRepo;
    private FlowLayout installedModels;
    private LabelComponent activeModelLabel;

    private TextBoxComponent gpuLayersBox;
    private TextBoxComponent contextSizeBox;
    private TextBoxComponent threadsBox;
    private TextBoxComponent maxTokensBox;
    private TextBoxComponent temperatureBox;

    private LabelComponent statusLabel;
    private LabelComponent statsLabel;
    private LabelComponent downloadLabel;

    public ModSettingsScreen(Screen parent) {
        super(Component.literal("Better Villagers Settings"));
        this.parent = parent;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        ModConfig config = ModConfig.get();

        root.surface(Surface.flat(0xD0101014));
        root.horizontalAlignment(HorizontalAlignment.CENTER);
        root.verticalAlignment(VerticalAlignment.CENTER);
        root.padding(Insets.of(20));

        root.child(UIComponents.label(Component.literal("Better Villagers — LLM Settings"))
                .color(Color.ofRgb(0xFFFFFF))
                .margins(Insets.bottom(6)));

        this.statusLabel = UIComponents.label(Component.literal("Status: ..."));
        root.child(this.statusLabel);
        this.statsLabel = label("");
        root.child(this.statsLabel);
        this.activeModelLabel = label("Active model: " + activeModelName(config));
        this.activeModelLabel.color(Color.ofRgb(COLOR_ACCENT));
        root.child(this.activeModelLabel.margins(Insets.bottom(10)));

        var scroll = UIContainers.verticalScroll(Sizing.fill(85), Sizing.fill(65), buildForm());
        scroll.padding(Insets.of(6));
        scroll.surface(Surface.flat(0x40000000).and(Surface.outline(0xFF303236)));
        root.child(scroll);

        FlowLayout buttons = UIContainers.horizontalFlow(Sizing.content(), Sizing.content());
        buttons.gap(8);
        buttons.margins(Insets.top(10));
        buttons.child(UIComponents.button(Component.literal("Save Settings"), b -> saveConfig()));
        buttons.child(UIComponents.button(Component.literal("Save & Restart LLM"), b -> {
            saveConfig();
            LlamaServerProcess.instance().restart();
        }));
        buttons.child(UIComponents.button(Component.literal("Done"), b ->
                this.minecraft.setScreenAndShow(this.parent)));
        root.child(buttons);

        refreshInstalledModels();
    }

    private FlowLayout buildForm() {
        ModConfig config = ModConfig.get();
        FlowLayout form = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        form.gap(10);

        // --- Installed models (first/most prominent — this is the actual manager) ---
        form.child(sectionTitle("Installed Models"));
        form.child(label("The model in use right now is marked ← active. Click Use to switch, Delete to remove.")
                .color(Color.ofRgb(COLOR_DIM)));
        this.installedModels = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        this.installedModels.gap(3);
        form.child(this.installedModels);

        // --- Hugging Face browser ---
        form.child(sectionTitle("Download a New Model from Hugging Face").margins(Insets.top(10)));
        FlowLayout searchRow = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        searchRow.gap(6);
        this.searchBox = UIComponents.textBox(Sizing.fill(75), "");
        this.searchBox.setMaxLength(200);
        searchRow.child(this.searchBox);
        searchRow.child(UIComponents.button(Component.literal("Search"), b -> runSearch()));
        form.child(searchRow);

        this.searchResults = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        this.searchResults.gap(3);
        this.searchResults.child(label("Type a model name above and press Search (e.g. \"gemma\", \"phi\", \"qwen\").")
                .color(Color.ofRgb(COLOR_DIM)));
        form.child(this.searchResults);

        this.filesForSelectedRepo = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        this.filesForSelectedRepo.gap(3);
        form.child(this.filesForSelectedRepo);

        this.downloadLabel = label("");
        form.child(this.downloadLabel);

        // --- Advanced settings ---
        form.child(sectionTitle("Advanced").margins(Insets.top(10)));
        form.child(row("GPU layers (999 = full offload, 0 = CPU-only)",
                this.gpuLayersBox = UIComponents.textBox(Sizing.fixed(80), String.valueOf(config.gpuLayers))));
        form.child(row("Context size",
                this.contextSizeBox = UIComponents.textBox(Sizing.fixed(80), String.valueOf(config.contextSize))));
        form.child(row("CPU threads",
                this.threadsBox = UIComponents.textBox(Sizing.fixed(80), String.valueOf(config.threads))));
        form.child(row("Max reply tokens",
                this.maxTokensBox = UIComponents.textBox(Sizing.fixed(80), String.valueOf(config.maxTokens))));
        form.child(row("Temperature",
                this.temperatureBox = UIComponents.textBox(Sizing.fixed(80), String.valueOf(config.temperature))));

        return form;
    }

    private LabelComponent sectionTitle(String text) {
        return UIComponents.label(Component.literal(text)).color(Color.ofRgb(0xFFFFFF));
    }

    private FlowLayout row(String labelText, TextBoxComponent box) {
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.gap(8);
        row.verticalAlignment(VerticalAlignment.CENTER);
        row.child(label(labelText).horizontalSizing(Sizing.fill(70)));
        row.child(box);
        return row;
    }

    private LabelComponent label(String text) {
        return UIComponents.label(Component.literal(text)).color(Color.ofRgb(COLOR_TEXT));
    }

    // --- Hugging Face search ---

    private void runSearch() {
        String query = this.searchBox.getValue().trim();
        if (query.isEmpty()) {
            return;
        }
        this.searchResults.clearChildren();
        this.searchResults.child(label("Searching..."));

        CompletableFuture.supplyAsync(() -> {
            try {
                return HuggingFaceSearch.search(query);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }).whenComplete((results, error) -> this.minecraft.execute(() -> {
                    if (error != null) {
                        logSearchError(error);
                        this.searchResults.clearChildren();
                        this.searchResults.child(label("Search failed: " + rootMessage(error)).color(Color.ofRgb(COLOR_ERROR)));
                    } else {
                        showSearchResults(results);
                    }
                }));
    }

    private void showSearchResults(List<HuggingFaceSearch.ModelResult> results) {
        this.searchResults.clearChildren();
        this.filesForSelectedRepo.clearChildren();
        if (results.isEmpty()) {
            this.searchResults.child(label("No results for that search."));
            return;
        }
        for (HuggingFaceSearch.ModelResult result : results) {
            ButtonComponent button = UIComponents.button(
                    Component.literal(result.repoId() + "  (" + formatCount(result.downloads()) + " downloads)"),
                    b -> selectRepo(result.repoId()));
            button.horizontalSizing(Sizing.fill(100));
            this.searchResults.child(button);
        }
    }

    private void selectRepo(String repoId) {
        this.filesForSelectedRepo.clearChildren();
        this.filesForSelectedRepo.child(label("Loading files for " + repoId + "..."));

        CompletableFuture.supplyAsync(() -> {
            try {
                return HuggingFaceSearch.listGgufFiles(repoId);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }).whenComplete((files, error) -> this.minecraft.execute(() -> {
                    if (error != null) {
                        logSearchError(error);
                        this.filesForSelectedRepo.clearChildren();
                        this.filesForSelectedRepo.child(label("Could not list files: " + rootMessage(error)).color(Color.ofRgb(COLOR_ERROR)));
                    } else {
                        showFiles(repoId, files);
                    }
                }));
    }

    private static void logSearchError(Throwable error) {
        dev.mike.bettervillagers.BetterVillagers.LOGGER.warn("Hugging Face request failed", error);
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        return msg == null ? cause.getClass().getSimpleName() : msg;
    }

    private void showFiles(String repoId, List<HuggingFaceSearch.ModelFile> files) {
        this.filesForSelectedRepo.clearChildren();
        if (files.isEmpty()) {
            this.filesForSelectedRepo.child(label("No .gguf files found in " + repoId + "."));
            return;
        }
        for (HuggingFaceSearch.ModelFile file : files) {
            ButtonComponent button = UIComponents.button(Component.literal(file.fileName()),
                    b -> startDownload(file.downloadUrl()));
            button.horizontalSizing(Sizing.fill(100));
            this.filesForSelectedRepo.child(button);
        }
    }

    private void startDownload(String url) {
        if (ModelDownloadState.status() == ModelDownloadState.Status.DOWNLOADING) {
            return;
        }
        ModConfig config = ModConfig.get();
        config.modelDownloadUrl = url;
        config.modelPath = "";
        config.save();

        CompletableFuture.runAsync(() -> {
            try {
                Path modelsDir = FabricLoader.getInstance().getGameDir().resolve("bettervillagers/models");
                ModelAssets.resolve(modelsDir, url);
            } catch (Exception ignored) {
                // status already recorded in ModelDownloadState
            } finally {
                this.minecraft.execute(this::refreshInstalledModels);
            }
        });
    }

    // --- Installed models ---

    private void refreshInstalledModels() {
        this.installedModels.clearChildren();
        Path modelsDir = FabricLoader.getInstance().getGameDir().resolve("bettervillagers/models");
        List<Path> files;
        try (Stream<Path> stream = Files.exists(modelsDir) ? Files.list(modelsDir) : Stream.empty()) {
            files = stream.filter(p -> p.toString().endsWith(".gguf"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            files = List.of();
        }

        if (files.isEmpty()) {
            this.installedModels.child(label("No models downloaded yet."));
            return;
        }

        ModConfig config = ModConfig.get();
        for (Path file : files) {
            boolean active = file.toAbsolutePath().toString().equals(activeModelPathOf(config));
            FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
            row.gap(6);
            row.verticalAlignment(VerticalAlignment.CENTER);

            long sizeMb = fileSizeMb(file);
            String name = file.getFileName().toString() + " (" + sizeMb + " MB)" + (active ? "  ← active" : "");
            row.child(label(name).horizontalSizing(Sizing.fill(70)));

            ButtonComponent useButton = UIComponents.button(Component.literal("Use"), b -> useLocalModel(file));
            useButton.active(!active);
            row.child(useButton);

            row.child(UIComponents.button(Component.literal("Delete"), b -> deleteModel(file)));

            this.installedModels.child(row);
        }
    }

    private void useLocalModel(Path file) {
        ModConfig config = ModConfig.get();
        config.modelPath = file.toAbsolutePath().toString();
        config.save();
        this.activeModelLabel.text(Component.literal("Active model: " + activeModelName(config)));
        refreshInstalledModels();
    }

    private void deleteModel(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best-effort
        }
        refreshInstalledModels();
    }

    private static String activeModelPathOf(ModConfig config) {
        if (!config.modelPath.isBlank()) {
            return Path.of(config.modelPath).toAbsolutePath().toString();
        }
        Path modelsDir = FabricLoader.getInstance().getGameDir().resolve("bettervillagers/models");
        return modelsDir.resolve(fileNameFromUrl(config.modelDownloadUrl)).toAbsolutePath().toString();
    }

    private static String activeModelName(ModConfig config) {
        if (!config.modelPath.isBlank()) {
            return Path.of(config.modelPath).getFileName().toString();
        }
        return fileNameFromUrl(config.modelDownloadUrl);
    }

    private static String fileNameFromUrl(String url) {
        String path = url;
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private static long fileSizeMb(Path file) {
        try {
            return Files.size(file) / 1_048_576;
        } catch (IOException e) {
            return 0;
        }
    }

    private static String formatCount(long n) {
        if (n >= 1_000_000) {
            return String.format("%.1fM", n / 1_000_000.0);
        }
        if (n >= 1_000) {
            return String.format("%.1fK", n / 1_000.0);
        }
        return String.valueOf(n);
    }

    // --- Live status / progress ---

    @Override
    public void tick() {
        super.tick();

        LlamaServerProcess llm = LlamaServerProcess.instance();
        int statusColor = switch (llm.state()) {
            case READY -> COLOR_READY;
            case STARTING -> COLOR_STARTING;
            case DEGRADED, STOPPED -> COLOR_ERROR;
        };
        this.statusLabel.text(Component.literal("Status: " + llm.state()));
        this.statusLabel.color(Color.ofRgb(statusColor));

        String stats = llm.lastTokensPerSecond() > 0
                ? String.format("Last: %.1f tok/s  •  Average: %.1f tok/s", llm.lastTokensPerSecond(), llm.averageTokensPerSecond())
                : "No completions yet this session";
        this.statsLabel.text(Component.literal(stats));
        this.statsLabel.color(Color.ofRgb(COLOR_DIM));

        ModelDownloadState.Status downloadStatus = ModelDownloadState.status();
        switch (downloadStatus) {
            case DOWNLOADING -> {
                long done = ModelDownloadState.bytesDownloaded();
                long total = ModelDownloadState.totalBytes();
                if (total > 0) {
                    int percent = (int) (done * 100 / total);
                    this.downloadLabel.text(Component.literal(String.format("Downloading... %d%% (%.0f / %.0f MB)",
                            percent, done / 1_048_576.0, total / 1_048_576.0)));
                } else {
                    this.downloadLabel.text(Component.literal(String.format("Downloading... %.0f MB", done / 1_048_576.0)));
                }
                this.downloadLabel.color(Color.ofRgb(COLOR_STARTING));
            }
            case DONE -> {
                this.downloadLabel.text(Component.literal("Download complete."));
                this.downloadLabel.color(Color.ofRgb(COLOR_READY));
            }
            case ERROR -> {
                this.downloadLabel.text(Component.literal("Download failed: " + ModelDownloadState.errorMessage()));
                this.downloadLabel.color(Color.ofRgb(COLOR_ERROR));
            }
            case IDLE -> {
            }
        }
    }

    private void saveConfig() {
        ModConfig config = ModConfig.get();
        config.gpuLayers = parseIntOr(this.gpuLayersBox.getValue(), config.gpuLayers);
        config.contextSize = parseIntOr(this.contextSizeBox.getValue(), config.contextSize);
        config.threads = parseIntOr(this.threadsBox.getValue(), config.threads);
        config.maxTokens = parseIntOr(this.maxTokensBox.getValue(), config.maxTokens);
        config.temperature = parseFloatOr(this.temperatureBox.getValue(), config.temperature);
        config.save();
    }

    private static int parseIntOr(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float parseFloatOr(String value, float fallback) {
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
