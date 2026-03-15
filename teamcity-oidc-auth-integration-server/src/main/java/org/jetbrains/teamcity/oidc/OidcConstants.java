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
    public static final String LOGIN_PATH             = "/app/oidc/login";
    public static final String CALLBACK_PATH          = "/app/oidc/callback";
    public static final String BACKCHANNEL_LOGOUT_PATH = "/app/oidc/backchannel-logout";

    // OIDC Back-Channel Logout event identifier (RFC 8935)
    public static final String BACKCHANNEL_LOGOUT_EVENT = "http://schemas.openid.net/event/backchannel-logout";

    // UserEx attribute key used to persist the OIDC subject identifier on the TC user record
    public static final String OIDC_SUB_ATTRIBUTE = "oidc.sub";

    // Config file name (relative to TeamCity config directory)
    public static final String CONFIG_FILE_NAME = "oidc-auth-plugin.json";

    // HTTP session attribute keys
    public static final String SESSION_STATE        = "oidc.state";
    public static final String SESSION_NONCE        = "oidc.nonce";
    public static final String SESSION_REDIRECT_URL = "oidc.redirectUrl";
    // Stored at login to support per-request token introspection (opt-in via feature flag)
    public static final String SESSION_ACCESS_TOKEN = "oidc.access.token";
    public static final String SESSION_USER_ID      = "oidc.user.id";

    // OIDC well-known discovery path suffix
    public static final String DISCOVERY_PATH = "/.well-known/openid-configuration";

    // Default configuration values
    public static final List<String> DEFAULT_SCOPES      = Arrays.asList("openid", "email", "profile");
    public static final String DEFAULT_GROUPS_CLAIM       = "groups";
    public static final int    DEFAULT_HTTP_TIMEOUT_SECONDS = 30;
    public static final int    DEFAULT_CLOCK_SKEW_SECONDS   = 30;
    public static final String DEFAULT_LOGIN_BUTTON_LABEL   = "Log in with OIDC";
}
