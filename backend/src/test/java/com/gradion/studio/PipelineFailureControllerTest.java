package com.gradion.studio;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pipeline-failure;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always",
        "gradion.projects.path=target/pipeline-failure-project-files",
        "gradion.pipeline.fake-outcome=failure"
})
@AutoConfigureMockMvc
class PipelineFailureControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void failedStepIsRetryableWithoutChangingPreviousCompletedSteps() throws Exception {
        Cookie owner = signIn();
        String projectId = createProject(owner);

        mockMvc.perform(post("/api/projects/{id}/steps/STYLE/run", projectId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].state").value("FAILED"))
                .andExpect(jsonPath("$.steps[0].canRetry").value(true))
                .andExpect(jsonPath("$.steps[1].canRun").value(false));
        String firstToken = jdbcTemplate.queryForObject("select run_token from project_steps where project_id = ? and step_key = 'STYLE'", String.class, projectId);

        mockMvc.perform(post("/api/projects/{id}/steps/STYLE/run", projectId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].state").value("FAILED"));
        String retryToken = jdbcTemplate.queryForObject("select run_token from project_steps where project_id = ? and step_key = 'STYLE'", String.class, projectId);
        assertThat(retryToken).isNotEqualTo(firstToken);
    }

    private Cookie signIn() throws Exception {
        var response = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Failure Tester\",\"email\":\"pipeline-failure@example.com\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        return new Cookie("gradion_session", response.getCookie("gradion_session").getValue());
    }

    private String createProject(Cookie owner) throws Exception {
        mockMvc.perform(multipart("/api/projects").param("title", "Failure pipeline").param("bookText", "A saved book.").cookie(owner))
                .andExpect(status().isCreated());
        return jdbcTemplate.queryForObject("select id from projects where title = 'Failure pipeline'", String.class);
    }
}
