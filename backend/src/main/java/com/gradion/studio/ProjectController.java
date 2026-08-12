package com.gradion.studio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    private final JdbcTemplate jdbcTemplate;
    private final CurrentUser currentUser;
    private final ProjectFiles projectFiles;
    private final PipelineService pipelineService;
    private final TransactionTemplate transactions;

    public ProjectController(
            JdbcTemplate jdbcTemplate,
            CurrentUser currentUser,
            ProjectFiles projectFiles,
            PipelineService pipelineService,
            TransactionTemplate transactions) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUser = currentUser;
        this.projectFiles = projectFiles;
        this.pipelineService = pipelineService;
        this.transactions = transactions;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        Optional<CurrentUser.User> user = currentUser.find(request);
        if (user.isEmpty()) return unauthorized();
        List<ProjectRow> projects = jdbcTemplate.query(
                "select id, title, created_at from projects where owner_id = ? order by created_at desc",
                (resultSet, rowNum) -> new ProjectRow(resultSet.getString("id"), resultSet.getString("title"), resultSet.getTimestamp("created_at")),
                user.get().id());
        return ResponseEntity.ok(projects.stream().map(this::summary).toList());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> create(
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String bookText,
            @RequestParam(required = false) MultipartFile file,
            HttpServletRequest request) {
        Optional<CurrentUser.User> user = currentUser.find(request);
        if (user.isEmpty()) return unauthorized();

        String projectTitle = title == null ? "" : title.trim();
        if (projectTitle.isBlank()) return badRequest("Enter a project title.");
        if (projectTitle.length() > 200) return badRequest("Project title is too long.");

        String pastedText = bookText == null ? "" : bookText;
        boolean hasPastedText = !pastedText.isBlank();
        String uploadedText;
        try {
            uploadedText = uploadedText(file);
        } catch (InvalidUploadException exception) {
            return badRequest(exception.getMessage());
        } catch (IOException exception) {
            return ResponseEntity.internalServerError().body(Map.of("message", "Could not read the uploaded book."));
        }
        boolean hasFile = uploadedText != null;
        if (hasPastedText == hasFile) {
            return badRequest(hasPastedText
                    ? "Provide either pasted book text or a .txt file, not both."
                    : "Provide pasted book text or a non-empty .txt file.");
        }

        String projectId = UUID.randomUUID().toString();
        String canonicalBookText = hasPastedText ? pastedText : uploadedText;
        try {
            projectFiles.writeBook(projectId, canonicalBookText);
            transactions.executeWithoutResult(status -> {
                jdbcTemplate.update("insert into projects (id, owner_id, title) values (?, ?, ?)", projectId, user.get().id(), projectTitle);
                pipelineService.seed(projectId);
            });
        } catch (IOException | DataAccessException exception) {
            try {
                projectFiles.deleteProject(projectId);
            } catch (IOException ignored) {
                // The next local cleanup can safely remove this unreferenced directory.
            }
            return ResponseEntity.internalServerError().body(Map.of("message", "Could not create the project."));
        }

        Timestamp createdAt = jdbcTemplate.queryForObject("select created_at from projects where id = ?", Timestamp.class, projectId);
        return ResponseEntity.status(201).body(detail(projectId, projectTitle, createdAt, canonicalBookText));
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<?> detail(@PathVariable String projectId, HttpServletRequest request) {
        Optional<CurrentUser.User> user = currentUser.find(request);
        if (user.isEmpty()) return unauthorized();
        Optional<ProjectRow> project = jdbcTemplate.query(
                "select id, title, created_at from projects where id = ? and owner_id = ?",
                (resultSet, rowNum) -> new ProjectRow(resultSet.getString("id"), resultSet.getString("title"), resultSet.getTimestamp("created_at")),
                projectId, user.get().id()).stream().findFirst();
        if (project.isEmpty()) return ResponseEntity.notFound().build();

        try {
            ProjectRow row = project.get();
            return ResponseEntity.ok(detail(row.id(), row.title(), row.createdAt(), projectFiles.readBook(row.id())));
        } catch (IOException | IllegalArgumentException exception) {
            return ResponseEntity.internalServerError().body(Map.of("message", "Book text is unavailable."));
        }
    }

    private String uploadedText(MultipartFile file) throws IOException, InvalidUploadException {
        if (file == null) return null;
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".txt") || file.isEmpty()) {
            throw new InvalidUploadException("Upload a non-empty .txt file.");
        }
        String text = new String(file.getBytes(), StandardCharsets.UTF_8);
        if (text.isBlank()) throw new InvalidUploadException("Upload a non-empty .txt file.");
        return text;
    }

    private ProjectSummary summary(ProjectRow project) {
        PipelineService.ProjectPipeline pipeline = pipelineService.pipeline(project.id());
        return new ProjectSummary(project.id(), project.title(), project.createdAt().toInstant(), pipeline.status(), pipeline.completedSteps(), pipeline.totalSteps());
    }

    private ProjectDetail detail(String id, String title, Timestamp createdAt, String bookText) {
        PipelineService.ProjectPipeline pipeline = pipelineService.pipeline(id);
        return new ProjectDetail(id, title, createdAt.toInstant(), pipeline.status(), pipeline.completedSteps(), pipeline.totalSteps(), bookText, pipeline.steps());
    }

    private ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("message", "No active session."));
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    private record ProjectRow(String id, String title, Timestamp createdAt) { }
    private static class InvalidUploadException extends Exception {
        InvalidUploadException(String message) {
            super(message);
        }
    }
    private record ProjectSummary(String id, String title, Instant createdAt, String status, int completedSteps, int totalSteps) { }
    private record ProjectDetail(String id, String title, Instant createdAt, String status, int completedSteps, int totalSteps, String bookText, List<PipelineService.StepView> steps) { }
}
