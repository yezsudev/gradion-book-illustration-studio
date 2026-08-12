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

    void deleteProject(String projectId) throws IOException {
        Path directory = projectDirectory(projectId);
        Files.deleteIfExists(directory.resolve("book.txt"));
        Files.deleteIfExists(directory);
    }

    private Path projectDirectory(String projectId) {
        UUID.fromString(projectId);
        Path directory = root.resolve(projectId).normalize();
        if (!directory.startsWith(root)) throw new IllegalArgumentException("Invalid project path.");
        return directory;
    }
}
