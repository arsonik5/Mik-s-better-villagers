package dev.mike.bettervillagers.client.model;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Client-only: searches Hugging Face for GGUF models, LM-Studio-style. No gameplay impact. */
public final class HuggingFaceSearch {
    public record ModelResult(String repoId, long downloads) {
    }

    public record ModelFile(String fileName, String downloadUrl) {
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private HuggingFaceSearch() {
    }

    public static List<ModelResult> search(String query) throws IOException, InterruptedException {
        String url = "https://huggingface.co/api/models?search=" + encode(query)
                + "&filter=gguf&sort=downloads&direction=-1&limit=15";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Search failed (" + response.statusCode() + ")");
        }

        List<ModelResult> results = new ArrayList<>();
        JsonArray array = JsonParser.parseString(response.body()).getAsJsonArray();
        for (JsonElement element : array) {
            JsonObject obj = element.getAsJsonObject();
            String id = obj.get("id").getAsString();
            long downloads = obj.has("downloads") ? obj.get("downloads").getAsLong() : 0;
            results.add(new ModelResult(id, downloads));
        }
        return results;
    }

    public static List<ModelFile> listGgufFiles(String repoId) throws IOException, InterruptedException {
        String url = "https://huggingface.co/api/models/" + repoId;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Could not read " + repoId + " (" + response.statusCode() + ")");
        }

        List<ModelFile> files = new ArrayList<>();
        JsonObject obj = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!obj.has("siblings")) {
            return files;
        }
        for (JsonElement element : obj.getAsJsonArray("siblings")) {
            String name = element.getAsJsonObject().get("rfilename").getAsString();
            if (name.toLowerCase().endsWith(".gguf")) {
                String downloadUrl = "https://huggingface.co/" + repoId + "/resolve/main/" + name;
                files.add(new ModelFile(name, downloadUrl));
            }
        }
        return files;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
