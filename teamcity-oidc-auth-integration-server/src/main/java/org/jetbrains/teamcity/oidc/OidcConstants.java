package org.jetbrains.teamcity.oidc;

import java.util.Arrays;
import java.util.List;

public final class OidcConstants {

    private OidcConstants() {}

    // Auth scheme identifiers
    public static final String AUTH_SCHEME_NAME = "OpenID Connect";
    public static final String AUTH_SCHEME_TYPE = "oidc";
    public static final String AUTH_SCHEME_DESCRIPTION = "Authenticate using an external OpenID Connect identity provider";

    // URL paths registered by this plugin
    public static final String LOGIN_PATH    = "/app/oidc/login";
    public static final String CALLBACK_PATH = "/app/oidc/callback";

    // Config file name (relative to TeamCity config directory)
    public static final String CONFIG_FILE_NAME = "oidc-auth-plugin.json";

    // HTTP session attribute keys
    public static final String SESSION_STATE        = "oidc.state";
    public static final String SESSION_NONCE        = "oidc.nonce";
    public static final String SESSION_REDIRECT_URL = "oidc.redirectUrl";

    // OIDC well-known discovery path suffix
    public static final String DISCOVERY_PATH = "/.well-known/openid-configuration";

    // Default configuration values
    public static final List<String> DEFAULT_SCOPES      = Arrays.asList("openid", "email", "profile");
    public static final String DEFAULT_GROUPS_CLAIM       = "groups";
    public static final int    DEFAULT_HTTP_TIMEOUT_SECONDS = 30;
    public static final int    DEFAULT_CLOCK_SKEW_SECONDS   = 30;
}
