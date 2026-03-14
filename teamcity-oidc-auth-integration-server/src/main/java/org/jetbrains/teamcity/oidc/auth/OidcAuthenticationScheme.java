package org.jetbrains.teamcity.oidc.auth;

import jetbrains.buildServer.RootUrlHolder;
import jetbrains.buildServer.controllers.AuthorizationInterceptor;
import jetbrains.buildServer.controllers.interceptors.auth.HttpAuthenticationResult;
import jetbrains.buildServer.controllers.interceptors.auth.HttpAuthenticationSchemeAdapter;
import jetbrains.buildServer.controllers.interceptors.auth.util.HttpAuthUtil;
import jetbrains.buildServer.groups.SUserGroup;
import jetbrains.buildServer.groups.UserGroupManager;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.serverSide.auth.LoginConfiguration;
import jetbrains.buildServer.serverSide.auth.ServerPrincipal;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.users.UserModel;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.teamcity.oidc.OidcConstants;
import org.jetbrains.teamcity.oidc.config.OidcClaimMappingSettings;
import org.jetbrains.teamcity.oidc.config.OidcPluginSettings;
import org.jetbrains.teamcity.oidc.config.OidcPluginSettingsStorage;
import org.jetbrains.teamcity.oidc.oidc.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.*;
import java.util.stream.Collectors;

public class OidcAuthenticationScheme extends HttpAuthenticationSchemeAdapter {

    private final OidcPluginSettingsStorage settingsStorage;
    private final OidcClient oidcClient;
    private final OidcIdTokenValidator tokenValidator;
    private final OidcStateManager stateManager;
    private final UserModel userModel;
    private final UserGroupManager userGroupManager;
    private final RootUrlHolder rootUrlHolder;

    public OidcAuthenticationScheme(
            @NotNull LoginConfiguration loginConfiguration,
            @NotNull OidcPluginSettingsStorage settingsStorage,
            @NotNull OidcClient oidcClient,
            @NotNull OidcIdTokenValidator tokenValidator,
            @NotNull OidcStateManager stateManager,
            @NotNull UserModel userModel,
            @NotNull UserGroupManager userGroupManager,
            @NotNull RootUrlHolder rootUrlHolder,
            @NotNull WebControllerManager webControllerManager,
            @NotNull AuthorizationInterceptor authInterceptor) {
        this.settingsStorage = settingsStorage;
        this.oidcClient = oidcClient;
        this.tokenValidator = tokenValidator;
        this.stateManager = stateManager;
        this.userModel = userModel;
        this.userGroupManager = userGroupManager;
        this.rootUrlHolder = rootUrlHolder;

        // The login initiator path must be accessible without authentication so unauthenticated
        // users can start the OIDC flow. The callback path must NOT be exempt — TC's auth
        // interceptor only calls processAuthenticationRequest for paths that require auth, and
        // that method is where the code-exchange and token validation happen.
        authInterceptor.addPathNotRequiringAuth(OidcConstants.LOGIN_PATH);
    }

    @NotNull
    @Override
    protected String doGetName() {
        return OidcConstants.AUTH_SCHEME_NAME;
    }

    @NotNull
    @Override
    public String getDescription() {
        return OidcConstants.AUTH_SCHEME_DESCRIPTION;
    }

    @Override
    public boolean isMultipleInstancesAllowed() {
        return false;
    }

    public boolean isConfigured() {
        OidcPluginSettings s = settingsStorage.getSettings();
        return !isBlank(s.getIssuerUrl()) && !isBlank(s.getClientId());
    }

