package org.example.coral.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Shared Google OAuth 2.0 token manager for Gmail and Calendar adapters.
 *
 * Holds one cached access token and refreshes it automatically when it is
 * within 60 seconds of expiry. All callers receive the same token until it
 * needs refreshing — no concurrent refresh storms because the method is
 * synchronized.
 *
 * If credentials are not configured, getAccessToken() returns empty so callers
 * can skip gracefully without throwing.
 */
@Component
public class GoogleOAuthClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthClient.class);
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final int EXPIRY_BUFFER_SECONDS = 60;

    private final GoogleProperties props;
    private final RestClient http;

    private String cachedToken;
    private Instant tokenExpiry = Instant.EPOCH;

    GoogleOAuthClient(GoogleProperties props) {
        this.props = props;
        this.http  = RestClient.builder().build();
    }

    /**
     * Returns a valid Bearer access token, refreshing if needed.
     * Returns Optional.empty() when Google credentials are not configured.
     */
    public synchronized Optional<String> getAccessToken() {
        if (!props.isConfigured()) return Optional.empty();
        if (cachedToken != null && Instant.now().isBefore(tokenExpiry.minusSeconds(EXPIRY_BUFFER_SECONDS))) {
            return Optional.of(cachedToken);
        }
        return refresh();
    }

    /**
     * Refresh a per-user token using the global client credentials but a user-specific refresh token.
     * Returns the access token directly (not cached — callers store it themselves).
     */
    @SuppressWarnings("unchecked")
    public Optional<String> getUserAccessToken(String refreshToken) {
        if (!props.canOAuth() || refreshToken == null || refreshToken.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> body = http.post()
                    .uri(TOKEN_URL)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body("client_id="     + props.clientId()
                        + "&client_secret=" + props.clientSecret()
                        + "&refresh_token=" + refreshToken
                        + "&grant_type=refresh_token")
                    .retrieve()
                    .body(Map.class);
            if (body == null || !body.containsKey("access_token")) return Optional.empty();
            return Optional.of((String) body.get("access_token"));
        } catch (Exception e) {
            log.warn("Per-user Google token refresh failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Exchange an authorization code for tokens (used by the OAuth callback).
     * Returns a map with access_token, refresh_token, expires_in.
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> exchangeCode(String code, String redirectUri) {
        if (!props.canOAuth()) return Optional.empty();
        try {
            Map<String, Object> body = http.post()
                    .uri(TOKEN_URL)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body("client_id="     + props.clientId()
                        + "&client_secret=" + props.clientSecret()
                        + "&code="          + code
                        + "&redirect_uri="  + redirectUri
                        + "&grant_type=authorization_code")
                    .retrieve()
                    .body(Map.class);
            return Optional.ofNullable(body);
        } catch (Exception e) {
            log.error("Google code exchange failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<String> refresh() {
        try {
            Map<String, Object> body = http.post()
                    .uri(TOKEN_URL)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body("client_id="     + props.clientId()
                        + "&client_secret=" + props.clientSecret()
                        + "&refresh_token=" + props.refreshToken()
                        + "&grant_type=refresh_token")
                    .retrieve()
                    .body(Map.class);

            if (body == null || !body.containsKey("access_token")) {
                log.error("Google token refresh returned no access_token");
                return Optional.empty();
            }

            cachedToken  = (String) body.get("access_token");
            int expiresIn = body.containsKey("expires_in")
                    ? ((Number) body.get("expires_in")).intValue()
                    : 3600;
            tokenExpiry  = Instant.now().plusSeconds(expiresIn);

            log.debug("Google access token refreshed, expires in {}s", expiresIn);
            return Optional.of(cachedToken);
        } catch (Exception e) {
            log.error("Google token refresh failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}