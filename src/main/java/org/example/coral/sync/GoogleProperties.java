package org.example.coral.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binding for coral.google.* properties. Set all three fields in
 * application-local.properties (git-ignored). Both GmailApiClient and
 * CalendarApiClient share this single credential set.
 */
@ConfigurationProperties(prefix = "coral.google")
public record GoogleProperties(
        String clientId,
        String clientSecret,
        String refreshToken
) {
    /** True when all three fields are set — needed for the shared global token. */
    public boolean isConfigured() {
        return clientId     != null && !clientId.isBlank()
            && clientSecret != null && !clientSecret.isBlank()
            && refreshToken != null && !refreshToken.isBlank();
    }

    /** True when client credentials are present — sufficient for per-user OAuth flows. */
    public boolean canOAuth() {
        return clientId     != null && !clientId.isBlank()
            && clientSecret != null && !clientSecret.isBlank();
    }
}