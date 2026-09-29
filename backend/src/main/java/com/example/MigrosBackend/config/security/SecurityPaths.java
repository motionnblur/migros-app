package com.example.MigrosBackend.config.security;

import java.util.Set;

/**
 * Route fragments owned by a single place so the servlet filter and the
 * security chain cannot drift apart on which requests are authentication
 * bootstrap endpoints and which subtree is administrator-scoped.
 */
public final class SecurityPaths {

    /** Administrator route subtree root. */
    public static final String ADMIN_ROOT = "/admin";

    public static final String ADMIN_LOGIN = "/admin/login";
    public static final String ADMIN_LOGOUT = "/admin/logout";
    public static final String USER_LOGIN = "/user/login";
    public static final String USER_LOGOUT = "/user/logout";

    /**
     * Authentication bootstrap endpoints the JWT filter must not inspect: a
     * stale session cookie presented on a login request must not authenticate
     * the call.
     */
    public static final Set<String> FILTER_SKIPPED_PATHS = Set.of(ADMIN_LOGIN, USER_LOGIN);

    private SecurityPaths() {
    }
}
