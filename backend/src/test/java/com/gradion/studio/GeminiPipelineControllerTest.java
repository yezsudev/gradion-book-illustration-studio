package com.gradion.studio;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:gemini-pipeline;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always",
        "gradion.projects.path=target/gemini-pipeline-project-files"
})
@AutoConfigureMockMvc
class GeminiPipelineControllerTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockBean private GeminiGateway geminiGateway;

    @Test
    void suppliedStyleCompletesWithoutCallingGeminiAndIsReturnedInProjectDetail() throws Exception {
        Cookie owner = signIn("custom-style@example.com");
        String projectId = createProject(owner, "Custom style");

        mockMvc.perform(post("/api/projects/{id}/steps/STYLE/run", projectId).cookie(owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"style\":\"Watercolor storybook\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].state").value("COMPLETED"));

        mockMvc.perform(get("/api/projects/{id}", projectId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.style").value("Watercolor storybook"));
        verify(geminiGateway, never()).uploadBook(any());
    }

    @Test
    void generatedStyleIsPersistedWithItsInteraction() throws Exception {
        Cookie owner = signIn("generated-style@example.com");
        String projectId = createProject(owner, "Generated style");
        stubFreshBookContext();
        when(geminiGateway.generateStyle("root-1")).thenReturn(new GeminiGateway.Interaction("style-1", "Soft watercolor with warm paper texture."));

        mockMvc.perform(post("/api/projects/{id}/steps/STYLE/run", projectId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].state").value("COMPLETED"));

        assertThat(jdbcTemplate.queryForObject("select style from projects where id = ?", String.class, projectId))
                .isEqualTo("Soft watercolor with warm paper texture.");
        assertThat(jdbcTemplate.queryForObject("select gemini_style_interaction_id from projects where id = ?", String.class, projectId))
                .isEqualTo("style-1");
    }

    @Test
    void charactersPersistOnlyOneOrTwoAdultEntries() throws Exception {
        Cookie owner = signIn("characters@example.com");
        String projectId = createProject(owner, "Characters");
        completeCustomStyle(owner, projectId);
        stubFreshBookContext();
        when(geminiGateway.createStyleContext("root-1", "Ink illustration")).thenReturn(new GeminiGateway.Interaction("style-context-1", "Ready."));
        when(geminiGateway.generateCharacters("style-context-1")).thenReturn(new GeminiGateway.Interaction("characters-1", """
                {"characters":[
                  {"name":"Mole","prompt":"An adult mole in a waistcoat","adult":true},
                  {"name":"Rat","prompt":"An adult water vole in a boating jacket","adult":true}
                ]}
                """));

        mockMvc.perform(post("/api/projects/{id}/steps/CHARACTERS/run", projectId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[1].state").value("COMPLETED"));

        mockMvc.perform(get("/api/projects/{id}", projectId).cookie(owner))
                .andExpect(jsonPath("$.characters.length()").value(2))
                .andExpect(jsonPath("$.characters[0].name").value("Mole"));
    }

    @Test
    void charactersReuseAStillAvailableGeneratedStyleContext() throws Exception {
        Cookie owner = signIn("reused-context@example.com");
        String projectId = createProject(owner, "Reused context");
        stubFreshBookContext();
        when(geminiGateway.generateStyle("root-1")).thenReturn(new GeminiGateway.Interaction("style-1", "Watercolor"));

        mockMvc.perform(post("/api/projects/{id}/steps/STYLE/run", projectId).cookie(owner)).andExpect(status().isOk());
        when(geminiGateway.isAvailable(any(), anyString())).thenReturn(true);
        when(geminiGateway.generateCharacters("style-1")).thenReturn(new GeminiGateway.Interaction("characters-1", "{\"characters\":[{\"name\":\"Mole\",\"prompt\":\"An adult mole\",\"adult\":true}]}"));

        mockMvc.perform(post("/api/projects/{id}/steps/CHARACTERS/run", projectId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.characters[0].name").value("Mole"));

        verify(geminiGateway, times(1)).uploadBook(any());
        verify(geminiGateway, never()).createStyleContext(anyString(), anyString());
    }

    @Test
    void charactersRebuildExpiredRemoteContextFromThePersistedStyle() throws Exception {
        Cookie owner = signIn("expired-context@example.com");
        String projectId = createProject(owner, "Expired context");
        stubFreshBookContext();
        when(geminiGateway.generateStyle("root-1")).thenReturn(new GeminiGateway.Interaction("style-1", "Watercolor"));
        when(geminiGateway.createStyleContext("root-1", "Watercolor")).thenReturn(new GeminiGateway.Interaction("rebuilt-style-1", "Ready"));
        when(geminiGateway.generateCharacters("rebuilt-style-1")).thenReturn(new GeminiGateway.Interaction("characters-1", "{\"characters\":[{\"name\":\"Mole\",\"prompt\":\"An adult mole\",\"adult\":true}]}"));

        mockMvc.perform(post("/api/projects/{id}/steps/STYLE/run", projectId).cookie(owner)).andExpect(status().isOk());
        mockMvc.perform(post("/api/projects/{id}/steps/CHARACTERS/run", projectId).cookie(owner)).andExpect(status().isOk());

        verify(geminiGateway, times(2)).uploadBook(any());
        verify(geminiGateway).createStyleContext("root-1", "Watercolor");
    }

    @Test
    void invalidCharacterOutputFailsAndExplicitRetryCanLaterSucceed() throws Exception {
        Cookie owner = signIn("retry-characters@example.com");
        String projectId = createProject(owner, "Retry characters");
        completeCustomStyle(owner, projectId);
        stubFreshBookContext();
        when(geminiGateway.createStyleContext("root-1", "Ink illustration")).thenReturn(new GeminiGateway.Interaction("style-context-1", "Ready."));
        when(geminiGateway.generateCharacters("style-context-1"))
                .thenReturn(new GeminiGateway.Interaction("bad-1", "{bad json"))
                .thenReturn(new GeminiGateway.Interaction("good-1", "{\"characters\":[{\"name\":\"Mole\",\"prompt\":\"An adult mole\",\"adult\":true}]}"));

        mockMvc.perform(post("/api/projects/{id}/steps/CHARACTERS/run", projectId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[1].state").value("FAILED"));
        mockMvc.perform(post("/api/projects/{id}/steps/CHARACTERS/run", projectId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[1].state").value("COMPLETED"));
    }

    @Test
    void childOrOversizedCharacterOutputFailsInsteadOfBeingTruncated() throws Exception {
        Cookie owner = signIn("invalid-characters@example.com");
        String projectId = createProject(owner, "Invalid characters");
        completeCustomStyle(owner, projectId);
        stubFreshBookContext();
        when(geminiGateway.createStyleContext("root-1", "Ink illustration")).thenReturn(new GeminiGateway.Interaction("style-context-1", "Ready."));
        when(geminiGateway.generateCharacters("style-context-1")).thenReturn(new GeminiGateway.Interaction("bad-1", """
                {"characters":[
                  {"name":"Child","prompt":"A child","adult":false},
                  {"name":"Two","prompt":"Adult two","adult":true},
                  {"name":"Three","prompt":"Adult three","adult":true}
                ]}
                """));

        mockMvc.perform(post("/api/projects/{id}/steps/CHARACTERS/run", projectId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[1].state").value("FAILED"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from characters where project_id = ?", Integer.class, projectId)).isZero();
    }

    @Test
    void duplicateCharacterRunInvokesGeminiOnce() throws Exception {
        Cookie owner = signIn("duplicate-characters@example.com");
        String projectId = createProject(owner, "Duplicate characters");
        completeCustomStyle(owner, projectId);
        stubFreshBookContext();
        when(geminiGateway.createStyleContext("root-1", "Ink illustration")).thenReturn(new GeminiGateway.Interaction("style-context-1", "Ready."));
        doAnswer(invocation -> { Thread.sleep(500); return new GeminiGateway.Interaction("characters-1", "{\"characters\":[{\"name\":\"Mole\",\"prompt\":\"An adult mole\",\"adult\":true}]}" ); })
                .when(geminiGateway).generateCharacters("style-context-1");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> first = executor.submit(() -> mockMvc.perform(post("/api/projects/{id}/steps/CHARACTERS/run", projectId).cookie(owner)).andReturn().getResponse().getStatus());
            waitUntilRunning(projectId, "CHARACTERS");
            mockMvc.perform(post("/api/projects/{id}/steps/CHARACTERS/run", projectId).cookie(owner)).andExpect(status().isConflict());
            assertThat(first.get()).isEqualTo(200);
            verify(geminiGateway, times(1)).generateCharacters("style-context-1");
        } finally {
            executor.shutdownNow();
        }
    }

    private void stubFreshBookContext() throws Exception {
        when(geminiGateway.isAvailable(any(), anyString())).thenReturn(false);
        when(geminiGateway.uploadBook(any())).thenReturn(new GeminiGateway.FileReference("files/book-1", "gemini://book-1"));
        when(geminiGateway.createBookContext(any())).thenReturn(new GeminiGateway.Interaction("root-1", "Book context ready."));
    }

    private void completeCustomStyle(Cookie owner, String projectId) throws Exception {
        mockMvc.perform(post("/api/projects/{id}/steps/STYLE/run", projectId).cookie(owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"style\":\"Ink illustration\"}"))
                .andExpect(status().isOk());
    }

    private void waitUntilRunning(String projectId, String step) throws InterruptedException {
        for (int attempt = 0; attempt < 80; attempt++) {
            String state = jdbcTemplate.queryForObject("select state from project_steps where project_id = ? and step_key = ?", String.class, projectId, step);
            if ("RUNNING".equals(state)) return;
            Thread.sleep(25);
        }
        throw new AssertionError("The first request did not claim the step.");
    }

    private Cookie signIn(String email) throws Exception {
        var response = mockMvc.perform(post("/api/session").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Gemini Tester\",\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        return new Cookie("gradion_session", response.getCookie("gradion_session").getValue());
    }

    private String createProject(Cookie owner, String title) throws Exception {
        mockMvc.perform(multipart("/api/projects").param("title", title).param("bookText", "A saved book.").cookie(owner))
                .andExpect(status().isCreated());
        return jdbcTemplate.queryForObject("select id from projects where title = ?", String.class, title);
    }
}
