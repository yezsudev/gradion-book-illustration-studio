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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:identity;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always"
})
@AutoConfigureMockMvc
class IdentityControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsUserAndRestoresThemFromTheSessionCookie() throws Exception {
        var signIn = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mira Hassan\",\"email\":\"mira@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mira Hassan"))
                .andExpect(jsonPath("$.email").value("mira@example.com"))
                .andExpect(cookie().httpOnly("gradion_session", true))
                .andReturn();

        String sessionToken = signIn.getResponse().getCookie("gradion_session").getValue();

        mockMvc.perform(get("/api/session").cookie(new Cookie("gradion_session", sessionToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mira Hassan"));
    }

    @Test
    void reusesTheExistingUserForTheSameEmail() throws Exception {
        mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mira Hassan\",\"email\":\"repeat@example.com\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated Name\",\"email\":\"REPEAT@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mira Hassan"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from users where email = 'repeat@example.com'", Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsMissingNameAndInvalidEmail() throws Exception {
        mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Enter a name and valid email."));
    }

    @Test
    void scopesProjectListToAnActiveSessionAndSignOutRevokesIt() throws Exception {
        var signIn = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Project User\",\"email\":\"projects@example.com\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String sessionToken = signIn.getResponse().getCookie("gradion_session").getValue();
        Cookie sessionCookie = new Cookie("gradion_session", sessionToken);

        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/projects").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        mockMvc.perform(delete("/api/session").cookie(sessionCookie))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("gradion_session", 0));

        mockMvc.perform(get("/api/session").cookie(sessionCookie))
                .andExpect(status().isUnauthorized());
    }
}
