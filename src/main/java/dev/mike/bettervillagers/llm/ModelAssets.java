package dev.mike.bettervillagers.llm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import dev.mike.bettervillagers.BetterVillagers;

/**
 * Resolves the configured local model, downloading it on first run (or
 * whenever the configured URL points at a model that isn't in the models
 * directory yet) — never bundled in the mod jar, a ~1-3GB file has no
 * business shipping inside a jar. The target filename is derived from the
 * URL itself, so pointing the config at a different model downloads a new
 * file alongside any previous ones rather than colliding with a fixed name.
 * Progress is published to ModelDownloadState for the settings screen.
 */
public final class ModelAssets {
    private ModelAssets() {
    }

    public static synchronized Path resolve(Path modelsDir, String downloadUrl) throws IOException {
        Files.createDirectories(modelsDir);
        String fileName = fileNameFromUrl(downloadUrl);
        Path target = modelsDir.resolve(fileName);
        if (Files.exists(target) && Files.size(target) > 0) {
            return target;
        }

        BetterVillagers.LOGGER.info("Downloading model {} (this only happens once per model)...", fileName);
        ModelDownloadState.begin();
        Path partial = modelsDir.resolve(fileName + ".part");
        try {
            downloadWithProgress(downloadUrl, partial);
        } catch (IOException e) {
            ModelDownloadState.error(e.getMessage() == null ? e.toString() : e.getMessage());
            throw e;
        }

        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        ModelDownloadState.done();
        BetterVillagers.LOGGER.info("Model downloaded to {}", target);
        return target;
    }

    private static void downloadWithProgress(String url, Path dest) throws IOException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("Download failed (" + response.statusCode() + ")");
            }
            long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            ModelDownloadState.progress(0, total);

            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(dest, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buf = new byte[1 << 16];
                long downloaded = 0;
                int read;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                    downloaded += read;
                    ModelDownloadState.progress(downloaded, total);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
    }

    private static String fileNameFromUrl(String url) {
        String path = URI.create(url).getPath();
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.isBlank() ? "model.gguf" : name;
    }
}
