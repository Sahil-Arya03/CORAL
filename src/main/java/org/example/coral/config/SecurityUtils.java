package org.example.coral.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Reads authentication context set by ClerkAuthFilter from request attributes. */
public final class SecurityUtils {

    public static final String ATTR_CLERK_USER_ID  = "clerkUserId";
    public static final String ATTR_INTERNAL_USER_ID = "internalUserId";

    private SecurityUtils() {}

    /** Returns the Clerk user ID string (e.g. "user_xxxxxxxxxx"). Throws 401 if absent. */
    public static String getClerkUserId(HttpServletRequest request) {
        Object val = request.getAttribute(ATTR_CLERK_USER_ID);
        if (val == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        return (String) val;
    }

    /** Returns the internal BIGINT user ID from creatoros.users. Throws 401 if absent. */
    public static long getInternalUserId(HttpServletRequest request) {
        Object val = request.getAttribute(ATTR_INTERNAL_USER_ID);
        if (val == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        return ((Number) val).longValue();
    }
}