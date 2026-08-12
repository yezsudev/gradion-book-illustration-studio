package com.gradion.studio;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class HuggingFaceImageGateway implements ImageGenerationGateway {
    private static final Logger log = LoggerFactory.getLogger(HuggingFaceImageGateway.class);
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final String token;
    private final String provider;
    private final String model;

    HuggingFaceImageGateway(ObjectMapper json,
            @Value("${gradion.huggingface.token:}") String token,
            @Value("${gradion.huggingface.provider:nscale}") String provider,
            @Value("${gradion.huggingface.image-model:black-forest-labs/FLUX.1-schnell}") String model) {
        this.json = json;
        this.token = token;
        this.provider = provider;
        this.model = model;
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
        if (token == null || token.isBlank()) throw new IllegalStateException("Hugging Face is not configured.");
        try {
            ObjectNode payload = json.createObjectNode();
            payload.put("prompt", prompt);
            payload.put("model", model);
            payload.put("response_format", "b64_json");
            payload.put("size", "512x512");
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://router.huggingface.co/" + provider + "/v1/images/generations"))
                    .timeout(Duration.ofMinutes(2))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) throw failure(response);
            String encoded = json.readTree(response.body()).path("data").path(0).path("b64_json").asText("");
            if (encoded.isBlank()) {
                String detail = sanitized(response.body());
                log.warn("Hugging Face portrait response did not contain image data: {}", detail);
                throw new IllegalStateException("Hugging Face image response did not contain image data.");
            }
            return new ImageResult("huggingface-" + UUID.randomUUID(), "image/png", Base64.getDecoder().decode(encoded));
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Could not call Hugging Face image generation.", exception);
        }
    }

    private IllegalStateException failure(HttpResponse<byte[]> response) {
        String detail = sanitized(response.body());
        log.warn("Hugging Face portrait request failed (HTTP {}): {}", response.statusCode(), detail);
        return new IllegalStateException("Hugging Face image request failed (HTTP " + response.statusCode() + "): " + detail);
    }

    private String sanitized(byte[] body) {
        String value = new String(body, StandardCharsets.UTF_8).replace(token, "[redacted]").replaceAll("[\\r\\n]+", " ").trim();
        return value.substring(0, Math.min(500, value.length()));
    }
}
