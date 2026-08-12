package com.gradion.studio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class ProjectFiles {
    private final Path root;

    ProjectFiles(@Value("${gradion.projects.path:data/projects}") String rootPath) {
        root = Path.of(rootPath).toAbsolutePath().normalize();
    }

    void writeBook(String projectId, String bookText) throws IOException {
        Path directory = projectDirectory(projectId);
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "book-", ".tmp");
        Path book = directory.resolve("book.txt");
        try {
            Files.writeString(temporary, bookText, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, book, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, book, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    String readBook(String projectId) throws IOException {
        return Files.readString(projectDirectory(projectId).resolve("book.txt"), StandardCharsets.UTF_8);
    }

    Path bookPath(String projectId) {
        return projectDirectory(projectId).resolve("book.txt");
    }

    void writePortrait(String projectId, String characterId, byte[] bytes) throws IOException {
        Path directory = projectDirectory(projectId).resolve("portraits");
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "portrait-", ".tmp");
        Path portrait = portraitPath(projectId, characterId);
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, portrait, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, portrait, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    byte[] readPortrait(String projectId, String characterId) throws IOException {
        return Files.readAllBytes(portraitPath(projectId, characterId));
    }

    Path portraitPath(String projectId, String characterId) {
        UUID.fromString(characterId);
        return projectDirectory(projectId).resolve("portraits").resolve(characterId + ".png").normalize();
    }

    void deleteProject(String projectId) throws IOException {
        Path directory = projectDirectory(projectId);
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                });
            }
        }
    }

    private Path projectDirectory(String projectId) {
        UUID.fromString(projectId);
        Path directory = root.resolve(projectId).normalize();
        if (!directory.startsWith(root)) throw new IllegalArgumentException("Invalid project path.");
        return directory;
    }
}
