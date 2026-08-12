package com.gradion.studio;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "gradion.image.provider", havingValue = "gemini")
class GeminiImageGateway implements ImageGenerationGateway {
    private static final Logger log = LoggerFactory.getLogger(GeminiImageGateway.class);
    private final ObjectMapper json;
    private final HttpClient client;
    private final String apiKey;
    private final String model;
    private final String baseUrl;

    GeminiImageGateway(ObjectMapper json,
            @Value("${gradion.gemini.api-key:}") String apiKey,
            @Value("${gradion.gemini.image-model:gemini-2.5-flash-image}") String model,
            @Value("${gradion.gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl) {
        this(json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build(), apiKey, model, baseUrl);
    }

    GeminiImageGateway(ObjectMapper json, HttpClient client, String apiKey, String model, String baseUrl) {
        this.json = json;
        this.client = client;
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
    }

    @Override
    public ImageResult generatePortrait(String name, String prompt) {
        return generate("Create a portrait of " + name + ". " + prompt);
    }

    @Override
    public ImageResult generateIllustration(String prompt) {
        return generate(prompt);
    }

    private ImageResult generate(String prompt) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("Gemini image generation is not configured.");
        try {
            ObjectNode payload = json.createObjectNode();
            ArrayNode contents = payload.putArray("contents");
            ObjectNode content = contents.addObject();
            content.putArray("parts").addObject().put("text", prompt);
            ObjectNode config = payload.putObject("generationConfig");
            config.putArray("responseModalities").add("TEXT").add("IMAGE");

            String endpoint = baseUrl.replaceAll("/$", "") + "/v1/models/" + model + ":generateContent";
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofMinutes(2))
                    .header("x-goog-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw failure(response);
            JsonNode root = json.readTree(response.body());
            for (JsonNode part : root.path("candidates").path(0).path("content").path("parts")) {
                JsonNode inline = part.path("inlineData");
                String encoded = inline.path("data").asText("");
                if (!encoded.isBlank()) {
                    return new ImageResult("gemini-image-" + UUID.randomUUID(),
                            inline.path("mimeType").asText("image/png"), Base64.getDecoder().decode(encoded));
                }
            }
            throw new IllegalStateException("Gemini image response did not contain image data.");
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Could not call Gemini image generation.", exception);
        }
    }

    private IllegalStateException failure(HttpResponse<String> response) {
        String detail = response.body().replaceAll("[\\r\\n]+", " ").trim();
        detail = detail.substring(0, Math.min(500, detail.length()));
        log.warn("Gemini image request failed (HTTP {}): {}", response.statusCode(), detail);
        return new IllegalStateException("Gemini image request failed (HTTP " + response.statusCode() + "): " + detail);
    }
}
