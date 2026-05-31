package org.example.coral.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binding for coral.notion.* properties.
 *
 * OAuth flow (preferred): set clientId + clientSecret from the Notion integration's
 * OAuth page; users connect via the Integrations UI.
 *
 * Legacy global sync (optional): set token + databaseId to sync one shared workspace
 * on a schedule using an internal integration token.
 */
@ConfigurationProperties(prefix = "coral.notion")
public record NotionProperties(
        String token,         // legacy integration token (coral.notion.token)
        String databaseId,    // legacy database ID     (coral.notion.database-id)
        String clientId,      // OAuth client ID         (coral.notion.client-id)
        String clientSecret   // OAuth client secret     (coral.notion.client-secret)
) {
    /** True when legacy global-sync credentials are fully configured. */
    public boolean isConfigured() {
        return token      != null && !token.isBlank()
            && databaseId != null && !databaseId.isBlank();
    }

    /** True when OAuth app credentials are present — sufficient for per-user OAuth flow. */
    public boolean canOAuth() {
        return clientId     != null && !clientId.isBlank()
            && clientSecret != null && !clientSecret.isBlank();
    }
}