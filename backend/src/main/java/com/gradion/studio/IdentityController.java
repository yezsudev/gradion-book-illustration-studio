package com.gradion.studio;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IdentityController {
    private static final String SESSION_COOKIE = CurrentUser.SESSION_COOKIE;
    private static final Duration SESSION_DURATION = Duration.ofDays(7);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUser currentUser;

    public IdentityController(JdbcTemplate jdbcTemplate, CurrentUser currentUser) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUser = currentUser;
    }

    @PostMapping("/api/session")
    @Transactional
    public ResponseEntity<?> signIn(@RequestBody IdentityRequest request, HttpServletResponse response) {
        String name = request.name() == null ? "" : request.name().trim();
        String email = request.email() == null ? "" : request.email().trim().toLowerCase(Locale.ROOT);
        if (name.isBlank() || name.length() > 100 || email.length() > 320 || !EMAIL_PATTERN.matcher(email).matches()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Enter a name and valid email."));
        }

        CurrentUser.User user = findOrCreateUser(name, email);
        String token = UUID.randomUUID().toString();
        jdbcTemplate.update("insert into sessions (token, user_id) values (?, ?)", token, user.id());
        addSessionCookie(response, token, SESSION_DURATION);
        return ResponseEntity.ok(new IdentityResponse(user.name(), user.email()));
    }

    @org.springframework.web.bind.annotation.GetMapping("/api/session")
    public ResponseEntity<?> currentSession(jakarta.servlet.http.HttpServletRequest request) {
        return currentUser.find(request)
                .<ResponseEntity<?>>map(user -> ResponseEntity.ok(new IdentityResponse(user.name(), user.email())))
                .orElseGet(() -> ResponseEntity.status(401).body(Map.of("message", "No active session.")));
    }

    @DeleteMapping("/api/session")
    public ResponseEntity<Void> signOut(jakarta.servlet.http.HttpServletRequest request, HttpServletResponse response) {
        currentUser.sessionToken(request).ifPresent(token -> jdbcTemplate.update("delete from sessions where token = ?", token));
        addSessionCookie(response, "", Duration.ZERO);
        return ResponseEntity.noContent().build();
    }

    private CurrentUser.User findOrCreateUser(String name, String email) {
        Optional<CurrentUser.User> existing = findUserByEmail(email);
        if (existing.isPresent()) return existing.get();

        try {
            jdbcTemplate.update("insert into users (name, email) values (?, ?)", name, email);
        } catch (DuplicateKeyException ignored) {
            // A concurrent request inserted the same normalized email first.
        }
        return findUserByEmail(email).orElseThrow();
    }

    private Optional<CurrentUser.User> findUserByEmail(String email) {
        return jdbcTemplate.query("select id, name, email from users where email = ?",
                (resultSet, rowNum) -> new CurrentUser.User(resultSet.getLong("id"), resultSet.getString("name"), resultSet.getString("email")),
                email).stream().findFirst();
    }

    private void addSessionCookie(HttpServletResponse response, String token, Duration maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(SESSION_COOKIE, token)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build()
                .toString());
    }

    private record IdentityRequest(String name, String email) { }
    private record IdentityResponse(String name, String email) { }
}
