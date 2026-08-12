package com.gradion.studio;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class HttpGeminiGateway implements GeminiGateway {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper json;
    private final String key;
    private final String model;
    private final String baseUrl;

    HttpGeminiGateway(ObjectMapper json,
            @Value("${gradion.gemini.api-key:}") String key,
            @Value("${gradion.gemini.model:gemini-2.5-flash-lite}") String model,
            @Value("${gradion.gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl) {
        this.json = json;
        this.key = key;
        this.model = model;
        this.baseUrl = baseUrl.replaceAll("/$", "");
    }

    @Override
    public boolean isAvailable(FileReference file, String interactionId) {
        if (file == null || blank(file.name()) || blank(interactionId) || blank(key)) return false;
        return succeeds(get("/v1beta/" + file.name())) && succeeds(get("/v1/interactions/" + interactionId));
    }

    @Override
    public FileReference uploadBook(Path book) {
        requireKey();
        try {
            long size = Files.size(book);
            ObjectNode metadata = json.createObjectNode();
            metadata.putObject("file").put("display_name", "book.txt");
            HttpRequest start = request("/upload/v1beta/files")
                    .header("X-Goog-Upload-Protocol", "resumable")
                    .header("X-Goog-Upload-Command", "start")
                    .header("X-Goog-Upload-Header-Content-Length", Long.toString(size))
                    .header("X-Goog-Upload-Header-Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(metadata)))
                    .build();
            HttpResponse<String> started = client.send(start, HttpResponse.BodyHandlers.ofString());
            String uploadUrl = started.headers().firstValue("x-goog-upload-url").orElse(null);
            if (started.statusCode() / 100 != 2 || blank(uploadUrl)) throw failure("Could not start Gemini book upload (HTTP " + started.statusCode() + ").");
            HttpRequest upload = HttpRequest.newBuilder(URI.create(uploadUrl))
                    .header("X-Goog-Upload-Offset", "0")
                    .header("X-Goog-Upload-Command", "upload, finalize")
                    .POST(HttpRequest.BodyPublishers.ofFile(book)).build();
            HttpResponse<String> completed = client.send(upload, HttpResponse.BodyHandlers.ofString());
            JsonNode file = body(completed, "File API finalize").path("file");
            String name = file.path("name").asText();
            String uri = file.path("uri").asText();
            if (blank(name) || blank(uri)) throw failure("Gemini did not return a book reference.");
            return new FileReference(name, uri);
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            throw failure("Could not upload the book to Gemini.", exception);
        }
    }

    @Override
    public Interaction createBookContext(FileReference file) {
        ArrayNode content = json.createArrayNode();
        content.addObject().put("type", "text").put("text", "Use this book as the source for the following illustration-planning steps.");
        content.addObject().put("type", "document").put("uri", file.uri()).put("mime_type", "text/plain");
        return interaction(null, userContent(content), null);
    }

    @Override
    public Interaction createStyleContext(String rootInteractionId, String style) {
        ArrayNode content = json.createArrayNode();
        content.addObject().put("type", "text").put("text", "Use this exact approved visual style for the book: " + style);
        return interaction(rootInteractionId, userContent(content), null);
    }

    @Override
    public Interaction generateStyle(String rootInteractionId) {
        ArrayNode content = json.createArrayNode();
        content.addObject().put("type", "text").put("text", "Create one concise visual illustration style for this book. Return only the style description.");
        return interaction(rootInteractionId, userContent(content), null);
    }

    @Override
    public Interaction generateCharacters(String styleInteractionId) {
        ArrayNode content = json.createArrayNode();
        content.addObject().put("type", "text").put("text", "Identify one or two important adult characters only. Return name, a detailed portrait prompt, and adult=true for each. Do not include children.");
        ObjectNode schema = json.createObjectNode();
        schema.put("type", "array").put("minItems", 1).put("maxItems", 2);
        ObjectNode item = schema.putObject("items");
        item.put("type", "object");
        ObjectNode properties = item.putObject("properties");
        properties.putObject("name").put("type", "string");
        properties.putObject("portrait_prompt").put("type", "string");
        properties.putObject("adult").put("type", "boolean");
        item.putArray("required").add("name").add("portrait_prompt").add("adult");
        item.put("additionalProperties", false);
        ObjectNode format = json.createObjectNode();
        format.put("type", "text").put("mime_type", "application/json").set("schema", schema);
        return interaction(styleInteractionId, userContent(content), format);
    }

    @Override
    public Interaction createCharactersContext(String styleInteractionId, String characters) {
        ArrayNode content = json.createArrayNode();
        content.addObject().put("type", "text").put("text", "Use these persisted adult character descriptions for portrait generation: " + characters);
        return interaction(styleInteractionId, userContent(content), null);
    }


    private ArrayNode userContent(ArrayNode parts) {
        ObjectNode step = json.createObjectNode();
        step.put("type", "content").put("role", "user").set("content", parts);
        return json.createArrayNode().add(step);
    }

    private Interaction interaction(String previousId, ArrayNode input, ObjectNode responseFormat) {
        JsonNode body = interactionBody(model, previousId, input, responseFormat);
        String id = body.path("id").asText();
        String text = outputText(body);
        if (blank(id) || blank(text)) throw failure("Gemini returned an incomplete response.");
        return new Interaction(id, text.trim());
    }

    private JsonNode interactionBody(String requestModel, String previousId, ArrayNode input, ObjectNode responseFormat) {
        requireKey();
        try {
            ObjectNode request = json.createObjectNode();
            request.put("model", requestModel).set("input", input);
            if (previousId != null) request.put("previous_interaction_id", previousId);
            if (responseFormat != null) request.set("response_format", responseFormat);
            HttpResponse<String> response = client.send(request("/v1/interactions")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request))).build(), HttpResponse.BodyHandlers.ofString());
            return body(response, "Interactions create");
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            throw failure("Could not call Gemini.", exception);
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofMinutes(2))
                .header("x-goog-api-key", key).header("Content-Type", "application/json");
    }

    private HttpResponse<String> get(String path) {
        try {
            return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    private boolean succeeds(HttpResponse<String> response) { return response != null && response.statusCode() / 100 == 2; }

    private JsonNode body(HttpResponse<String> response, String operation) {
        if (response.statusCode() / 100 != 2) {
            String detail = response.body() == null ? "" : response.body().replace(key, "[redacted]").replaceAll("[\\r\\n]+", " ");
            if (detail.length() > 500) detail = detail.substring(0, 500) + "…";
            throw failure(operation + " failed (HTTP " + response.statusCode() + "): " + detail);
        }
        try { return json.readTree(response.body()); } catch (IOException exception) { throw failure(operation + " returned invalid JSON.", exception); }
    }

    private String outputText(JsonNode response) {
        if (!blank(response.path("output_text").asText())) return response.path("output_text").asText();
        String result = "";
        for (JsonNode step : response.path("steps")) {
            if (!"model_output".equals(step.path("type").asText())) continue;
            for (JsonNode content : step.path("content")) {
                if ("text".equals(content.path("type").asText()) && !blank(content.path("text").asText())) {
                    result = content.path("text").asText();
                }
            }
        }
        return result;
    }

    private void requireKey() { if (blank(key)) throw failure("Gemini is not configured."); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private IllegalStateException failure(String message) { return new IllegalStateException(message); }
    private IllegalStateException failure(String message, Exception cause) { return new IllegalStateException(message, cause); }
}
