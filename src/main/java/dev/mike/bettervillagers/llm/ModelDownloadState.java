package dev.mike.bettervillagers.llm;

/** Shared progress state for a model download, polled by the settings screen. */
public final class ModelDownloadState {
    public enum Status { IDLE, DOWNLOADING, DONE, ERROR }

    private static volatile Status status = Status.IDLE;
    private static volatile long bytesDownloaded = 0;
    private static volatile long totalBytes = -1;
    private static volatile String errorMessage = "";

    private ModelDownloadState() {
    }

    public static Status status() {
        return status;
    }

    public static long bytesDownloaded() {
        return bytesDownloaded;
    }

    public static long totalBytes() {
        return totalBytes;
    }

    public static String errorMessage() {
        return errorMessage;
    }

    static void begin() {
        status = Status.DOWNLOADING;
        bytesDownloaded = 0;
        totalBytes = -1;
        errorMessage = "";
    }

    static void progress(long downloaded, long total) {
        bytesDownloaded = downloaded;
        totalBytes = total;
    }

    static void done() {
        status = Status.DONE;
    }

    static void error(String message) {
        status = Status.ERROR;
        errorMessage = message;
    }
}