    private static boolean isBlank(@org.jetbrains.annotations.Nullable String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Called by TC's auth interceptor for every request.
     * Returns {@code notApplicable()} for all requests that are not the OIDC callback
     * carrying a {@code code} parameter.
     */
    @NotNull
    @Override
    public HttpAuthenticationResult processAuthenticationRequest(
            @NotNull HttpServletRequest request,
            @NotNull HttpServletResponse response,
            @NotNull Map<String, String> properties) throws IOException {

        String code = request.getParameter("code");
        if (code == null || !request.getRequestURI().contains(OidcConstants.CALLBACK_PATH)) {
            return HttpAuthenticationResult.notApplicable();
        }

        Loggers.AUTH.debug("OIDC: processing callback for " + request.getRequestURI());

        try {
            return doProcessCallback(request, response, code);
        } catch (OidcAuthException e) {
            return fail(request, response, e.getMessage());
        } catch (OidcClientException e) {
            Loggers.AUTH.error("OIDC: IdP communication failure during callback", e);
            return fail(request, response, "Authentication failed: could not communicate with identity provider");
        } catch (Exception e) {
            Loggers.AUTH.error("OIDC: unexpected error during callback processing", e);
            return fail(request, response, "Authentication failed: " + e.getMessage());
        }
    }

    // ---- Core callback logic -----------------------------------------------

    @NotNull
    private HttpAuthenticationResult doProcessCallback(
            @NotNull HttpServletRequest request,
            @NotNull HttpServletResponse response,
            @NotNull String code) throws OidcAuthException, OidcClientException, IOException {

        OidcPluginSettings settings = settingsStorage.getSettings();

        // 1. Validate state (anti-CSRF)
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new OidcAuthException("No session found — state cannot be validated");
        }
        String stateParam = request.getParameter("state");
        if (!stateManager.validateAndConsumeState(session, stateParam)) {
            throw new OidcAuthException("State parameter mismatch — possible CSRF attack");
        }

        // 2. Resolve token endpoint
        String tokenEndpoint = resolveTokenEndpoint(settings);

        // 3. Build redirect URI (must match what was sent to IdP)
        String redirectUri = buildRedirectUri(settings);

        // 4. Exchange code for tokens
        OidcTokenResponse tokenResponse = oidcClient.exchangeCodeForTokens(
                tokenEndpoint, code, redirectUri, settings.getClientId(), settings.getClientSecret());

        String rawIdToken = tokenResponse.getIdToken();
        if (rawIdToken == null || rawIdToken.isEmpty()) {
            throw new OidcAuthException("Token endpoint response did not include an id_token");
        }

        // 5. Validate ID token
        String jwksUri = resolveJwksUri(settings);
        String nonce = stateManager.consumeNonce(session);
        OidcIdTokenClaims idTokenClaims = tokenValidator.validateAndDecode(
                rawIdToken, jwksUri, settings.getIssuerUrl(),
                settings.getClientId(), nonce, settings.getTokenClockSkewSeconds());

        // 6. Optionally fetch userinfo for supplemental claims
        OidcUserInfo userInfo = null;
        String userInfoEndpoint = resolveUserInfoEndpoint(settings);
        if (userInfoEndpoint != null && tokenResponse.getAccessToken() != null) {
            try {
                userInfo = oidcClient.fetchUserInfo(userInfoEndpoint, tokenResponse.getAccessToken());
            } catch (OidcClientException e) {
                Loggers.AUTH.warn("OIDC: failed to fetch userinfo (continuing without it): " + e.getMessage());
            }
        }

        // 7. Resolve TeamCity username
        String username = resolveUsername(settings, idTokenClaims, userInfo);
        if (username == null || username.isEmpty()) {
            throw new OidcAuthException("Could not resolve a username from token claims");
        }

        // 8. Apply email domain allowlist
        String email = resolveClaim(settings.getEmailClaim(), idTokenClaims, userInfo);
        if (!settings.getAllowedEmailDomains().isEmpty() && email != null) {
            checkEmailDomain(email, settings.getAllowedEmailDomains());
        }

        // 9. Lookup or create user
        SUser user = userModel.findUserAccount(null, username);
        if (user == null && settings.isCreateUsersAutomatically()) {
            Loggers.AUTH.info("OIDC: auto-creating user '" + username + "'");
            user = userModel.createUserAccount(null, username);
            if (user != null) {
                String displayName = resolveClaim(settings.getDisplayNameClaim(), idTokenClaims, userInfo);
                user.updateUserAccount(username, displayName, email);
            }
        }
        if (user == null) {
            throw new OidcAuthException("User '" + username + "' not found and auto-creation is disabled");
        }

        // 10. Group sync
        if (settings.isAssignGroups()) {
            List<String> groups = resolveGroups(settings, idTokenClaims, userInfo);
            syncGroups(user, groups, settings.isRemoveUnassignedGroups());
        }

        Loggers.AUTH.info("OIDC: authenticated user '" + username + "'");
        String redirectUrl = getPostLoginRedirect(session, request);
        return HttpAuthenticationResult.authenticated(
                new ServerPrincipal(user.getRealm(), user.getUsername(), null,
                        settings.isCreateUsersAutomatically(), new HashMap<>()),
                true).withRedirect(redirectUrl);
    }

