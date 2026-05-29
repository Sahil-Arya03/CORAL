package org.example.coral.sync;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

/**
 * Thin wrapper around the GitHub REST API v3.
 * Returns domain records ready for DB upsert — callers never see raw JSON.
 */
@Component
class GitHubApiClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubApiClient.class);
    private static final String BASE_URL = "https://api.github.com";
    private static final int PAGE_SIZE = 100;

    private final GitHubProperties props;
    private final RestClient http;

    GitHubApiClient(GitHubProperties props) {
        this.props = props;
        this.http = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /**
     * Fetch one page of commits for a repo.
     * @param since  only commits at or after this instant; null fetches all
     * @param page   1-based page number
     */
    List<CommitItem> fetchCommits(String owner, String repo, Instant since, int page) {
        try {
            String uri = "/repos/{owner}/{repo}/commits?per_page={ps}&page={pg}"
                    + (since != null ? "&since={since}" : "");
            List<CommitResponse> raw = http.get()
                    .uri(uri, owner, repo, PAGE_SIZE, page, since != null ? since.toString() : "")
                    .header("Authorization", "Bearer " + props.token())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (raw == null) return List.of();
            return raw.stream().map(GitHubApiClient::toCommitItem).toList();
        } catch (Exception e) {
            log.warn("GitHub commits fetch failed ({}/{}): {}", owner, repo, e.getMessage());
            return List.of();
        }
    }

    /**
     * Fetch one page of pull requests sorted by updated_at DESC.
     * Callers stop paging when all items on a page predate their since timestamp.
     */
    List<PullItem> fetchPulls(String owner, String repo, int page) {
        try {
            List<PullResponse> raw = http.get()
                    .uri("/repos/{owner}/{repo}/pulls?state=all&sort=updated&direction=desc&per_page={ps}&page={pg}",
                            owner, repo, PAGE_SIZE, page)
                    .header("Authorization", "Bearer " + props.token())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (raw == null) return List.of();
            return raw.stream().map(r -> toPullItem(owner, repo, r)).toList();
        } catch (Exception e) {
            log.warn("GitHub pulls fetch failed ({}/{}): {}", owner, repo, e.getMessage());
            return List.of();
        }
    }

    // ── Domain records returned to callers ───────────────────────────────────

    record CommitItem(String sha, String repo, String author, String message, Instant committedAt) {}

    record PullItem(String id, String repo, String title, String state,
                    Instant createdAt, Instant mergedAt, Instant updatedAt) {}

    // ── Private JSON response DTOs ────────────────────────────────────────────

    private record CommitResponse(
            String sha,
            CommitData commit,
            LoginInfo author          // nullable — unlinked GitHub account
    ) {}

    private record CommitData(CommitAuthor author, String message) {}

    private record CommitAuthor(String name, String date) {}

    private record LoginInfo(String login) {}

    private record PullResponse(
            long id,
            int number,
            String title,
            String state,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("merged_at")  String mergedAt,
            @JsonProperty("updated_at") String updatedAt
    ) {}

    // ── Mappers ───────────────────────────────────────────────────────────────

    private static CommitItem toCommitItem(CommitResponse r) {
        String author = (r.author() != null) ? r.author().login() : r.commit().author().name();
        Instant date  = parseInstant(r.commit().author().date());
        return new CommitItem(r.sha(), null, author, r.commit().message(), date);
    }

    private static PullItem toPullItem(String owner, String repo, PullResponse r) {
        String id = owner + "/" + repo + "#" + r.number();
        return new PullItem(id, owner + "/" + repo, r.title(), r.state(),
                parseInstant(r.createdAt()), parseInstant(r.mergedAt()), parseInstant(r.updatedAt()));
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (Exception e) { return null; }
    }
}
