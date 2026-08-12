package com.gradion.studio;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class PipelineService {
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
    record ProjectPipeline(String status, int completedSteps, int totalSteps, List<StepView> steps) { }
    record Result(ProjectPipeline pipeline, String message, boolean notFound) {
        static Result success(ProjectPipeline pipeline) { return new Result(pipeline, null, false); }
        static Result conflict(String message) { return new Result(null, message, false); }
        static Result missing() { return new Result(null, null, true); }
    }

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;
    private final FakePipelineExecutor executor;
    private final Duration staleAfter;
    private final String instanceId;

    PipelineService(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactions,
            FakePipelineExecutor executor,
            @Value("${gradion.pipeline.stale-after:PT5M}") Duration staleAfter,
            @Value("${gradion.pipeline.instance-id:}") String configuredInstanceId) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = transactions;
        this.executor = executor;
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
        return new ProjectPipeline(status, completed, StepKey.values().length, steps);
    }

    Result run(long ownerId, String projectId, StepKey requested) {
        Claim claim = transactions.execute(status -> claim(ownerId, projectId, requested));
        if (claim.notFound) return Result.missing();
        if (claim.message != null) return Result.conflict(claim.message);

        try {
            executor.execute();
            finish(projectId, requested, claim.runToken, State.COMPLETED, null);
        } catch (RuntimeException exception) {
            finish(projectId, requested, claim.runToken, State.FAILED, "Pipeline execution failed.");
        }
        return Result.success(pipeline(projectId));
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
    private record Claim(String runToken, String message, boolean notFound) {
        static Claim claimed(String runToken) { return new Claim(runToken, null, false); }
        static Claim conflict(String message) { return new Claim(null, message, false); }
        static Claim missing() { return new Claim(null, null, true); }
    }
}
