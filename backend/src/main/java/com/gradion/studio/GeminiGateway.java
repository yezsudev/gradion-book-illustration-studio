package com.gradion.studio;

import java.nio.file.Path;

interface GeminiGateway {
    record FileReference(String name, String uri) { }
    record Interaction(String id, String text) { }

    boolean isAvailable(FileReference file, String interactionId);
    FileReference uploadBook(Path book);
    Interaction createBookContext(FileReference file);
    Interaction createStyleContext(String rootInteractionId, String style);
    Interaction generateStyle(String rootInteractionId);
    Interaction generateCharacters(String styleInteractionId);
    Interaction createCharactersContext(String styleInteractionId, String characters);
    Interaction generateChapter(String charactersInteractionId);
}
