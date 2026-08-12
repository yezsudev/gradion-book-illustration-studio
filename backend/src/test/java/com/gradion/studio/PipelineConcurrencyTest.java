package com.gradion.studio;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pipeline-concurrency;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always",
        "gradion.projects.path=target/pipeline-concurrency-project-files",
        "gradion.pipeline.fake-delay=PT2S"
})
@AutoConfigureMockMvc
class PipelineConcurrencyTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void concurrentRunRequestsAllowOnlyOneClaim() throws Exception {
        Cookie owner = signIn();
        String projectId = createProject(owner);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> first = executor.submit(() -> mockMvc.perform(post("/api/projects/{id}/steps/STYLE/run", projectId).cookie(owner))
                    .andReturn().getResponse().getStatus());
            waitUntilRunning(projectId);

            mockMvc.perform(post("/api/projects/{id}/steps/STYLE/run", projectId).cookie(owner))
                    .andExpect(status().isConflict());

            assertThat(first.get()).isEqualTo(200);
            assertThat(jdbcTemplate.queryForObject("select state from project_steps where project_id = ? and step_key = 'STYLE'", String.class, projectId))
                    .isEqualTo("COMPLETED");
        } finally {
            executor.shutdownNow();
        }
    }

    private void waitUntilRunning(String projectId) throws InterruptedException {
        for (int attempt = 0; attempt < 80; attempt++) {
            String state = jdbcTemplate.queryForObject("select state from project_steps where project_id = ? and step_key = 'STYLE'", String.class, projectId);
            if ("RUNNING".equals(state)) return;
            Thread.sleep(25);
        }
        throw new AssertionError("The first request did not claim STYLE.");
    }

    private Cookie signIn() throws Exception {
        var response = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Concurrency Tester\",\"email\":\"pipeline-concurrency@example.com\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        return new Cookie("gradion_session", response.getCookie("gradion_session").getValue());
    }

    private String createProject(Cookie owner) throws Exception {
        mockMvc.perform(multipart("/api/projects").param("title", "Concurrent pipeline").param("bookText", "A saved book.").cookie(owner))
                .andExpect(status().isCreated());
        return jdbcTemplate.queryForObject("select id from projects where title = 'Concurrent pipeline'", String.class);
    }
}
