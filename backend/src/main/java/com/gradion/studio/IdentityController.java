package com.gradion.studio;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IdentityController {
    private static final String SESSION_COOKIE = "gradion_session";
    private static final Duration SESSION_DURATION = Duration.ofDays(7);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final JdbcTemplate jdbcTemplate;

    public IdentityController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/api/session")
    @Transactional
    public ResponseEntity<?> signIn(@RequestBody IdentityRequest request, HttpServletResponse response) {
        String name = request.name() == null ? "" : request.name().trim();
        String email = request.email() == null ? "" : request.email().trim().toLowerCase(Locale.ROOT);
        if (name.isBlank() || name.length() > 100 || email.length() > 320 || !EMAIL_PATTERN.matcher(email).matches()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Enter a name and valid email."));
        }

        User user = findOrCreateUser(name, email);
        String token = UUID.randomUUID().toString();
        jdbcTemplate.update("insert into sessions (token, user_id) values (?, ?)", token, user.id());
        addSessionCookie(response, token, SESSION_DURATION);
        return ResponseEntity.ok(new IdentityResponse(user.name(), user.email()));
    }

    @GetMapping("/api/session")
    public ResponseEntity<?> currentSession(HttpServletRequest request) {
        return currentUser(request)
                .<ResponseEntity<?>>map(user -> ResponseEntity.ok(new IdentityResponse(user.name(), user.email())))
                .orElseGet(() -> ResponseEntity.status(401).body(Map.of("message", "No active session.")));
    }

    @DeleteMapping("/api/session")
    public ResponseEntity<Void> signOut(HttpServletRequest request, HttpServletResponse response) {
        sessionToken(request).ifPresent(token -> jdbcTemplate.update("delete from sessions where token = ?", token));
        addSessionCookie(response, "", Duration.ZERO);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/projects")
    public ResponseEntity<?> projects(HttpServletRequest request) {
        if (currentUser(request).isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("message", "No active session."));
        }
        return ResponseEntity.ok(java.util.List.of());
    }

    private User findOrCreateUser(String name, String email) {
        Optional<User> existing = findUserByEmail(email);
        if (existing.isPresent()) return existing.get();

        try {
            jdbcTemplate.update("insert into users (name, email) values (?, ?)", name, email);
        } catch (DuplicateKeyException ignored) {
            // A concurrent request inserted the same normalized email first.
        }
        return findUserByEmail(email).orElseThrow();
    }

    private Optional<User> currentUser(HttpServletRequest request) {
        return sessionToken(request).flatMap(token -> jdbcTemplate.query(
                "select u.id, u.name, u.email from sessions s join users u on u.id = s.user_id where s.token = ?",
                (resultSet, rowNum) -> new User(resultSet.getLong("id"), resultSet.getString("name"), resultSet.getString("email")),
                token).stream().findFirst());
    }

    private Optional<User> findUserByEmail(String email) {
        return jdbcTemplate.query("select id, name, email from users where email = ?",
                (resultSet, rowNum) -> new User(resultSet.getLong("id"), resultSet.getString("name"), resultSet.getString("email")),
                email).stream().findFirst();
    }

    private Optional<String> sessionToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE.equals(cookie.getName())) return Optional.of(cookie.getValue());
        }
        return Optional.empty();
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

    private record User(long id, String name, String email) { }
    private record IdentityRequest(String name, String email) { }
    private record IdentityResponse(String name, String email) { }
}
