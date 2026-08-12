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
import static org.mockito.ArgumentMatchers.eq;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

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
    @MockBean private ImageGenerationGateway imageGateway;

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

    @Test
    void portraitsAreBlockedUntilCharactersComplete() throws Exception {
        Cookie owner = signIn("portraits-order@example.com");
        String projectId = createProject(owner, "Portrait order");

        mockMvc.perform(post("/api/projects/{id}/steps/PORTRAITS/run", projectId).cookie(owner))
                .andExpect(status().isConflict());
        verify(imageGateway, never()).generatePortrait(anyString(), anyString());
    }

    @Test
    void chaptersAreBlockedUntilPortraitsComplete() throws Exception {
        Cookie owner = signIn("chapters-order@example.com");
        String projectId = createProject(owner, "Chapter order");
        completeCharacters(owner, projectId, "characters-chapter-order", "Mole");

        mockMvc.perform(post("/api/projects/{id}/steps/CHAPTERS/run", projectId).cookie(owner))
                .andExpect(status().isConflict());
        verify(geminiGateway, never()).generateChapter(anyString());
    }

    @Test
    void chapterOutputPersistsExactlyOneChapterAndIsReturnedInProjectDetail() throws Exception {
        Cookie owner = signIn("chapters-success@example.com");
        String projectId = createProject(owner, "Chapter success");
        completeCharactersAndPortraits(owner, projectId, "characters-chapter-success", "Mole");
        when(geminiGateway.isAvailable(any(), anyString())).thenReturn(true);
        when(geminiGateway.generateChapter("characters-chapter-success"))
                .thenReturn(new GeminiGateway.Interaction("chapter-1", "{\"chapters\":[{\"title\":\"The river crossing\",\"prompt\":\"Mole crosses the moonlit river.\"}]}"));

        mockMvc.perform(post("/api/projects/{id}/steps/CHAPTERS/run", projectId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[3].state").value("COMPLETED"))
                .andExpect(jsonPath("$.chapter.title").value("The river crossing"))
                .andExpect(jsonPath("$.chapter.prompt").value("Mole crosses the moonlit river."));
        assertThat(jdbcTemplate.queryForObject("select count(*) from chapters where project_id = ?", Integer.class, projectId)).isEqualTo(1);
        mockMvc.perform(get("/api/projects/{id}", projectId).cookie(owner))
                .andExpect(jsonPath("$.chapter.title").value("The river crossing"));
    }

    @Test
    void invalidChapterOutputFailsWithoutInventingOrTruncating() throws Exception {
        Cookie owner = signIn("chapters-invalid@example.com");
        String projectId = createProject(owner, "Chapter invalid");
        completeCharactersAndPortraits(owner, projectId, "characters-chapter-invalid", "Mole");
        when(geminiGateway.isAvailable(any(), anyString())).thenReturn(true);
        when(geminiGateway.generateChapter("characters-chapter-invalid"))
                .thenReturn(new GeminiGateway.Interaction("bad-1", "{\"chapters\":[]}"))
                .thenReturn(new GeminiGateway.Interaction("good-1", "{\"chapters\":[{\"title\":\"A scene\",\"prompt\":\"A prompt\"}]}"));

        mockMvc.perform(post("/api/projects/{id}/steps/CHAPTERS/run", projectId).cookie(owner))
                .andExpect(jsonPath("$.steps[3].state").value("FAILED"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from chapters where project_id = ?", Integer.class, projectId)).isZero();
        mockMvc.perform(post("/api/projects/{id}/steps/CHAPTERS/run", projectId).cookie(owner))
                .andExpect(jsonPath("$.steps[3].state").value("COMPLETED"));
    }

    @Test
    void blankChapterFieldsAndMultipleChaptersFail() throws Exception {
        Cookie owner = signIn("chapters-fields@example.com");
        String projectId = createProject(owner, "Chapter fields");
        completeCharactersAndPortraits(owner, projectId, "characters-chapter-fields", "Mole");
        when(geminiGateway.isAvailable(any(), anyString())).thenReturn(true);
        when(geminiGateway.generateChapter("characters-chapter-fields"))
                .thenReturn(new GeminiGateway.Interaction("bad-1", "{\"chapters\":[{\"title\":\"\",\"prompt\":\"scene\"}]}"))
                .thenReturn(new GeminiGateway.Interaction("bad-2", "{\"chapters\":[{\"title\":\"One\",\"prompt\":\"scene\"},{\"title\":\"Two\",\"prompt\":\"scene\"}]}"));

        mockMvc.perform(post("/api/projects/{id}/steps/CHAPTERS/run", projectId).cookie(owner))
                .andExpect(jsonPath("$.steps[3].state").value("FAILED"));
        mockMvc.perform(post("/api/projects/{id}/steps/CHAPTERS/run", projectId).cookie(owner))
                .andExpect(jsonPath("$.steps[3].state").value("FAILED"));
    }

    @Test
    void duplicateChapterRunInvokesGeminiOnceAndOwnershipIsEnforced() throws Exception {
        Cookie owner = signIn("chapters-duplicate@example.com");
        Cookie other = signIn("chapters-other@example.com");
        String projectId = createProject(owner, "Chapter duplicate");
        completeCharactersAndPortraits(owner, projectId, "characters-chapter-duplicate", "Mole");
        when(geminiGateway.isAvailable(any(), anyString())).thenReturn(true);
        doAnswer(invocation -> { Thread.sleep(500); return new GeminiGateway.Interaction("chapter-1", "{\"chapters\":[{\"title\":\"Scene\",\"prompt\":\"Prompt\"}]}" ); })
                .when(geminiGateway).generateChapter("characters-chapter-duplicate");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> first = executor.submit(() -> mockMvc.perform(post("/api/projects/{id}/steps/CHAPTERS/run", projectId).cookie(owner)).andReturn().getResponse().getStatus());
            waitUntilRunning(projectId, "CHAPTERS");
            mockMvc.perform(post("/api/projects/{id}/steps/CHAPTERS/run", projectId).cookie(owner)).andExpect(status().isConflict());
            mockMvc.perform(post("/api/projects/{id}/steps/CHAPTERS/run", projectId).cookie(other)).andExpect(status().isNotFound());
            assertThat(first.get()).isEqualTo(200);
            verify(geminiGateway, times(1)).generateChapter("characters-chapter-duplicate");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void portraitsPersistEachImageAndExposeAnAuthorizedUrl() throws Exception {
        Cookie owner = signIn("portraits-success@example.com");
        String projectId = createProject(owner, "Portrait success");
        completeCharacters(owner, projectId, "characters-1", "Mole", "Rat");
        String characterId = jdbcTemplate.queryForObject("select id from characters where project_id = ? and position = 1", String.class, projectId);
        when(geminiGateway.isAvailable(any(), anyString())).thenReturn(true);
        when(imageGateway.generatePortrait(eq("Mole"), anyString()))
                .thenReturn(new ImageGenerationGateway.ImageResult("portrait-1", "image/png", new byte[] { 1, 2, 3 }));
        when(imageGateway.generatePortrait(eq("Rat"), anyString()))
                .thenReturn(new ImageGenerationGateway.ImageResult("portrait-2", "image/png", new byte[] { 4, 5, 6 }));

        mockMvc.perform(post("/api/projects/{id}/steps/PORTRAITS/run", projectId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[2].state").value("COMPLETED"))
                .andExpect(jsonPath("$.characters[0].portraitStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.characters[0].portraitUrl").value("/api/projects/" + projectId + "/media/" + characterId));
        mockMvc.perform(get("/api/projects/{id}/media/{characterId}", projectId, characterId).cookie(owner))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[] { 1, 2, 3 }));
    }

    @Test
    void failedPortraitPreservesEarlierImageAndRetrySkipsIt() throws Exception {
        Cookie owner = signIn("portraits-retry@example.com");
        String projectId = createProject(owner, "Portrait retry");
        completeCharacters(owner, projectId, "characters-2", "Mole", "Rat");
        String firstId = jdbcTemplate.queryForObject("select id from characters where project_id = ? and position = 1", String.class, projectId);
        String secondId = jdbcTemplate.queryForObject("select id from characters where project_id = ? and position = 2", String.class, projectId);
        when(geminiGateway.isAvailable(any(), anyString())).thenReturn(true);
        when(imageGateway.generatePortrait(eq("Mole"), anyString()))
                .thenReturn(new ImageGenerationGateway.ImageResult("portrait-1", "image/png", new byte[] { 1 }));
        when(imageGateway.generatePortrait(eq("Rat"), anyString()))
                .thenThrow(new IllegalStateException("quota"))
                .thenReturn(new ImageGenerationGateway.ImageResult("portrait-2", "image/png", new byte[] { 2 }));

        mockMvc.perform(post("/api/projects/{id}/steps/PORTRAITS/run", projectId).cookie(owner))
                .andExpect(jsonPath("$.steps[2].state").value("FAILED"))
                .andExpect(jsonPath("$.characters[0].portraitStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.characters[1].portraitStatus").value("FAILED"));
        mockMvc.perform(post("/api/projects/{id}/steps/PORTRAITS/run", projectId).cookie(owner))
                .andExpect(jsonPath("$.steps[2].state").value("COMPLETED"));
        verify(imageGateway, times(1)).generatePortrait("Mole", "Mole prompt");
        verify(imageGateway, times(2)).generatePortrait(eq("Rat"), eq("Rat prompt"));
        assertThat(jdbcTemplate.queryForObject("select portrait_status from characters where id = ?", String.class, firstId)).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject("select portrait_status from characters where id = ?", String.class, secondId)).isEqualTo("COMPLETED");
    }

    @Test
    void portraitMediaIsOwnershipChecked() throws Exception {
        Cookie owner = signIn("portraits-owner@example.com");
        Cookie other = signIn("portraits-other@example.com");
        String projectId = createProject(owner, "Private portrait");
        completeCharacters(owner, projectId, "characters-3", "Mole");
        String characterId = jdbcTemplate.queryForObject("select id from characters where project_id = ?", String.class, projectId);
        when(geminiGateway.isAvailable(any(), anyString())).thenReturn(true);
        when(imageGateway.generatePortrait(eq("Mole"), anyString()))
                .thenReturn(new ImageGenerationGateway.ImageResult("portrait-3", "image/png", new byte[] { 9 }));
        mockMvc.perform(post("/api/projects/{id}/steps/PORTRAITS/run", projectId).cookie(owner));
        mockMvc.perform(get("/api/projects/{id}/media/{characterId}", projectId, characterId).cookie(other))
                .andExpect(status().isNotFound());
    }

    private void stubFreshBookContext() throws Exception {
        when(geminiGateway.isAvailable(any(), anyString())).thenReturn(false);
        when(geminiGateway.uploadBook(any())).thenReturn(new GeminiGateway.FileReference("files/book-1", "gemini://book-1"));
        when(geminiGateway.createBookContext(any())).thenReturn(new GeminiGateway.Interaction("root-1", "Book context ready."));
    }

    private void completeCharacters(Cookie owner, String projectId, String interactionId, String... names) throws Exception {
        completeCustomStyle(owner, projectId);
        stubFreshBookContext();
        when(geminiGateway.createStyleContext("root-1", "Ink illustration")).thenReturn(new GeminiGateway.Interaction("style-context-" + interactionId, "Ready."));
        StringBuilder output = new StringBuilder("{\"characters\":[");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) output.append(',');
            output.append("{\"name\":\"").append(names[i]).append("\",\"prompt\":\"").append(names[i]).append(" prompt\",\"adult\":true}");
        }
        output.append("]}");
        when(geminiGateway.generateCharacters("style-context-" + interactionId))
                .thenReturn(new GeminiGateway.Interaction(interactionId, output.toString()));
        mockMvc.perform(post("/api/projects/{id}/steps/CHARACTERS/run", projectId).cookie(owner))
                .andExpect(status().isOk());
    }

    private void completeCharactersAndPortraits(Cookie owner, String projectId, String interactionId, String... names) throws Exception {
        completeCharacters(owner, projectId, interactionId, names);
        when(imageGateway.generatePortrait(anyString(), anyString())).thenAnswer(invocation ->
                new ImageGenerationGateway.ImageResult("portrait-" + invocation.getArgument(0), "image/png", new byte[] { 1, 2, 3 }));
        mockMvc.perform(post("/api/projects/{id}/steps/PORTRAITS/run", projectId).cookie(owner)).andExpect(status().isOk());
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
