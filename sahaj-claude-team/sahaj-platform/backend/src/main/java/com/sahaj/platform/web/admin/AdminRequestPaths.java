package com.sahaj.platform.web.admin;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.web.util.UrlPathHelper;

/**
 * Path rules shared by the admin filters. The path is the decoded lookup path Spring MVC itself uses
 * ({@link UrlPathHelper}), so a percent-encoded or disguised path cannot dodge a filter.
 */
public final class AdminRequestPaths {

    public static final String BASE = "/api/v1/admin";
    static final String LOGIN = BASE + "/auth/login";
    static final String ENROL = BASE + "/auth/totp/enrol";
    static final String VERIFY = BASE + "/auth/totp/verify";
    static final String REFRESH = BASE + "/auth/refresh";

    /** The four endpoints that take no access token. */
    static final Set<String> PUBLIC_POSTS = Set.of(LOGIN, ENROL, VERIFY, REFRESH);

    private static final UrlPathHelper HELPER = UrlPathHelper.defaultInstance;

    private AdminRequestPaths() {
    }

    public static String lookupPath(HttpServletRequest request) {
        return HELPER.getLookupPathForRequest(request);
    }

    static boolean isPublicPost(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && PUBLIC_POSTS.contains(lookupPath(request));
    }
}