    // ---- Helpers -----------------------------------------------------------

    @NotNull
    private String resolveTokenEndpoint(@NotNull OidcPluginSettings settings) throws OidcClientException {
        if (!settings.isDiscoveryEnabled() && settings.getTokenEndpoint() != null) {
            return settings.getTokenEndpoint();
        }
        return oidcClient.fetchDiscoveryDocument(settings.getIssuerUrl()).getTokenEndpoint();
    }

    @NotNull
    private String resolveJwksUri(@NotNull OidcPluginSettings settings) throws OidcClientException {
        if (!settings.isDiscoveryEnabled() && settings.getJwksUri() != null) {
            return settings.getJwksUri();
        }
        return oidcClient.fetchDiscoveryDocument(settings.getIssuerUrl()).getJwksUri();
    }

    @org.jetbrains.annotations.Nullable
    private String resolveUserInfoEndpoint(@NotNull OidcPluginSettings settings) throws OidcClientException {
        if (!settings.isDiscoveryEnabled()) {
            return settings.getUserInfoEndpoint();
        }
        return oidcClient.fetchDiscoveryDocument(settings.getIssuerUrl()).getUserInfoEndpoint();
    }

    @NotNull
    private String buildRedirectUri(@NotNull OidcPluginSettings settings) {
        String base = settings.getCallbackBaseUrl();
        if (base == null || base.isEmpty()) {
            base = rootUrlHolder.getRootUrl();
        }
        // Strip trailing slash to avoid double-slash
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + OidcConstants.CALLBACK_PATH;
    }

    @org.jetbrains.annotations.Nullable
    private String resolveUsername(@NotNull OidcPluginSettings settings,
                                   @NotNull OidcIdTokenClaims claims,
                                   @org.jetbrains.annotations.Nullable OidcUserInfo userInfo) {
        return resolveClaim(settings.getUsernameClaim(), claims, userInfo);
    }

    @org.jetbrains.annotations.Nullable
    private String resolveClaim(@NotNull OidcClaimMappingSettings mapping,
                                @NotNull OidcIdTokenClaims claims,
                                @org.jetbrains.annotations.Nullable OidcUserInfo userInfo) {
        switch (mapping.getMappingType()) {
            case SUB:
                return claims.getSub();
            case CLAIM:
                String claimName = mapping.getClaimName();
                if (claimName == null || claimName.isEmpty()) return null;
                // Try ID token first
                Object value = claims.getRaw().get(claimName);
                if (value != null) return value.toString();
                // Fall back to userinfo
                if (userInfo != null) {
                    Object uiValue = userInfo.getRaw().get(claimName);
                    if (uiValue != null) return uiValue.toString();
                }
                return null;
            case NONE:
            default:
                return null;
        }
    }

