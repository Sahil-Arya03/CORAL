package org.example.coral.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private static final Logger log = LoggerFactory.getLogger(UserRepository.class);

    private final NamedParameterJdbcTemplate jdbc;

    public UserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Upsert a Clerk-authenticated user. On first sign-in a new row is inserted
     * and a BIGSERIAL id is assigned. Subsequent sign-ins update email/username
     * in case they changed in Clerk. Returns the internal DB id.
     *
     * Passwords are never received or stored — Clerk holds them encrypted
     * on their servers.
     */
    public long upsert(String clerkId, String email, String username, String displayName) {
        try {
            Long id = jdbc.queryForObject("""
                    INSERT INTO creatoros.users (clerk_id, email, username, display_name)
                    VALUES (:clerkId, :email, :username, :displayName)
                    ON CONFLICT (clerk_id) DO UPDATE
                        SET email        = EXCLUDED.email,
                            username     = EXCLUDED.username,
                            display_name = EXCLUDED.display_name
                    RETURNING id
                    """,
                    new MapSqlParameterSource()
                            .addValue("clerkId",     clerkId)
                            .addValue("email",       email)
                            .addValue("username",    username)
                            .addValue("displayName", displayName),
                    Long.class);
            return id == null ? -1L : id;
        } catch (Exception e) {
            log.error("user upsert failed for clerkId={}: {}", clerkId, e.getMessage());
            return -1L;
        }
    }
}
