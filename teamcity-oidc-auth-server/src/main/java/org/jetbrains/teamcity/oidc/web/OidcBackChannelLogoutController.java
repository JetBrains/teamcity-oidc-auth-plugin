package org.jetbrains.teamcity.oidc.web;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jetbrains.buildServer.controllers.AuthorizationInterceptor;
import jetbrains.buildServer.controllers.BaseController;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.SecurityContextEx;
import jetbrains.buildServer.auth.SessionModel;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.users.UserModelEx;
import org.jetbrains.teamcity.oidc.OidcConstants;
import org.jetbrains.teamcity.oidc.auth.OidcAuthenticationScheme;
import org.jetbrains.teamcity.oidc.config.OidcPluginSettings;
import org.jetbrains.teamcity.oidc.config.OidcPluginSettingsStorage;
import org.jetbrains.teamcity.oidc.oidc.JwtVerifier;
import org.jetbrains.teamcity.oidc.oidc.OidcClient;
import org.jetbrains.teamcity.oidc.oidc.OidcClientException;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

/**
 * Handles OIDC Back-Channel Logout notifications (RFC 8935).
 * <p>
 * The IdP POSTs a signed {@code logout_token} JWT here when a user's session ends
 * (user removed, logged out, or session expired on the IdP side). The controller
 * validates the token and terminates all active TeamCity sessions for that user.
 * <p>
 * Register the logout URI in the IdP client configuration as:
 * {@code <tcRootUrl>/app/oidc/backchannel-logout}
 */
public class OidcBackChannelLogoutController extends BaseController {

    private final OidcPluginSettingsStorage settingsStorage;
    private final OidcClient oidcClient;
    private final UserModelEx userModel;
    private final SecurityContextEx securityContext;
    private final SessionModel sessionModel;

    public OidcBackChannelLogoutController(
            @NotNull SBuildServer server,
            @NotNull WebControllerManager webControllerManager,
            @NotNull OidcPluginSettingsStorage settingsStorage,
            @NotNull OidcClient oidcClient,
            @NotNull UserModelEx userModel,
            @NotNull SecurityContextEx securityContext,
            @NotNull SessionModel sessionModel,
            @NotNull AuthorizationInterceptor authInterceptor) {
        super(server);
        this.settingsStorage = settingsStorage;
        this.oidcClient = oidcClient;
        this.userModel = userModel;
        this.securityContext = securityContext;
        this.sessionModel = sessionModel;
        webControllerManager.registerController(OidcConstants.BACKCHANNEL_LOGOUT_PATH, this);

        authInterceptor.addPathNotRequiringAuth(OidcConstants.BACKCHANNEL_LOGOUT_PATH);
    }

    @Nullable
    @Override
    protected ModelAndView doHandle(@NotNull HttpServletRequest request,
                                    @NotNull HttpServletResponse response) throws Exception {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return null;
        }

        Loggers.AUTH.info("OIDC back-channel logout: received POST from " + request.getRemoteAddr());

        String rawToken = request.getParameter("logout_token");
        if (rawToken == null || rawToken.isEmpty()) {
            Loggers.AUTH.warn("OIDC back-channel logout: missing logout_token parameter");
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing logout_token parameter");
            return null;
        }
        Loggers.AUTH.debug("OIDC back-channel logout: raw token length=" + rawToken.length());

        String sub;
        try {
            sub = validateLogoutToken(rawToken);
        } catch (Exception e) {
            Loggers.AUTH.warn("OIDC back-channel logout: invalid logout_token — " + e.getMessage());
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid logout_token: " + e.getMessage());
            return null;
        }
        Loggers.AUTH.info("OIDC back-channel logout: token valid, sub='" + sub + "'");

        SUser user;
        try {
            user = findUserBySub(sub);
        } catch (Throwable t) {
            Loggers.AUTH.warn("OIDC back-channel logout: error looking up user for sub='" + sub + "': " + t.getMessage());
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return null;
        }
        if (user == null) {
            Loggers.AUTH.info("OIDC back-channel logout: no TC user has oidc.sub='" + sub + "' — skipping");
            response.setStatus(HttpServletResponse.SC_OK);
            return null;
        }
        Loggers.AUTH.info("OIDC back-channel logout: matched sub='" + sub + "' to user '" + user.getUsername() + "' (id=" + user.getId() + ")");

