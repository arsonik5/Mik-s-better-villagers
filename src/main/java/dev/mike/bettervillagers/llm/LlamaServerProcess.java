package dev.mike.bettervillagers.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import net.fabricmc.loader.api.FabricLoader;

import dev.mike.bettervillagers.BetterVillagers;
import dev.mike.bettervillagers.config.ModConfig;

/**
 * Owns the bundled llama-server subprocess: resolving its binary/model
 * (downloading on first run), launching it, health-checking it, and
 * shutting it down cleanly. Singleton, server-side only — never touched
 * from src/client.
 */
public final class LlamaServerProcess {
    public enum State { STOPPED, STARTING, READY, DEGRADED }

    private static final LlamaServerProcess INSTANCE = new LlamaServerProcess();

    private volatile Process process;
    private volatile int port = -1;
    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);

    private volatile double lastTokensPerSecond = -1;
    private volatile double averageTokensPerSecond = -1;
    private int tokensPerSecondSamples = 0;

    private final AtomicBoolean shutdownHookRegistered = new AtomicBoolean(false);

    private LlamaServerProcess() {
    }

    public static LlamaServerProcess instance() {
        return INSTANCE;
    }

    public State state() {
        return state.get();
    }

    public int port() {
        return port;
    }

    public double lastTokensPerSecond() {
        return lastTokensPerSecond;
    }

    public double averageTokensPerSecond() {
        return averageTokensPerSecond;
    }

    public synchronized void recordTokensPerSecond(double tokensPerSecond) {
        if (tokensPerSecond <= 0) {
            return;
        }
        this.lastTokensPerSecond = tokensPerSecond;
        this.tokensPerSecondSamples++;
        this.averageTokensPerSecond = this.averageTokensPerSecond < 0
                ? tokensPerSecond
                : this.averageTokensPerSecond + (tokensPerSecond - this.averageTokensPerSecond) / this.tokensPerSecondSamples;
    }

    public void startAsync() {
        if (!state.compareAndSet(State.STOPPED, State.STARTING)) {
            return;
        }
        CompletableFuture.runAsync(this::startBlocking).exceptionally(ex -> {
            BetterVillagers.LOGGER.warn("Local LLM unavailable — chat will be disabled this session", ex);
            state.set(State.DEGRADED);
            return null;
        });
    }

    /** Stops the current process (if any) and starts fresh — used after the config changes. */
    public void restart() {
        stop();
        state.set(State.STOPPED);
        startAsync();
    }

    private void startBlocking() {
        try {
            ModConfig config = ModConfig.get();
            Path root = FabricLoader.getInstance().getGameDir().resolve("bettervillagers");
            Files.createDirectories(root);
            Path pidFile = root.resolve("llama-server.pid");

            // A previous session's llama-server can be left running if the
            // JVM was killed hard enough that our shutdown hook never ran
            // (e.g. a forceful process kill, or a crash) — GPU memory stays
            // pinned by an orphan nobody's talking to. Clean that up before
            // starting a new one, every launch.
            killStaleProcess(pidFile);

            if (this.shutdownHookRegistered.compareAndSet(false, true)) {
                Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "bv-llama-shutdown"));
            }

            Path exe = config.llamaServerPath.isBlank()
                    ? NativeAssets.resolve(root.resolve("bin"))
                    : Path.of(config.llamaServerPath);
            Path model = config.modelPath.isBlank()
                    ? ModelAssets.resolve(root.resolve("models"), config.modelDownloadUrl)
                    : Path.of(config.modelPath);

            this.port = findFreePort();

            List<String> command = List.of(
                    exe.toAbsolutePath().toString(),
                    "-m", model.toAbsolutePath().toString(),
                    "--host", "127.0.0.1",
                    "--port", String.valueOf(port),
                    "-ngl", String.valueOf(config.gpuLayers),
                    "-c", String.valueOf(config.contextSize),
                    "-t", String.valueOf(config.threads),
                    "--parallel", "2",
                    "--cont-batching",
                    "--no-webui"
            );

            BetterVillagers.LOGGER.info("Starting llama-server on port {} with model {}", port, model.getFileName());
            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(exe.getParent().toFile())
                    .redirectErrorStream(true);
            this.process = pb.start();
            writePidFile(pidFile, this.process.pid());

            Thread logDrain = new Thread(this::drainLog, "bv-llama-log");
            logDrain.setDaemon(true);
            logDrain.start();

            waitUntilHealthy();
        } catch (IOException e) {
            BetterVillagers.LOGGER.warn("Failed to start local LLM server", e);
            state.set(State.DEGRADED);
        }
    }

    private static void killStaleProcess(Path pidFile) {
        if (!Files.exists(pidFile)) {
            return;
        }
        try {
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isPresent() && handle.get().isAlive()
                    && handle.get().info().command().map(cmd -> cmd.toLowerCase().contains("llama-server")).orElse(false)) {
                BetterVillagers.LOGGER.info("Found a stale llama-server (pid {}) from a previous session, stopping it", pid);
                handle.get().destroy();
                handle.get().onExit().orTimeout(5, TimeUnit.SECONDS).exceptionally(ex -> {
                    handle.get().destroyForcibly();
                    return null;
                }).join();
            }
        } catch (Exception e) {
            BetterVillagers.LOGGER.debug("Could not check/stop stale llama-server pid file", e);
        } finally {
            try {
                Files.deleteIfExists(pidFile);
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    private static void writePidFile(Path pidFile, long pid) {
        try {
            Files.writeString(pidFile, String.valueOf(pid));
        } catch (IOException e) {
            BetterVillagers.LOGGER.debug("Could not write llama-server pid file", e);
        }
    }

    private void drainLog() {
        Process p = this.process;
        if (p == null) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                BetterVillagers.LOGGER.debug("[llama-server] {}", line);
            }
        } catch (IOException ignored) {
            // process exited; nothing left to drain
        }
    }

    private void waitUntilHealthy() {
        HttpClient client = HttpClient.newHttpClient();
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                state.set(State.DEGRADED);
                BetterVillagers.LOGGER.warn("llama-server exited before becoming healthy (exit code {})", process.exitValue());
                return;
            }
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                        .timeout(Duration.ofSeconds(1)).GET().build();
                HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() == 200) {
                    state.set(State.READY);
                    BetterVillagers.LOGGER.info("Local LLM ready on port {}", port);
                    return;
                }
            } catch (IOException | InterruptedException ignored) {
                // not up yet, keep polling
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        state.set(State.DEGRADED);
        BetterVillagers.LOGGER.warn("llama-server did not become healthy within the startup window");
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    public void stop() {
        state.set(State.STOPPED);
        Process p = this.process;
        if (p == null) {
            return;
        }
        p.destroy();
        try {
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        this.process = null;

        try {
            Path pidFile = FabricLoader.getInstance().getGameDir().resolve("bettervillagers/llama-server.pid");
            Files.deleteIfExists(pidFile);
        } catch (Exception ignored) {
            // best-effort
        }
    }
}
