package com.gradion.studio;

interface ImageGenerationGateway {
    record ImageResult(String id, String mimeType, byte[] bytes) { }

    ImageResult generatePortrait(String name, String prompt);
    ImageResult generateIllustration(String prompt);
}
