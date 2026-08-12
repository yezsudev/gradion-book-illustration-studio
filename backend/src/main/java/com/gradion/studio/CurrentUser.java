package com.gradion.studio;

import java.util.Optional;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class CurrentUser {
    static final String SESSION_COOKIE = "gradion_session";

    private final JdbcTemplate jdbcTemplate;

    CurrentUser(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Optional<User> find(HttpServletRequest request) {
        return sessionToken(request).flatMap(token -> jdbcTemplate.query(
                "select u.id, u.name, u.email from sessions s join users u on u.id = s.user_id where s.token = ?",
                (resultSet, rowNum) -> new User(resultSet.getLong("id"), resultSet.getString("name"), resultSet.getString("email")),
                token).stream().findFirst());
    }

    Optional<String> sessionToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE.equals(cookie.getName())) return Optional.of(cookie.getValue());
        }
        return Optional.empty();
    }

    record User(long id, String name, String email) { }
}