    private void checkEmailDomain(@NotNull String email, @NotNull List<String> allowedDomains) throws OidcAuthException {
        int atIdx = email.lastIndexOf('@');
        if (atIdx < 0) throw new OidcAuthException("Email '" + email + "' is not valid");
        String domain = email.substring(atIdx + 1).toLowerCase(Locale.ROOT);
        boolean allowed = allowedDomains.stream()
                .anyMatch(d -> d.toLowerCase(Locale.ROOT).equals(domain));
        if (!allowed) {
            throw new OidcAuthException("Email domain '" + domain + "' is not in the allowed domains list");
        }
    }

    @NotNull
    private List<String> resolveGroups(@NotNull OidcPluginSettings settings,
                                       @NotNull OidcIdTokenClaims claims,
                                       @org.jetbrains.annotations.Nullable OidcUserInfo userInfo) {
        String claimName = settings.getGroupsClaimName();
        Object raw = claims.getRaw().get(claimName);
        if (raw == null && userInfo != null) raw = userInfo.getRaw().get(claimName);
        if (raw == null) return Collections.emptyList();

        if (raw instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) raw;
            return list.stream().filter(Objects::nonNull).map(Object::toString).collect(Collectors.toList());
        }
        // Handle space- or comma-separated string
        String str = raw.toString().trim();
        if (str.isEmpty()) return Collections.emptyList();
        return Arrays.stream(str.split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private void syncGroups(@NotNull SUser user, @NotNull List<String> idpGroups, boolean removeUnassigned) {
        Map<String, SUserGroup> tcGroups = new HashMap<>();
        for (SUserGroup g : userGroupManager.getUserGroups()) {
            tcGroups.put(g.getKey().toLowerCase(Locale.ROOT), g);
        }

        Set<String> currentGroupKeys = user.getUserGroups().stream()
                .filter(g -> !"ALL_USERS_GROUP".equals(g.getKey()))
                .map(g -> g.getKey().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        Set<String> idpGroupKeys = idpGroups.stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        // Add groups present in IdP claim but not yet assigned in TC
        for (String key : idpGroupKeys) {
            if (!currentGroupKeys.contains(key)) {
                SUserGroup group = tcGroups.get(key);
                if (group != null) {
                    Loggers.AUTH.info("OIDC: adding user '" + user.getUsername() + "' to group '" + key + "'");
                    group.addUser(user);
                } else {
                    Loggers.AUTH.debug("OIDC: no TC group found for IdP group key '" + key + "'");
                }
            }
        }

        // Optionally remove groups absent from IdP claim
        if (removeUnassigned) {
            for (String key : currentGroupKeys) {
                if (!idpGroupKeys.contains(key)) {
                    SUserGroup group = tcGroups.get(key);
                    if (group != null) {
                        Loggers.AUTH.info("OIDC: removing user '" + user.getUsername() + "' from group '" + key + "'");
                        group.removeUser(user);
                    }
                }
            }
        }
    }

    @NotNull
    private String getPostLoginRedirect(@NotNull HttpSession session, @NotNull HttpServletRequest request) {
        Object stored = session.getAttribute(OidcConstants.SESSION_REDIRECT_URL);
        if (stored instanceof String && !((String) stored).isEmpty()) {
            session.removeAttribute(OidcConstants.SESSION_REDIRECT_URL);
            return (String) stored;
        }
        Object urlKey = session.getAttribute("URL_KEY");
        if (urlKey instanceof String && !((String) urlKey).isEmpty()) {
            session.removeAttribute("URL_KEY");
            return (String) urlKey;
        }
        return request.getContextPath() + "/";
    }

    @NotNull
    private HttpAuthenticationResult fail(@NotNull HttpServletRequest request,
                                          @NotNull HttpServletResponse response,
                                          @NotNull String reason) throws IOException {
        Loggers.AUTH.warn("OIDC: authentication failed — " + reason);
        HttpAuthUtil.setUnauthenticatedReason(request, reason);
        String loginPage = request.getContextPath() + "/login.html?authError="
                + URLEncoder.encode(reason, "UTF-8");
        response.sendRedirect(loginPage);
        return HttpAuthenticationResult.unauthenticated();
    }
}
