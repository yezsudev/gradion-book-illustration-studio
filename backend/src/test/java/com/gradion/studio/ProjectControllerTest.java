package com.gradion.studio;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:projects;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always",
        "gradion.projects.path=target/test-project-files"
})
@AutoConfigureMockMvc
class ProjectControllerTest {
    private static final Path PROJECTS_ROOT = Path.of("target", "test-project-files");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearProjectFiles() throws Exception {
        FileSystemUtils.deleteRecursively(PROJECTS_ROOT);
    }

    @Test
    void createsPastedBookProjectAndReadsItsStoredText() throws Exception {
        Cookie owner = signIn("paste-owner@example.com");

        mockMvc.perform(multipart("/api/projects")
                        .param("title", "The River Book")
                        .param("bookText", "Chapter one begins here.")
                        .cookie(owner))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("The River Book"))
                .andExpect(jsonPath("$.status").value("Draft"))
                .andExpect(jsonPath("$.completedSteps").value(0))
                .andExpect(jsonPath("$.totalSteps").value(5));

        String projectId = projectId("The River Book");
        assertThat(Files.readString(PROJECTS_ROOT.resolve(projectId).resolve("book.txt")))
                .isEqualTo("Chapter one begins here.");
        mockMvc.perform(get("/api/projects/{id}", projectId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookText").value("Chapter one begins here."));
    }

    @Test
    void createsProjectFromTxtUpload() throws Exception {
        Cookie owner = signIn("upload-owner@example.com");
        MockMultipartFile file = new MockMultipartFile("file", "book.txt", "text/plain",
                "Uploaded book text.".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/projects").file(file).param("title", "Uploaded Book").cookie(owner))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Uploaded Book"));

        String projectId = projectId("Uploaded Book");
        assertThat(Files.readString(PROJECTS_ROOT.resolve(projectId).resolve("book.txt")))
                .isEqualTo("Uploaded book text.");
    }

    @Test
    void rejectsInvalidProjectInputs() throws Exception {
        Cookie owner = signIn("invalid-project@example.com");
        MockMultipartFile pdf = new MockMultipartFile("file", "book.pdf", "application/pdf", "not text".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/projects").file(pdf).param("title", "Wrong file").cookie(owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Upload a non-empty .txt file."));
        mockMvc.perform(multipart("/api/projects").param("title", " ").param("bookText", "Book").cookie(owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Enter a project title."));
        mockMvc.perform(multipart("/api/projects").param("title", "No book").param("bookText", " ").cookie(owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Provide pasted book text or a non-empty .txt file."));
    }

    @Test
    void rejectsBothBookSources() throws Exception {
        Cookie owner = signIn("both-sources@example.com");
        MockMultipartFile file = new MockMultipartFile("file", "book.txt", "text/plain", "File book".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/projects").file(file).param("title", "Two books").param("bookText", "Pasted book").cookie(owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Provide either pasted book text or a .txt file, not both."));
    }

    @Test
    void scopesListsAndDetailsToTheCurrentOwner() throws Exception {
        Cookie ownerA = signIn("project-owner-a@example.com");
        Cookie ownerB = signIn("project-owner-b@example.com");
        createPastedProject(ownerA, "Private Book", "Only owner A can read this.");
        String projectId = projectId("Private Book");

        mockMvc.perform(get("/api/projects").cookie(ownerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Private Book"));
        mockMvc.perform(get("/api/projects").cookie(ownerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get("/api/projects/{id}", projectId).cookie(ownerB))
                .andExpect(status().isNotFound());
    }

    @Test
    void reportsMissingStoredBookTextWithoutLeakingFilesystemDetails() throws Exception {
        Cookie owner = signIn("missing-book@example.com");
        createPastedProject(owner, "Missing Book", "This file will be removed.");
        String projectId = projectId("Missing Book");
        Files.delete(PROJECTS_ROOT.resolve(projectId).resolve("book.txt"));

        mockMvc.perform(get("/api/projects/{id}", projectId).cookie(owner))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Book text is unavailable."));
    }

    private Cookie signIn(String email) throws Exception {
        var response = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Project Tester\",\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        return new Cookie("gradion_session", response.getCookie("gradion_session").getValue());
    }

    private void createPastedProject(Cookie owner, String title, String bookText) throws Exception {
        mockMvc.perform(multipart("/api/projects").param("title", title).param("bookText", bookText).cookie(owner))
                .andExpect(status().isCreated());
    }

    private String projectId(String title) {
        return jdbcTemplate.queryForObject("select id from projects where title = ?", String.class, title);
    }
}