        try {
            securityContext.runAs(user, () -> {
                boolean terminated = sessionModel.terminateSessionsByUserId(user.getId());
                Loggers.AUTH.info("OIDC back-channel logout: terminateSessionsByUserId(" + user.getId() + ") returned " + terminated);
            });
        } catch (Throwable e) {
            Loggers.AUTH.warn("OIDC back-channel logout: error terminating sessions for user '" + user.getUsername() + "': " + e.getMessage());
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return null;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        return null;
    }

    @Nullable
    private SUser findUserBySub(@NotNull String sub) throws Throwable {
        java.util.Set<SUser> found = securityContext.runAsSystem(() ->
                userModel.findUsersByAttributeValue(OidcConstants.OIDC_SUB_ATTRIBUTE, sub, true)
                         .getUsers());
        return found.isEmpty() ? null : found.iterator().next();
    }

    /**
     * Validates the logout_token according to RFC 8935 §2.6 and returns the {@code sub} claim.
     */
    @NotNull
    private String validateLogoutToken(@NotNull String rawToken) throws Exception {
        OidcPluginSettings settings = settingsStorage.getSettings();

        SignedJWT jwt = SignedJWT.parse(rawToken);
        JWTClaimsSet claims = jwt.getJWTClaimsSet();

        // Verify signature against the IdP's JWKS
        String jwksUri = resolveJwksUri(settings);
        String jwksJson = oidcClient.fetchJwks(jwksUri);
        JWKSet jwkSet = JWKSet.parse(jwksJson);
        String kid = jwt.getHeader().getKeyID();
        JWK jwk = kid != null ? jwkSet.getKeyByKeyId(kid) : jwkSet.getKeys().isEmpty() ? null : jwkSet.getKeys().get(0);
        if (jwk == null) throw new Exception("No matching key found in JWKS for kid='" + kid + "'");

        if (!JwtVerifier.verify(jwt, jwk)) throw new Exception("Signature verification failed");

        // iss must match configured issuer
        if (!settings.getIssuerUrl().equals(claims.getIssuer())) {
            throw new Exception("iss mismatch: expected '" + settings.getIssuerUrl()
                    + "' got '" + claims.getIssuer() + "'");
        }

        // aud must contain client_id
        if (!claims.getAudience().contains(settings.getClientId())) {
            throw new Exception("aud does not contain client_id '" + settings.getClientId() + "'");
        }

        // iat must be present and not too old (max 5 min + clock skew)
        if (claims.getIssueTime() == null) throw new Exception("Missing iat claim");
        long ageMs = System.currentTimeMillis() - claims.getIssueTime().getTime();
        long maxAgeMs = (300 + settings.getTokenClockSkewSeconds()) * 1000L;
        if (ageMs > maxAgeMs) throw new Exception("logout_token is too old (" + ageMs / 1000 + "s)");

        // events claim must contain the back-channel logout event
        @SuppressWarnings("unchecked")
        Map<String, Object> events = (Map<String, Object>) claims.getClaim("events");
        if (events == null || !events.containsKey(OidcConstants.BACKCHANNEL_LOGOUT_EVENT)) {
            throw new Exception("Missing or invalid 'events' claim");
        }

        // nonce must NOT be present
        if (claims.getClaim("nonce") != null) {
            throw new Exception("logout_token must not contain a nonce claim");
        }

        String sub = claims.getSubject();
        if (sub == null || sub.isEmpty()) throw new Exception("Missing sub claim");
        return sub;
    }

    @NotNull
    private String resolveJwksUri(@NotNull OidcPluginSettings settings) throws OidcClientException {
        if (!settings.isDiscoveryEnabled() && settings.getJwksUri() != null) {
            return settings.getJwksUri();
        }
        return oidcClient.fetchDiscoveryDocument(settings.getIssuerUrl()).getJwksUri();
    }
}
