package org.jetbrains.teamcity.oidc;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class RedirectUtil {

    private RedirectUtil() {}

    /**
     * Strips CR and LF characters to prevent HTTP response splitting.
     */
    @NotNull
    public static String stripCrlf(@NotNull String value) {
        return value.replaceAll("[\r\n]", "");
    }

    /**
     * Returns the path unchanged if it is a safe relative path, or {@code null} otherwise.
     * Rejects absolute URLs (open redirect) and strips CRLF (response splitting).
     * A safe relative path must start with {@code /} but not {@code //} (protocol-relative).
     */
    @Nullable
    public static String sanitizeRedirectPath(@Nullable String path) {
        if (path == null || path.isEmpty()) return null;
        String sanitized = stripCrlf(path);
        if (sanitized.contains("\\")) return null;
        if (!sanitized.startsWith("/") || sanitized.startsWith("//")) return null;
        return sanitized;
    }
}
