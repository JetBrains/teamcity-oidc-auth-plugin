package org.jetbrains.teamcity.oidc.auth;

import jetbrains.buildServer.auth.SessionModel;
import jetbrains.buildServer.controllers.interceptors.RequestInterceptors;
import jetbrains.buildServer.controllers.interceptors.SkippableInterceptor;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.serverSide.TeamCityProperties;
import jetbrains.buildServer.serverSide.SecurityContextEx;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.users.UserModelEx;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.teamcity.oidc.OidcConstants;
import org.jetbrains.teamcity.oidc.RedirectUtil;
import org.jetbrains.teamcity.oidc.config.OidcPluginSettings;
import org.jetbrains.teamcity.oidc.config.OidcPluginSettingsStorage;
import org.jetbrains.teamcity.oidc.oidc.OidcClient;
import org.jetbrains.teamcity.oidc.oidc.OidcClientException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.net.URLEncoder;

/**
 * Per-request OIDC token introspection interceptor (RFC 7662).
 * <p>
 * Enabled via the TC internal property
 * {@code teamcity.oidc.auth.token.introspection.enabled=true}.
 * When enabled, every request carrying an OIDC session (access token stored at login)
 * is validated against the IdP's introspection endpoint. If the token is no longer
 * active (user deleted, session revoked), all TC sessions for that user are terminated
 * and the browser is redirected to the login page.
 * <p>
 * Fails open: if the IdP is unreachable, the request is allowed to proceed.
 * <p>
 * <b>Limitation:</b> only covers browser sessions where the access token was stored
 * at login time. Requests that TC re-authenticates via a remember-me cookie create a
 * fresh session without the stored token, so introspection is not triggered for them.
 * Use Keycloak session lifetime matching as a complementary safeguard for that case.
 */
public class OidcTokenIntrospectionInterceptor extends SkippableInterceptor {

    private static final String INTROSPECTION_PROPERTY = "teamcity.oidc.auth.token.introspection.enabled";

    private final OidcPluginSettingsStorage settingsStorage;
    private final OidcClient oidcClient;
    private final UserModelEx userModel;
    private final SecurityContextEx securityContext;
    private final SessionModel sessionModel;

    public OidcTokenIntrospectionInterceptor(
            @NotNull RequestInterceptors interceptors,
            @NotNull OidcPluginSettingsStorage settingsStorage,
            @NotNull OidcClient oidcClient,
            @NotNull UserModelEx userModel,
            @NotNull SecurityContextEx securityContext,
            @NotNull SessionModel sessionModel) {
        this.settingsStorage = settingsStorage;
        this.oidcClient = oidcClient;
        this.userModel = userModel;
        this.securityContext = securityContext;
        this.sessionModel = sessionModel;
        interceptors.addInterceptor(this);
    }

    @Override
    protected boolean preHandleInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Object handler) throws Exception {
        if (!TeamCityProperties.getBoolean(INTROSPECTION_PROPERTY)) return true;

        HttpSession session = request.getSession(false);
        if (session == null) return true;

        String accessToken = (String) session.getAttribute(OidcConstants.SESSION_ACCESS_TOKEN);
        Object userIdAttr = session.getAttribute(OidcConstants.SESSION_USER_ID);
        if (accessToken == null || userIdAttr == null) return true;
        long userId = (Long) userIdAttr;

        OidcPluginSettings settings = settingsStorage.getSettings();
        String introspectionEndpoint = resolveIntrospectionEndpoint(settings);
        if (introspectionEndpoint == null) {
            Loggers.AUTH.warn("OIDC token introspection: endpoint not available (check discovery or set introspectionEndpoint) — skipping");
            return true; // fail open
        }

        boolean active;
        try {
            active = oidcClient.introspectToken(
                    introspectionEndpoint, accessToken, settings.getClientId(), settings.getClientSecret());
        } catch (OidcClientException e) {
            Loggers.AUTH.warn("OIDC token introspection: IdP call failed (allowing request): " + e.getMessage());
            return true; // fail open — IdP unreachable should not block the user
        }

        if (active) return true;

        // Token is no longer active — terminate all sessions so remember-me is also cleared
        Loggers.AUTH.info("OIDC token introspection: token inactive for userId=" + userId + ", terminating all sessions");
        try {
            SUser user = securityContext.runAsSystem(() -> userModel.findUserById(userId));
            if (user != null) {
                securityContext.runAs(user, () -> sessionModel.terminateSessionsByUserId(userId));
            } else {
                session.invalidate();
            }
        } catch (Throwable t) {
            Loggers.AUTH.warn("OIDC token introspection: error terminating sessions for userId=" + userId + ": " + t.getMessage());
            session.invalidate();
        }

        // Redirect to the OIDC login initiator rather than the error page.
        // If the user still has a live KC session the re-authentication is seamless:
        // KC issues a new token (with up-to-date group claims) and bounces them back
        // to TC without a password prompt, transparently applying any permission changes.
        String returnTo = RedirectUtil.stripCrlf(request.getRequestURI());
        String qs = request.getQueryString();
        if (qs != null && !qs.isEmpty()) returnTo += "?" + RedirectUtil.stripCrlf(qs);
        response.sendRedirect(request.getContextPath() + OidcConstants.LOGIN_PATH
                + "?redirectTo=" + URLEncoder.encode(returnTo, "UTF-8"));
        return false;
    }

    @Nullable
    private String resolveIntrospectionEndpoint(@NotNull OidcPluginSettings settings) {
        if (!settings.isDiscoveryEnabled()) {
            return settings.getIntrospectionEndpoint();
        }
        try {
            return oidcClient.fetchDiscoveryDocument(settings.getIssuerUrl()).getIntrospectionEndpoint();
        } catch (OidcClientException e) {
            Loggers.AUTH.warn("OIDC token introspection: could not fetch discovery document: " + e.getMessage());
            return null;
        }
    }
}
