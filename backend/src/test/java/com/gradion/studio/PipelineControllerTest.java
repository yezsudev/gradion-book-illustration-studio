package com.gradion.studio;

import java.util.List;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pipeline;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always",
        "gradion.projects.path=target/pipeline-project-files",
        "gradion.pipeline.instance-id=current-test-instance",
        "gradion.pipeline.stale-after=PT1S"
})
@AutoConfigureMockMvc
class PipelineControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void completesStyleAndMakesCharactersTheOnlyRunnableNextStep() throws Exception {
        Cookie owner = signIn("pipeline-order@example.com");
        String projectId = createProject(owner, "Ordered pipeline");

        mockMvc.perform(post("/api/projects/{id}/steps/CHARACTERS/run", projectId).cookie(owner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("STYLE must complete first."));

        mockMvc.perform(post("/api/projects/{id}/steps/STYLE/run", projectId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].key").value("STYLE"))
                .andExpect(jsonPath("$.steps[0].state").value("COMPLETED"))
                .andExpect(jsonPath("$.steps[1].key").value("CHARACTERS"))
                .andExpect(jsonPath("$.steps[1].canRun").value(true));

        mockMvc.perform(get("/api/projects/{id}", projectId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("In progress"))
                .andExpect(jsonPath("$.completedSteps").value(1));
    }

    @Test
    void completedStepCannotBeRunAgainAndOtherUsersCannotRunIt() throws Exception {
        Cookie owner = signIn("pipeline-owner@example.com");
        Cookie otherUser = signIn("pipeline-other@example.com");
        String projectId = createProject(owner, "Owned pipeline");

        mockMvc.perform(post("/api/projects/{id}/steps/STYLE/run", projectId).cookie(owner))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/projects/{id}/steps/STYLE/run", projectId).cookie(owner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("STYLE is already completed."));
        mockMvc.perform(post("/api/projects/{id}/steps/CHARACTERS/run", projectId).cookie(otherUser))
                .andExpect(status().isNotFound());
    }

    @Test
    void retryingFailedStepLeavesCompletedPreviousStepAlone() throws Exception {
        Cookie owner = signIn("pipeline-retry@example.com");
        String projectId = createProject(owner, "Retry pipeline");

        mockMvc.perform(post("/api/projects/{id}/steps/STYLE/run", projectId).cookie(owner))
                .andExpect(status().isOk());
        jdbcTemplate.update("update project_steps set state = 'FAILED', error_message = 'Temporary failure' where project_id = ? and step_key = 'CHARACTERS'", projectId);

        mockMvc.perform(post("/api/projects/{id}/steps/CHARACTERS/run", projectId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].state").value("COMPLETED"))
                .andExpect(jsonPath("$.steps[1].state").value("COMPLETED"));
    }

    @Test
    void recoversOnlyStaleExecutionFromAnOlderServerInstance() throws Exception {
        Cookie owner = signIn("pipeline-recovery@example.com");
        String projectId = createProject(owner, "Recovered pipeline");
        markRunning(projectId, "old-server-instance", "old-token", "DATEADD('MINUTE', -2, CURRENT_TIMESTAMP)");

        mockMvc.perform(post("/api/projects/{id}/steps/STYLE/recover", projectId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].state").value("FAILED"))
                .andExpect(jsonPath("$.steps[0].canRetry").value(true));
    }

    @Test
    void doesNotRecoverFreshOrCurrentServerExecution() throws Exception {
        Cookie owner = signIn("pipeline-current@example.com");
        String currentProject = createProject(owner, "Current execution pipeline");
        markRunning(currentProject, "current-test-instance", "current-token", "DATEADD('MINUTE', -2, CURRENT_TIMESTAMP)");

        mockMvc.perform(post("/api/projects/{id}/steps/STYLE/recover", currentProject).cookie(owner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This server still owns the running step."));

        String freshProject = createProject(owner, "Fresh execution pipeline");
        markRunning(freshProject, "old-server-instance", "fresh-token", "CURRENT_TIMESTAMP");
        mockMvc.perform(post("/api/projects/{id}/steps/STYLE/recover", freshProject).cookie(owner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The running step is not stale yet."));
    }

    private Cookie signIn(String email) throws Exception {
        var response = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Pipeline Tester\",\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        return new Cookie("gradion_session", response.getCookie("gradion_session").getValue());
    }

    private String createProject(Cookie owner, String title) throws Exception {
        mockMvc.perform(multipart("/api/projects").param("title", title).param("bookText", "A saved book.").cookie(owner))
                .andExpect(status().isCreated());
        return jdbcTemplate.queryForObject("select id from projects where title = ?", String.class, title);
    }

    private void markRunning(String projectId, String instanceId, String token, String startedAt) {
        jdbcTemplate.update("update project_steps set state = 'RUNNING', run_token = ?, executor_instance_id = ?, started_at = " + startedAt + " where project_id = ? and step_key = 'STYLE'", token, instanceId, projectId);
    }
}
