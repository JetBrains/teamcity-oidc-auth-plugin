package org.jetbrains.teamcity.oidc.web;

import jetbrains.buildServer.RootUrlHolder;
import jetbrains.buildServer.controllers.AuthorizationInterceptor;
import jetbrains.buildServer.controllers.BaseController;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.teamcity.oidc.OidcConstants;
import org.jetbrains.teamcity.oidc.RedirectUtil;
import org.jetbrains.teamcity.oidc.auth.OidcAuthenticationScheme;
import org.jetbrains.teamcity.oidc.auth.OidcStateManager;
import org.jetbrains.teamcity.oidc.config.OidcPluginSettings;
import org.jetbrains.teamcity.oidc.config.OidcPluginSettingsStorage;
import org.jetbrains.teamcity.oidc.oidc.OidcClient;
import org.jetbrains.teamcity.oidc.oidc.OidcDiscoveryDocument;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.net.URLEncoder;
import java.util.List;

/**
 * Handles {@code GET /app/oidc/login}.
 * Builds the IdP authorization URL and redirects the browser.
 * The path is unconditionally exempted from authentication via {@link AuthorizationInterceptor}.
 */
public class OidcLoginController extends BaseController {

    private final OidcPluginSettingsStorage settingsStorage;
    private final OidcClient oidcClient;
    private final OidcStateManager stateManager;
    private final OidcAuthenticationScheme authenticationScheme;

    public OidcLoginController(
            @NotNull WebControllerManager webControllerManager,
            @NotNull OidcPluginSettingsStorage settingsStorage,
            @NotNull OidcClient oidcClient,
            @NotNull OidcStateManager stateManager,
            @NotNull OidcAuthenticationScheme authenticationScheme,
            @NotNull AuthorizationInterceptor authInterceptor) {
        this.settingsStorage = settingsStorage;
        this.oidcClient = oidcClient;
        this.stateManager = stateManager;
        this.authenticationScheme = authenticationScheme;

        authInterceptor.addPathNotRequiringAuth(OidcConstants.LOGIN_PATH);
        webControllerManager.registerController(OidcConstants.LOGIN_PATH, this);
    }

    @Nullable
    @Override
    protected ModelAndView doHandle(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response) throws Exception {
        OidcPluginSettings settings = settingsStorage.getSettings();

        if (isBlank(settings.getClientId()) || isBlank(settings.getIssuerUrl())) {
            Loggers.SERVER.error("OIDC: cannot initiate login — clientId or issuerUrl is not configured");
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "OIDC plugin is not configured. Please create " + OidcConstants.CONFIG_FILE_NAME);
            return null;
        }

        // Resolve authorization endpoint
        String authorizationEndpoint;
        if (!settings.isDiscoveryEnabled() && !isBlank(settings.getAuthorizationEndpoint())) {
            authorizationEndpoint = settings.getAuthorizationEndpoint();
        } else {
            OidcDiscoveryDocument discovery = oidcClient.fetchDiscoveryDocument(settings.getIssuerUrl());
            authorizationEndpoint = discovery.getAuthorizationEndpoint();
        }

        // Store any requested URL for post-login redirect.
        // Only relative paths are accepted to prevent open redirect attacks.
        HttpSession session = request.getSession(true);
        String redirectAfter = RedirectUtil.sanitizeRedirectPath(request.getParameter("redirectTo"));
        if (redirectAfter != null) {
            session.setAttribute(OidcConstants.SESSION_REDIRECT_URL, redirectAfter);
        }

        // Generate and store state and nonce
        String state = stateManager.generateState(session);
        String nonce = stateManager.generateNonce(session);

        // Build redirect URI
        String redirectUri = authenticationScheme.getCallbackUrl(settings);

        // Build scopes string
        List<String> scopes = settings.getScopes();
        if (scopes == null || scopes.isEmpty()) scopes = OidcConstants.DEFAULT_SCOPES;
        String scopeStr = String.join(" ", scopes);

        // Build authorization URL
        String authUrl = authorizationEndpoint
                + "?response_type=code"
                + "&client_id=" + URLEncoder.encode(settings.getClientId(), "UTF-8")
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8")
                + "&scope=" + URLEncoder.encode(scopeStr, "UTF-8")
                + "&state=" + URLEncoder.encode(state, "UTF-8")
                + "&nonce=" + URLEncoder.encode(nonce, "UTF-8");

        Loggers.SERVER.debug("OIDC: redirecting to authorization endpoint");
        response.sendRedirect(authUrl);
        return null;
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.trim().isEmpty();
    }

}
