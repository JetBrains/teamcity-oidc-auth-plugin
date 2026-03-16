package org.jetbrains.teamcity.oidc.web;

import jetbrains.buildServer.controllers.BaseController;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.teamcity.oidc.OidcConstants;
import org.jetbrains.teamcity.oidc.RedirectUtil;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.net.URLEncoder;

/**
 * Handles {@code GET /app/oidc/callback}.
 * The actual authentication is performed by TC's auth interceptor which calls
 * {@link org.jetbrains.teamcity.oidc.auth.OidcAuthenticationScheme#processAuthenticationRequest}
 * before this controller's {@code doHandle()} runs.
 *
 * {@code doHandle()} is therefore only reached when:
 * <ul>
 *   <li>The IdP sent an error response ({@code ?error=...}), or</li>
 *   <li>The request has no {@code code} parameter (scheme returned notApplicable).</li>
 * </ul>
 */
public class OidcCallbackController extends BaseController {

    public OidcCallbackController(
            @NotNull SBuildServer server,
            @NotNull WebControllerManager webControllerManager) {
        super(server);
        webControllerManager.registerController(OidcConstants.CALLBACK_PATH, this);
    }

    @Nullable
    @Override
    protected ModelAndView doHandle(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response) throws Exception {
        String error = request.getParameter("error");
        if (error != null) {
            String sanitizedError = RedirectUtil.stripCrlf(error);
            String description = request.getParameter("error_description");
            String sanitizedDescription = description != null ? RedirectUtil.stripCrlf(description) : null;
            String message = sanitizedDescription != null && !sanitizedDescription.isEmpty()
                    ? sanitizedDescription : sanitizedError;
            Loggers.AUTH.warn("OIDC: IdP returned error: " + sanitizedError + " — " + sanitizedDescription);
            String loginPage = request.getContextPath() + "/login.html?authError="
                    + URLEncoder.encode(message, "UTF-8");
            response.sendRedirect(loginPage);
            return null;
        }

        // Fallback: redirect to home (auth succeeded via interceptor and was redirected,
        // or there was nothing to do)
        response.sendRedirect(request.getContextPath() + "/");
        return null;
    }
}
