package org.jetbrains.teamcity.oidc.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jetbrains.teamcity.oidc.OidcConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * Full plugin configuration. Deserialized from {@code oidc-auth-plugin.json}.
 * All fields are camelCase in JSON (user-authored file).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OidcPluginSettings {

    /** OIDC Issuer URL. Discovery document is fetched from {@code issuerUrl + "/.well-known/openid-configuration"}. */
    private String issuerUrl;

    /** When true (default), resolve endpoints from the discovery document. */
    private boolean discoveryEnabled = true;

    /** Authorization endpoint URL — required if discoveryEnabled is false. */
    private String authorizationEndpoint;

    /** Token endpoint URL — required if discoveryEnabled is false. */
    private String tokenEndpoint;

    /** UserInfo endpoint URL — optional. */
    private String userInfoEndpoint;

    /** JWKS endpoint URL — required if discoveryEnabled is false. */
    private String jwksUri;

    /** Token introspection endpoint URL — optional, used when discoveryEnabled is false. */
    private String introspectionEndpoint;

    /** OAuth 2.0 client ID. */
    private String clientId;

    /** OAuth 2.0 client secret (plaintext). */
    private String clientSecret;

    /** OAuth 2.0 scopes. Default: ["openid", "email", "profile"]. */
    private List<String> scopes = new ArrayList<>(OidcConstants.DEFAULT_SCOPES);

    /**
     * Base URL of this TeamCity instance as seen from the browser.
     * Used to build the redirect URI. When absent, derived from RootUrlHolder.
     */
    private String callbackBaseUrl;

    /** Auto-create TeamCity users on first OIDC login. Default false. */
    private boolean createUsersAutomatically = false;

    /** If non-empty, only emails from these domains may log in. */
    private List<String> allowedEmailDomains = new ArrayList<>();

    /** Sync user's TeamCity group membership from OIDC claims. Default false. */
    private boolean assignGroups = false;

    /** When true, remove user from TeamCity groups absent in the OIDC claim. Requires assignGroups=true. */
    private boolean removeUnassignedGroups = false;

    /** JWT/userinfo claim that holds the group list. Default "groups". */
    private String groupsClaimName = OidcConstants.DEFAULT_GROUPS_CLAIM;

    /** Claim mapping for TeamCity username. Default: SUB. */
    private OidcClaimMappingSettings usernameClaim = defaultSub();

    /** Claim mapping for user email. Default: CLAIM/email. */
    private OidcClaimMappingSettings emailClaim = defaultClaim("email");

    /** Claim mapping for user display name. Default: CLAIM/name. */
    private OidcClaimMappingSettings displayNameClaim = defaultClaim("name");

    /** Timeout in seconds for HTTP calls to the IdP. Default 30. */
    private int httpTimeoutSeconds = OidcConstants.DEFAULT_HTTP_TIMEOUT_SECONDS;

    /** Allowed clock skew in seconds when validating exp/iat. Default 30. */
    private int tokenClockSkewSeconds = OidcConstants.DEFAULT_CLOCK_SKEW_SECONDS;

    /** Label shown as the tooltip/alt text on the login page icon. Default: "Log in with OIDC". */
    private String loginButtonLabel = OidcConstants.DEFAULT_LOGIN_BUTTON_LABEL;

    /**
     * URL of the icon image shown on the login page.
     * May be absolute, root-relative, or a data URI.
     * When null, a built-in SVG is used.
     */
    private String loginButtonIconUrl;

    // ---- helpers -----------------------------------------------------------

    private static OidcClaimMappingSettings defaultSub() {
        OidcClaimMappingSettings s = new OidcClaimMappingSettings();
        s.setMappingType(OidcClaimMappingSettings.MappingType.SUB);
        return s;
    }

    private static OidcClaimMappingSettings defaultClaim(String claimName) {
        OidcClaimMappingSettings s = new OidcClaimMappingSettings();
        s.setMappingType(OidcClaimMappingSettings.MappingType.CLAIM);
        s.setClaimName(claimName);
        return s;
    }

    // ---- view helpers (for JSP) --------------------------------------------

    /** Returns {@link #allowedEmailDomains} as a comma-separated string for form rendering. */
    public String getAllowedEmailDomainsJoined() {
        return allowedEmailDomains == null ? "" : String.join(", ", allowedEmailDomains);
    }

    /** Returns {@link #scopes} as a space-separated string for form rendering. */
    public String getScopesJoined() {
        return scopes == null ? "" : String.join(" ", scopes);
    }

    // ---- getters / setters -------------------------------------------------

    public String getIssuerUrl() { return issuerUrl; }
    public void setIssuerUrl(String issuerUrl) { this.issuerUrl = issuerUrl; }

    public boolean isDiscoveryEnabled() { return discoveryEnabled; }
    public void setDiscoveryEnabled(boolean discoveryEnabled) { this.discoveryEnabled = discoveryEnabled; }

    public String getAuthorizationEndpoint() { return authorizationEndpoint; }
    public void setAuthorizationEndpoint(String authorizationEndpoint) { this.authorizationEndpoint = authorizationEndpoint; }

    public String getTokenEndpoint() { return tokenEndpoint; }
    public void setTokenEndpoint(String tokenEndpoint) { this.tokenEndpoint = tokenEndpoint; }

    public String getUserInfoEndpoint() { return userInfoEndpoint; }
    public void setUserInfoEndpoint(String userInfoEndpoint) { this.userInfoEndpoint = userInfoEndpoint; }

    public String getJwksUri() { return jwksUri; }
    public void setJwksUri(String jwksUri) { this.jwksUri = jwksUri; }

    public String getIntrospectionEndpoint() { return introspectionEndpoint; }
    public void setIntrospectionEndpoint(String introspectionEndpoint) { this.introspectionEndpoint = introspectionEndpoint; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

    public List<String> getScopes() { return scopes; }
    public void setScopes(List<String> scopes) { this.scopes = scopes; }

    public String getCallbackBaseUrl() { return callbackBaseUrl; }
    public void setCallbackBaseUrl(String callbackBaseUrl) { this.callbackBaseUrl = callbackBaseUrl; }

    public boolean isCreateUsersAutomatically() { return createUsersAutomatically; }
    public void setCreateUsersAutomatically(boolean createUsersAutomatically) { this.createUsersAutomatically = createUsersAutomatically; }

    public List<String> getAllowedEmailDomains() { return allowedEmailDomains; }
    public void setAllowedEmailDomains(List<String> allowedEmailDomains) { this.allowedEmailDomains = allowedEmailDomains; }

    public boolean isAssignGroups() { return assignGroups; }
    public void setAssignGroups(boolean assignGroups) { this.assignGroups = assignGroups; }

    public boolean isRemoveUnassignedGroups() { return removeUnassignedGroups; }
    public void setRemoveUnassignedGroups(boolean removeUnassignedGroups) { this.removeUnassignedGroups = removeUnassignedGroups; }

    public String getGroupsClaimName() { return groupsClaimName; }
    public void setGroupsClaimName(String groupsClaimName) { this.groupsClaimName = groupsClaimName; }

    public OidcClaimMappingSettings getUsernameClaim() { return usernameClaim; }
    public void setUsernameClaim(OidcClaimMappingSettings usernameClaim) { this.usernameClaim = usernameClaim; }

    public OidcClaimMappingSettings getEmailClaim() { return emailClaim; }
    public void setEmailClaim(OidcClaimMappingSettings emailClaim) { this.emailClaim = emailClaim; }

    public OidcClaimMappingSettings getDisplayNameClaim() { return displayNameClaim; }
    public void setDisplayNameClaim(OidcClaimMappingSettings displayNameClaim) { this.displayNameClaim = displayNameClaim; }

    public int getHttpTimeoutSeconds() { return httpTimeoutSeconds; }
    public void setHttpTimeoutSeconds(int httpTimeoutSeconds) { this.httpTimeoutSeconds = httpTimeoutSeconds; }

    public int getTokenClockSkewSeconds() { return tokenClockSkewSeconds; }
    public void setTokenClockSkewSeconds(int tokenClockSkewSeconds) { this.tokenClockSkewSeconds = tokenClockSkewSeconds; }

    public String getLoginButtonLabel() { return loginButtonLabel; }
    public void setLoginButtonLabel(String loginButtonLabel) { this.loginButtonLabel = loginButtonLabel; }

    public String getLoginButtonIconUrl() { return loginButtonIconUrl; }
    public void setLoginButtonIconUrl(String loginButtonIconUrl) { this.loginButtonIconUrl = loginButtonIconUrl; }
}
