package com.gradion.studio;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class PipelineService {
    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);
    enum StepKey {
        STYLE(1), CHARACTERS(2), PORTRAITS(3), CHAPTERS(4), ILLUSTRATIONS(5);

        final int position;

        StepKey(int position) {
            this.position = position;
        }

        static StepKey parse(String value) {
            try {
                return StepKey.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }
    }

    enum State { PENDING, RUNNING, COMPLETED, FAILED }

    record StepView(String key, State state, boolean canRun, boolean canRetry, boolean canRecover, String error) { }
    record ProjectPipeline(String status, int completedSteps, int totalSteps, List<StepView> steps, String style, List<CharacterView> characters) { }
    record Result(ProjectPipeline pipeline, String message, boolean notFound) {
        static Result success(ProjectPipeline pipeline) { return new Result(pipeline, null, false); }
        static Result conflict(String message) { return new Result(null, message, false); }
        static Result missing() { return new Result(null, null, true); }
    }

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;
    private final FakePipelineExecutor executor;
    private final GeminiGateway gemini;
    private final ImageGenerationGateway imageGateway;
    private final ProjectFiles projectFiles;
    private final ObjectMapper json;
    private final Duration staleAfter;
    private final String instanceId;

    PipelineService(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactions,
            FakePipelineExecutor executor,
            GeminiGateway gemini,
            ImageGenerationGateway imageGateway,
            ProjectFiles projectFiles,
            ObjectMapper json,
            @Value("${gradion.pipeline.stale-after:PT5M}") Duration staleAfter,
            @Value("${gradion.pipeline.instance-id:}") String configuredInstanceId) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = transactions;
        this.executor = executor;
        this.gemini = gemini;
        this.imageGateway = imageGateway;
        this.projectFiles = projectFiles;
        this.json = json;
        this.staleAfter = staleAfter;
        this.instanceId = configuredInstanceId.isBlank() ? UUID.randomUUID().toString() : configuredInstanceId;
    }

    void seed(String projectId) {
        for (StepKey step : StepKey.values()) {
            jdbcTemplate.update("insert into project_steps (project_id, step_key, position, state) values (?, ?, ?, 'PENDING')",
                    projectId, step.name(), step.position);
        }
    }

    ProjectPipeline pipeline(String projectId) {
        List<StepRow> rows = load(projectId);
        int completed = (int) rows.stream().filter(row -> row.state == State.COMPLETED).count();
        StepRow current = rows.stream().filter(row -> row.state != State.COMPLETED).findFirst().orElse(null);
        Instant staleBefore = Instant.now().minus(staleAfter);
        List<StepView> steps = new ArrayList<>();
        for (StepRow row : rows) {
            boolean currentStep = row == current;
            boolean recoverable = row.state == State.RUNNING && row.executorInstanceId != null
                    && !instanceId.equals(row.executorInstanceId) && row.startedAt != null && row.startedAt.toInstant().isBefore(staleBefore);
            steps.add(new StepView(row.key.name(), row.state,
                    currentStep && row.state == State.PENDING,
                    currentStep && row.state == State.FAILED,
                    recoverable,
                    row.error));
        }
        String status = completed == 0 ? "Draft" : completed == StepKey.values().length ? "Done" : "In progress";
        String style = jdbcTemplate.queryForObject("select style from projects where id = ?", String.class, projectId);
        List<CharacterView> characters = jdbcTemplate.query("select id, name, prompt, portrait_status, portrait_path, portrait_error from characters where project_id = ? order by position",
                (resultSet, rowNum) -> new CharacterView(resultSet.getString("id"), resultSet.getString("name"), resultSet.getString("prompt"),
                        resultSet.getString("portrait_status"), resultSet.getString("portrait_path") == null ? null : "/api/projects/" + projectId + "/media/" + resultSet.getString("id"), resultSet.getString("portrait_error")), projectId);
        return new ProjectPipeline(status, completed, StepKey.values().length, steps, style, characters);
    }

    Result run(long ownerId, String projectId, StepKey requested, String suppliedStyle) {
        Claim claim = transactions.execute(status -> claim(ownerId, projectId, requested));
        if (claim.notFound) return Result.missing();
        if (claim.message != null) return Result.conflict(claim.message);

        try {
            execute(projectId, requested, suppliedStyle);
            finish(projectId, requested, claim.runToken, State.COMPLETED, null);
        } catch (RuntimeException exception) {
            log.warn("Pipeline step {} failed for project {} ({}): {}", requested, projectId,
                    exception.getClass().getSimpleName(), exception.getMessage());
            finish(projectId, requested, claim.runToken, State.FAILED, "Pipeline execution failed.");
        }
        return Result.success(pipeline(projectId));
    }

    private void execute(String projectId, StepKey step, String suppliedStyle) {
        if (step == StepKey.STYLE) {
            String style = suppliedStyle == null ? "" : suppliedStyle.trim();
            if (!style.isBlank()) {
                jdbcTemplate.update("update projects set style = ?, gemini_style_interaction_id = null where id = ?", style, projectId);
                return;
            }
            ProjectContext context = ensureBookContext(projectId);
            GeminiGateway.Interaction result = gemini.generateStyle(context.rootInteractionId);
            if (result.text() == null || result.text().isBlank()) throw new IllegalStateException("Gemini returned no style.");
            jdbcTemplate.update("update projects set style = ?, gemini_style_interaction_id = ? where id = ?", result.text().trim(), result.id(), projectId);
            return;
        }
        if (step == StepKey.CHARACTERS) {
            ProjectContext context = ensureStyleContext(projectId);
            GeminiGateway.Interaction result = gemini.generateCharacters(context.styleInteractionId);
            List<CharacterInput> characters = parseCharacters(result.text());
            transactions.executeWithoutResult(status -> {
                jdbcTemplate.update("delete from characters where project_id = ?", projectId);
                for (int index = 0; index < characters.size(); index++) {
                    CharacterInput character = characters.get(index);
                    jdbcTemplate.update("insert into characters (id, project_id, position, name, prompt) values (?, ?, ?, ?, ?)",
                            UUID.randomUUID().toString(), projectId, index + 1, character.name, character.prompt);
                }
                jdbcTemplate.update("update projects set gemini_characters_interaction_id = ? where id = ?", result.id(), projectId);
            });
            return;
        }
        if (step == StepKey.PORTRAITS) {
            List<CharacterRow> characters = jdbcTemplate.query("select id, name, prompt, portrait_status, portrait_path, portrait_interaction_id from characters where project_id = ? order by position",
                    (resultSet, rowNum) -> new CharacterRow(resultSet.getString("id"), resultSet.getString("name"), resultSet.getString("prompt"), resultSet.getString("portrait_status"), resultSet.getString("portrait_path"), resultSet.getString("portrait_interaction_id")), projectId);
            if (characters.isEmpty()) throw new IllegalStateException("Characters are unavailable.");
            for (CharacterRow character : characters) {
                if ("COMPLETED".equals(character.portraitStatus) && character.portraitPath != null) {
                    continue;
                }
                jdbcTemplate.update("update characters set portrait_status = 'RUNNING', portrait_error = null where id = ?", character.id);
                try {
                    ImageGenerationGateway.ImageResult result = imageGateway.generatePortrait(character.name, character.prompt);
                    projectFiles.writePortrait(projectId, character.id, result.bytes());
                    jdbcTemplate.update("update characters set portrait_status = 'COMPLETED', portrait_path = ?, portrait_error = null, portrait_generated_at = current_timestamp, portrait_interaction_id = ? where id = ?",
                            "portraits/" + character.id + ".png", result.id(), character.id);
                } catch (RuntimeException | java.io.IOException exception) {
                    jdbcTemplate.update("update characters set portrait_status = 'FAILED', portrait_error = ? where id = ?", safeError(exception), character.id);
                    throw exception instanceof RuntimeException runtime ? runtime : new IllegalStateException("Could not persist portrait.", exception);
                }
            }
            return;
        }
        executor.execute();
    }

    private ProjectContext ensureStyleContext(String projectId) {
        ProjectContext context = ensureBookContext(projectId);
        if (context.style == null || context.style.isBlank()) throw new IllegalStateException("Style is unavailable.");
        if (context.styleInteractionId != null && gemini.isAvailable(context.file(), context.styleInteractionId)) return context;
        GeminiGateway.Interaction styleContext = gemini.createStyleContext(context.rootInteractionId, context.style);
        jdbcTemplate.update("update projects set gemini_style_interaction_id = ? where id = ?", styleContext.id(), projectId);
        return loadProjectContext(projectId);
    }

    private ProjectContext ensureBookContext(String projectId) {
        ProjectContext context = loadProjectContext(projectId);
        if (context.file() != null && context.rootInteractionId != null && gemini.isAvailable(context.file(), context.rootInteractionId)) return context;
        GeminiGateway.FileReference file = gemini.uploadBook(projectFiles.bookPath(projectId));
        jdbcTemplate.update("update projects set gemini_file_name = ?, gemini_file_uri = ?, gemini_root_interaction_id = null, gemini_style_interaction_id = null where id = ?",
                file.name(), file.uri(), projectId);
        GeminiGateway.Interaction root = gemini.createBookContext(file);
        jdbcTemplate.update("update projects set gemini_root_interaction_id = ? where id = ?", root.id(), projectId);
        return loadProjectContext(projectId);
    }

    private ProjectContext loadProjectContext(String projectId) {
        return jdbcTemplate.queryForObject("select style, gemini_file_name, gemini_file_uri, gemini_root_interaction_id, gemini_style_interaction_id, gemini_characters_interaction_id from projects where id = ?",
                (resultSet, rowNum) -> new ProjectContext(resultSet.getString("style"), resultSet.getString("gemini_file_name"), resultSet.getString("gemini_file_uri"),
                        resultSet.getString("gemini_root_interaction_id"), resultSet.getString("gemini_style_interaction_id"), resultSet.getString("gemini_characters_interaction_id")), projectId);
    }

    private List<CharacterInput> parseCharacters(String text) {
        try {
            JsonNode parsed = json.readTree(stripJsonFence(text == null ? "" : text));
            if (parsed.isTextual()) parsed = json.readTree(stripJsonFence(parsed.asText()));
            if (parsed.isTextual()) parsed = json.readTree(stripJsonFence(parsed.asText()));
            JsonNode characters = parsed.isArray() ? parsed : parsed.path("characters");
            if (!characters.isArray() || characters.size() < 1 || characters.size() > 2) throw new IllegalStateException("Gemini returned invalid characters.");
            List<CharacterInput> result = new ArrayList<>();
            for (JsonNode character : characters) {
                String name = character.path("name").asText().trim();
                String prompt = character.path("prompt").asText();
                if (prompt.isBlank()) prompt = character.path("portrait_prompt").asText();
                prompt = prompt.trim();
                if (!character.path("adult").asBoolean(false) || name.isBlank() || prompt.isBlank()) throw new IllegalStateException("Gemini returned invalid characters.");
                result.add(new CharacterInput(name, prompt));
            }
            return result;
        } catch (Exception exception) {
            String detail = text == null ? "" : text.replaceAll("[\\r\\n]+", " ").trim();
            if (detail.length() > 300) detail = detail.substring(0, 300) + "…";
            throw new IllegalStateException("Gemini returned invalid characters: " + detail, exception);
        }
    }

    private String stripJsonFence(String value) {
        String trimmed = value.trim();
        if (!trimmed.startsWith("```")) return trimmed;
        int firstLine = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        return firstLine >= 0 && lastFence > firstLine ? trimmed.substring(firstLine + 1, lastFence).trim() : trimmed;
    }

    Result recover(long ownerId, String projectId, StepKey requested) {
        Result result = transactions.execute(status -> recoverInTransaction(ownerId, projectId, requested));
        return result == null ? Result.conflict("Could not recover the running step.") : result;
    }

    private Claim claim(long ownerId, String projectId, StepKey requested) {
        if (!ownsAndLocks(ownerId, projectId)) return Claim.missing();
        List<StepRow> rows = load(projectId);
        StepRow target = find(rows, requested);
        if (target == null) return Claim.conflict("Unknown pipeline step.");
        if (target.state == State.COMPLETED) return Claim.conflict(requested.name() + " is already completed.");

        StepRow current = rows.stream().filter(row -> row.state != State.COMPLETED).findFirst().orElse(null);
        if (current == null) return Claim.conflict(requested.name() + " is already completed.");
        if (current.state == State.RUNNING) return Claim.conflict(current.key.name() + " is already running.");
        if (current.key != requested) return Claim.conflict(current.key.name() + " must complete first.");

        String runToken = UUID.randomUUID().toString();
        int changed = jdbcTemplate.update("update project_steps set state = 'RUNNING', run_token = ?, executor_instance_id = ?, started_at = current_timestamp, finished_at = null, error_message = null where project_id = ? and step_key = ? and state in ('PENDING', 'FAILED')",
                runToken, instanceId, projectId, requested.name());
        return changed == 1 ? Claim.claimed(runToken) : Claim.conflict(requested.name() + " is already running.");
    }

    private Result recoverInTransaction(long ownerId, String projectId, StepKey requested) {
        if (!ownsAndLocks(ownerId, projectId)) return Result.missing();
        StepRow target = find(load(projectId), requested);
        if (target == null || target.state != State.RUNNING) return Result.conflict(requested.name() + " is not running.");
        if (target.executorInstanceId == null) return Result.conflict("The running step has no executor identity.");
        if (instanceId.equals(target.executorInstanceId)) return Result.conflict("This server still owns the running step.");
        if (target.startedAt == null || !target.startedAt.toInstant().isBefore(Instant.now().minus(staleAfter))) {
            return Result.conflict("The running step is not stale yet.");
        }
        jdbcTemplate.update("update project_steps set state = 'FAILED', finished_at = current_timestamp, error_message = ? where project_id = ? and step_key = ? and state = 'RUNNING' and run_token = ?",
                "Execution was interrupted by a previous server instance.", projectId, requested.name(), target.runToken);
        return Result.success(pipeline(projectId));
    }

    private void finish(String projectId, StepKey step, String runToken, State state, String error) {
        transactions.executeWithoutResult(status -> jdbcTemplate.update(
                "update project_steps set state = ?, finished_at = current_timestamp, error_message = ? where project_id = ? and step_key = ? and state = 'RUNNING' and run_token = ?",
                state.name(), error, projectId, step.name(), runToken));
    }

    private boolean ownsAndLocks(long ownerId, String projectId) {
        return !jdbcTemplate.query("select id from projects where id = ? and owner_id = ? for update",
                (resultSet, rowNum) -> resultSet.getString("id"), projectId, ownerId).isEmpty();
    }

    private List<StepRow> load(String projectId) {
        return jdbcTemplate.query("select step_key, position, state, run_token, executor_instance_id, started_at, error_message from project_steps where project_id = ? order by position",
                (resultSet, rowNum) -> new StepRow(
                        StepKey.valueOf(resultSet.getString("step_key")),
                        State.valueOf(resultSet.getString("state")),
                        resultSet.getString("run_token"),
                        resultSet.getString("executor_instance_id"),
                        resultSet.getTimestamp("started_at"),
                        resultSet.getString("error_message")), projectId);
    }

    private StepRow find(List<StepRow> rows, StepKey key) {
        return rows.stream().filter(row -> row.key == key).findFirst().orElse(null);
    }

    private record StepRow(StepKey key, State state, String runToken, String executorInstanceId, Timestamp startedAt, String error) { }
    private String safeError(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "Portrait generation failed." : message.substring(0, Math.min(500, message.length()));
    }

    private record ProjectContext(String style, String fileName, String fileUri, String rootInteractionId, String styleInteractionId, String charactersInteractionId) {
        GeminiGateway.FileReference file() {
            return fileName == null || fileUri == null ? null : new GeminiGateway.FileReference(fileName, fileUri);
        }
    }
    private record CharacterInput(String name, String prompt) { }
    private record CharacterRow(String id, String name, String prompt, String portraitStatus, String portraitPath, String portraitInteractionId) { }
    record CharacterView(String id, String name, String prompt, String portraitStatus, String portraitUrl, String portraitError) { }
    private record Claim(String runToken, String message, boolean notFound) {
        static Claim claimed(String runToken) { return new Claim(runToken, null, false); }
        static Claim conflict(String message) { return new Claim(null, message, false); }
        static Claim missing() { return new Claim(null, null, true); }
    }
}
