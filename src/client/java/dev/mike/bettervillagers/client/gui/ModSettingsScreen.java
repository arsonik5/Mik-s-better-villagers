package dev.mike.bettervillagers.client.gui;

import java.util.concurrent.CompletableFuture;

import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.mike.bettervillagers.config.ModConfig;
import dev.mike.bettervillagers.llm.LlamaServerProcess;
import dev.mike.bettervillagers.llm.ModelAssets;
import dev.mike.bettervillagers.llm.ModelDownloadState;

/** Settings for the local LLM: which model to use, GPU/context settings, and live status. */
public class ModSettingsScreen extends BaseOwoScreen<FlowLayout> {
    private static final int COLOR_TEXT = 0xFFE0E0E0;
    private static final int COLOR_DIM = 0xFF9A9AA0;
    private static final int COLOR_READY = 0xFF6FCF6F;
    private static final int COLOR_STARTING = 0xFFE0C05A;
    private static final int COLOR_ERROR = 0xFFE05252;
    private static final int COLOR_PANEL = 0xF0202225;

    private final Screen parent;
    private TextBoxComponent modelUrlBox;
    private TextBoxComponent gpuLayersBox;
    private TextBoxComponent contextSizeBox;
    private TextBoxComponent threadsBox;
    private TextBoxComponent maxTokensBox;
    private TextBoxComponent temperatureBox;
    private LabelComponent statusLabel;
    private LabelComponent statsLabel;
    private LabelComponent downloadLabel;
    private ButtonComponent downloadButton;

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

        root.surface(Surface.flat(0xB0000000));
        root.horizontalAlignment(io.wispforest.owo.ui.core.HorizontalAlignment.CENTER);
        root.verticalAlignment(io.wispforest.owo.ui.core.VerticalAlignment.CENTER);

        FlowLayout panel = UIContainers.verticalFlow(Sizing.fixed(420), Sizing.fixed(360));
        panel.surface(Surface.flat(COLOR_PANEL));
        panel.padding(Insets.of(14));
        panel.gap(6);

        panel.child(UIComponents.label(Component.literal("Better Villagers — LLM Settings"))
                .color(Color.ofRgb(0xFFFFFF)));

        this.statusLabel = UIComponents.label(Component.literal("Status: ..."));
        panel.child(this.statusLabel);
        this.statsLabel = UIComponents.label(Component.literal(""));
        this.statsLabel.color(Color.ofRgb(COLOR_DIM));
        panel.child(this.statsLabel);

        var scroll = UIContainers.verticalScroll(Sizing.fill(100), Sizing.fill(100), buildForm(config));
        panel.child(scroll);

        FlowLayout buttons = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        buttons.gap(8);
        buttons.child(UIComponents.button(Component.literal("Save"), b -> saveConfig()));
        buttons.child(UIComponents.button(Component.literal("Save & Restart LLM"), b -> {
            saveConfig();
            LlamaServerProcess.instance().restart();
        }));
        buttons.child(UIComponents.button(Component.literal("Done"), b ->
                this.minecraft.setScreen(this.parent)));
        panel.child(buttons);

        root.child(panel);
    }

    private FlowLayout buildForm(ModConfig config) {
        FlowLayout form = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        form.gap(8);

        form.child(label("Model URL (or set a local file path in Model Path below)"));
        this.modelUrlBox = UIComponents.textBox(Sizing.fill(100), config.modelDownloadUrl);
        this.modelUrlBox.setMaxLength(2000);
        form.child(this.modelUrlBox);

        this.downloadLabel = label("");
        form.child(this.downloadLabel);
        this.downloadButton = UIComponents.button(Component.literal("Download model"), b -> startDownload());
        form.child(this.downloadButton);

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

    private FlowLayout row(String labelText, TextBoxComponent box) {
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.gap(8);
        row.verticalAlignment(io.wispforest.owo.ui.core.VerticalAlignment.CENTER);
        row.child(label(labelText).horizontalSizing(Sizing.fill(70)));
        row.child(box);
        return row;
    }

    private LabelComponent label(String text) {
        return UIComponents.label(Component.literal(text)).color(Color.ofRgb(COLOR_TEXT));
    }

    private void startDownload() {
        if (ModelDownloadState.status() == ModelDownloadState.Status.DOWNLOADING) {
            return;
        }
        String url = this.modelUrlBox.getValue().trim();
        this.downloadButton.active(false);
        CompletableFuture.runAsync(() -> {
            try {
                java.nio.file.Path modelsDir = FabricLoader.getInstance().getGameDir().resolve("bettervillagers/models");
                ModelAssets.resolve(modelsDir, url);
            } catch (Exception ignored) {
                // status already recorded in ModelDownloadState
            }
        });
    }

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
                this.downloadButton.active(true);
            }
            case ERROR -> {
                this.downloadLabel.text(Component.literal("Download failed: " + ModelDownloadState.errorMessage()));
                this.downloadLabel.color(Color.ofRgb(COLOR_ERROR));
                this.downloadButton.active(true);
            }
            case IDLE -> this.downloadButton.active(true);
        }
    }

    private void saveConfig() {
        ModConfig config = ModConfig.get();
        config.modelDownloadUrl = this.modelUrlBox.getValue().trim();
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
