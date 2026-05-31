package org.example.coral.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Notion OAuth 2.0 client — mirrors GoogleOAuthClient for the Notion integration.
 *
 * Notion's token endpoint uses HTTP Basic auth (Base64 clientId:clientSecret)
 * with a JSON body, unlike Google's form-encoded approach.
 *
 * The authorization URL includes state=notion so the frontend can distinguish
 * this callback from Google's ?code= redirect (both land on /integrations).
 */
@Component
public class NotionOAuthClient {

    private static final Logger log = LoggerFactory.getLogger(NotionOAuthClient.class);
    private static final String AUTHORIZE_URL  = "https://api.notion.com/v1/oauth/authorize";
    private static final String TOKEN_URL      = "https://api.notion.com/v1/oauth/token";
    private static final String NOTION_VERSION = "2022-06-28";

    private final NotionProperties props;
    private final RestClient http;

    NotionOAuthClient(NotionProperties props) {
        this.props = props;
        this.http  = RestClient.builder()
                .defaultHeader("Notion-Version", NOTION_VERSION)
                .build();
    }

    public boolean canOAuth() {
        return props.canOAuth();
    }

    /**
     * Builds the Notion consent URL.
     * state=notion lets the frontend distinguish this callback from Google's ?code= redirect.
     */
    public String getAuthorizationUrl(String redirectUri) {
        return AUTHORIZE_URL
                + "?client_id="    + encode(props.clientId())
                + "&response_type=code"
                + "&owner=user"
                + "&redirect_uri=" + encode(redirectUri)
                + "&state=notion";
    }

    /**
     * Exchanges an authorization code for a Notion access token.
     *
     * Response contains: access_token, token_type, bot_id, workspace_id,
     * workspace_name, workspace_icon, owner.
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> exchangeCode(String code, String redirectUri) {
        if (!props.canOAuth() || code == null || code.isBlank()) return Optional.empty();
        try {
            String credentials = Base64.getEncoder().encodeToString(
                    (props.clientId() + ":" + props.clientSecret())
                            .getBytes(StandardCharsets.UTF_8));

            Map<String, Object> resp = http.post()
                    .uri(TOKEN_URL)
                    .header("Authorization", "Basic " + credentials)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "grant_type",   "authorization_code",
                            "code",         code,
                            "redirect_uri", redirectUri))
                    .retrieve()
                    .body(Map.class);

            return Optional.ofNullable(resp);
        } catch (Exception e) {
            log.error("Notion code exchange failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
