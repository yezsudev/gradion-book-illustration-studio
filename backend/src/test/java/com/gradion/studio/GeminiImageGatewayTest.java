package com.gradion.studio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GeminiImageGatewayTest {
    @Test
    void parsesInlineImageDataFromNanoBananaResponse() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = (HttpResponse<String>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"image/png","data":"aGVsbG8="}}]}}]}
                """);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        GeminiImageGateway gateway = new GeminiImageGateway(new ObjectMapper(), client,
                "test-key", "gemini-2.5-flash-image", "https://generativelanguage.googleapis.com");

        ImageGenerationGateway.ImageResult result = gateway.generatePortrait("Mara", "A lighthouse keeper");

        assertEquals("image/png", result.mimeType());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), result.bytes());
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals("https://generativelanguage.googleapis.com/v1/models/gemini-2.5-flash-image:generateContent", request.getValue().uri().toString());
        assertEquals("test-key", request.getValue().headers().firstValue("x-goog-api-key").orElseThrow());
    }
}
