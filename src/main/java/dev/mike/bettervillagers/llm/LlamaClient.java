package dev.mike.bettervillagers.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Talks to the local llama-server over its OpenAI-compatible HTTP API. */
public final class LlamaClient {
    public record Message(String role, String content) {
    }

    /** {@code tokensPerSecond} is -1 if the server didn't report a token count for this reply. */
    public record ChatResult(String text, double tokensPerSecond) {
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .executor(Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "bv-llm-http");
                t.setDaemon(true);
                return t;
            }))
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private LlamaClient() {
    }

    public static CompletableFuture<ChatResult> chat(int port, List<Message> messages, int maxTokens, float temperature,
                                                       int timeoutSeconds) {
        JsonObject body = new JsonObject();
        JsonArray messagesArray = new JsonArray();
        for (Message m : messages) {
            JsonObject msg = new JsonObject();
            msg.addProperty("role", m.role());
            msg.addProperty("content", m.content());
            messagesArray.add(msg);
        }
        body.add("messages", messagesArray);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("temperature", temperature);
        body.addProperty("stream", false);

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        long startNanos = System.nanoTime();
        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() / 100 != 2) {
                        throw new RuntimeException("llama-server returned " + response.statusCode() + ": " + response.body());
                    }
                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    String text = json.getAsJsonArray("choices").get(0).getAsJsonObject()
                            .getAsJsonObject("message").get("content").getAsString().trim();

                    double tokensPerSecond = -1;
                    if (json.has("usage") && json.getAsJsonObject("usage").has("completion_tokens")) {
                        int tokens = json.getAsJsonObject("usage").get("completion_tokens").getAsInt();
                        double elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
                        if (tokens > 0 && elapsedSeconds > 0) {
                            tokensPerSecond = tokens / elapsedSeconds;
                        }
                    }
                    return new ChatResult(text, tokensPerSecond);
                });
    }
}
