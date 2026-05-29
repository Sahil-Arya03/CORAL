package org.example.coral.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Binding for coral.github.* properties. Set token and repos in
 * application-local.properties (git-ignored). The sync layer skips
 * gracefully when token is blank or repos is empty.
 */
@ConfigurationProperties(prefix = "coral.github")
public record GitHubProperties(
        String token,
        List<String> repos
) {
    public GitHubProperties {
        if (repos == null) repos = List.of();
    }

    public boolean isConfigured() {
        return token != null && !token.isBlank() && !repos.isEmpty();
    }
}
