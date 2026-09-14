package dev.mike.bettervillagers.llm;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import dev.mike.bettervillagers.BetterVillagers;

/**
 * Resolves the bundled llama-server binary for the current platform,
 * downloading and extracting the matching llama.cpp release archive on
 * first run if it isn't present yet. No native binary ships inside the mod
 * jar itself.
 */
public final class NativeAssets {
    private static final String LLAMA_CPP_TAG = "b10948";
    private static final String BASE_URL = "https://github.com/ggml-org/llama.cpp/releases/download/" + LLAMA_CPP_TAG + "/";

    private NativeAssets() {
    }

    private enum Archive { ZIP, TAR_GZ }

    private record Asset(String fileName, Archive archive) {
    }

    private static Asset assetForPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm");

        if (os.contains("win")) {
            // Vulkan build: GPU-accelerated on NVIDIA/AMD/Intel alike without picking a vendor-specific backend.
            return new Asset("llama-" + LLAMA_CPP_TAG + "-bin-win-vulkan-x64.zip", Archive.ZIP);
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return new Asset("llama-" + LLAMA_CPP_TAG + "-bin-macos-" + (arm ? "arm64" : "x64") + ".tar.gz", Archive.TAR_GZ);
        }
        return new Asset("llama-" + LLAMA_CPP_TAG + "-bin-ubuntu-" + (arm ? "arm64" : "vulkan-x64") + ".tar.gz", Archive.TAR_GZ);
    }

    private static String executableName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "llama-server.exe" : "llama-server";
    }

    /** Returns the resolved executable path, downloading/extracting it first if necessary. */
    public static synchronized Path resolve(Path binDir) throws IOException {
        Files.createDirectories(binDir);
        Path existing = findExecutable(binDir);
        if (existing != null) {
            return existing;
        }

        Asset asset = assetForPlatform();
        Path archivePath = binDir.resolve(asset.fileName());
        BetterVillagers.LOGGER.info("Downloading llama-server ({}) — this only happens once...", asset.fileName());
        download(BASE_URL + asset.fileName(), archivePath);

        BetterVillagers.LOGGER.info("Extracting {}", asset.fileName());
        if (asset.archive() == Archive.ZIP) {
            extractZip(archivePath, binDir);
        } else {
            extractTarGz(archivePath, binDir);
        }
        Files.deleteIfExists(archivePath);

        Path resolved = findExecutable(binDir);
        if (resolved == null) {
            throw new IOException("llama-server executable not found after extracting " + asset.fileName());
        }
        makeExecutable(resolved);
        return resolved;
    }

    private static Path findExecutable(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return null;
        }
        String name = executableName();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.getFileName().toString().equals(name)).findFirst().orElse(null);
        }
    }

    private static void download(String url, Path dest) throws IOException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        try {
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(dest));
            if (response.statusCode() / 100 != 2) {
                throw new IOException("Download failed (" + response.statusCode() + "): " + url);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted: " + url, e);
        }
    }

    private static void extractZip(Path archive, Path destDir) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = destDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(destDir)) {
                    continue; // zip-slip guard
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Minimal pure-Java ustar reader — enough for llama.cpp's own release tarballs (short, flat paths, no PAX headers). */
    private static void extractTarGz(Path archive, Path destDir) throws IOException {
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            byte[] header = new byte[512];
            while (true) {
                int read = readFully(in, header);
                if (read < 512 || isAllZero(header)) {
                    break;
                }

                String name = readString(header, 0, 100);
                long size = header[124] == 0 ? 0 : Long.parseLong(readString(header, 124, 12).trim(), 8);
                byte typeFlag = header[156];

                Path target = destDir.resolve(name).normalize();
                boolean safe = target.startsWith(destDir);

                if (!safe) {
                    skipFully(in, size);
                } else if (typeFlag == '5' || name.endsWith("/")) {
                    Files.createDirectories(target);
                } else if (typeFlag == '0' || typeFlag == 0) {
                    Files.createDirectories(target.getParent());
                    try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        copyN(in, out, size);
                    }
                } else {
                    skipFully(in, size);
                }

                long padding = (512 - (size % 512)) % 512;
                skipFully(in, padding);
            }
        }
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long skipped = in.skip(n);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    break;
                }
                skipped = 1;
            }
            n -= skipped;
        }
    }

    private static void copyN(InputStream in, OutputStream out, long n) throws IOException {
        byte[] buf = new byte[8192];
        while (n > 0) {
            int toRead = (int) Math.min(buf.length, n);
            int read = in.read(buf, 0, toRead);
            if (read < 0) {
                break;
            }
            out.write(buf, 0, read);
            n -= read;
        }
    }

    private static String readString(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.US_ASCII);
    }

    private static boolean isAllZero(byte[] header) {
        for (byte b : header) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static void makeExecutable(Path path) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException e) {
            // Windows has no POSIX permission bits — the .exe is already runnable.
        } catch (IOException e) {
            BetterVillagers.LOGGER.warn("Could not mark {} as executable", path, e);
        }
    }
}
