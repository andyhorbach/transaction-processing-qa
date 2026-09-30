package io.github.andyhorbach.txnqa.auth;

import io.github.andyhorbach.txnqa.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/**
 * Resolves the synthetic bearer token to a seeded user. This is deliberately
 * not a production auth mechanism — the QA focus of this system is resource
 * ownership (R-16, R-17), not credential management.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String USER_ATTRIBUTE = "authenticatedUser";

    private final JdbcClient jdbc;

    public AuthInterceptor(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw ApiException.unauthorized();
        }
        String token = header.substring("Bearer ".length()).trim();
        AuthenticatedUser user = jdbc.sql("SELECT id, name FROM app_user WHERE token = :token")
                .param("token", token)
                .query((rs, rowNum) -> new AuthenticatedUser(rs.getObject("id", UUID.class), rs.getString("name")))
                .optional()
                .orElseThrow(ApiException::unauthorized);
        request.setAttribute(USER_ATTRIBUTE, user);
        return true;
    }
}
