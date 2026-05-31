package org.example.coral.config;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.JwkProviderBuilder;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URL;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Verifies the Clerk Bearer JWT on every /api/** request.
 *
 * On success: sets clerkUserId (String) and internalUserId (Long) as request attributes.
 * The internal ID is looked up (or auto-created) in creatoros.users via clerk_id.
 *
 * On failure: returns 401. OPTIONS preflight and /api/users/sync are exempt.
 *
 * When clerk.jwks-url is not configured, the filter is a no-op so the app still
 * starts and works in dev without Clerk credentials.
 */
@Component
public class ClerkAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ClerkAuthFilter.class);

    private static final List<String> EXEMPT_PATHS = List.of(
            "/api/users/sync"   // initial user creation — Clerk SDK sends JWT but we want this reachable always
    );

    @Value("${clerk.jwks-url:}")
    private String jwksUrl;

    private final NamedParameterJdbcTemplate jdbc;

    // Cached JWKS provider — built lazily the first time a request arrives
    private volatile JwkProvider jwkProvider;

    public ClerkAuthFilter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Skip CORS preflight
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        // Skip non-API paths
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) return true;
        // Explicit exemptions
        return EXEMPT_PATHS.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // If JWKS URL is not configured, inject a dev-mode identity so all endpoints
        // remain reachable without Clerk credentials — never do this in production.
        if (jwksUrl == null || jwksUrl.isBlank()) {
            request.setAttribute(SecurityUtils.ATTR_CLERK_USER_ID,    "dev_user");
            request.setAttribute(SecurityUtils.ATTR_INTERNAL_USER_ID, 1L);
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing Bearer token");
            return;
        }

        String token = header.substring(7);
        try {
            DecodedJWT decoded = verifyToken(token);
            String clerkUserId = decoded.getSubject();

            long internalId = resolveInternalUserId(clerkUserId);

            request.setAttribute(SecurityUtils.ATTR_CLERK_USER_ID,   clerkUserId);
            request.setAttribute(SecurityUtils.ATTR_INTERNAL_USER_ID, internalId);

            chain.doFilter(request, response);
        } catch (Exception e) {
            log.warn("JWT verification failed: {}", e.getMessage());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid token");
        }
    }

    // ── JWT verification ──────────────────────────────────────────────────────

    private DecodedJWT verifyToken(String token) throws Exception {
        DecodedJWT decoded = JWT.decode(token);
        JwkProvider provider = getProvider();
        Jwk jwk = provider.get(decoded.getKeyId());
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) jwk.getPublicKey(), null);
        return JWT.require(algorithm).build().verify(token);
    }

    private JwkProvider getProvider() throws Exception {
        if (jwkProvider == null) {
            synchronized (this) {
                if (jwkProvider == null) {
                    jwkProvider = new JwkProviderBuilder(new URL(jwksUrl))
                            .cached(10, 24, TimeUnit.HOURS)
                            .rateLimited(10, 1, TimeUnit.MINUTES)
                            .build();
                }
            }
        }
        return jwkProvider;
    }

    // ── User resolution ───────────────────────────────────────────────────────

    /**
     * Look up the internal BIGINT user ID by Clerk ID.
     * Auto-inserts a minimal row on first sign-in so all subsequent requests work.
     */
    private long resolveInternalUserId(String clerkId) {
        try {
            Long id = jdbc.queryForObject(
                    "SELECT id FROM creatoros.users WHERE clerk_id = :cid",
                    new MapSqlParameterSource("cid", clerkId),
                    Long.class);
            if (id != null) return id;
        } catch (Exception ignored) {}

        // First sign-in: auto-create with just the Clerk ID
        try {
            Long id = jdbc.queryForObject("""
                    INSERT INTO creatoros.users (clerk_id, email)
                    VALUES (:cid, :cid)
                    ON CONFLICT (clerk_id) DO UPDATE SET clerk_id = EXCLUDED.clerk_id
                    RETURNING id
                    """,
                    new MapSqlParameterSource("cid", clerkId),
                    Long.class);
            return id == null ? -1L : id;
        } catch (Exception e) {
            log.error("Failed to resolve/create user for clerkId={}: {}", clerkId, e.getMessage());
            return -1L;
        }
    }
}