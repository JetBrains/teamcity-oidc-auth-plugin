package org.jetbrains.teamcity.oidc.web;

import jetbrains.buildServer.controllers.BaseController;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.teamcity.oidc.OidcConstants;
import org.jetbrains.teamcity.oidc.oidc.OidcClient;
import org.jetbrains.teamcity.oidc.oidc.OidcClientException;
import org.jetbrains.teamcity.oidc.oidc.OidcDiscoveryDocument;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class OidcDiscoveryInfoController extends BaseController {
    private final OidcClient oidcClient;
    private final String discoveryInfoPath;

    public OidcDiscoveryInfoController(@NotNull WebControllerManager webControllerManager,
                                       @NotNull PluginDescriptor pluginDescriptor,
                                       @NotNull OidcClient oidcClient) {
        this.oidcClient = oidcClient;
        this.discoveryInfoPath = pluginDescriptor.getPluginResourcesPath("oidc/discoveryInfo.jsp");
        webControllerManager.registerController(OidcConstants.ADMIN_DISCOVERY_INFO_PATH, this);
    }

    @Nullable
    @Override
    protected ModelAndView doHandle(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response) {
        String issuerUrl = request.getParameter("issuerUrl");
        ModelAndView mv = new ModelAndView(discoveryInfoPath);
        mv.getModel().put("issuerUrl", issuerUrl);
        mv.getModel().put("oidcClientError", "");
        try {
            if (StringUtil.isEmpty(issuerUrl)) {
                throw new IllegalArgumentException("The issuerUrl is not specified");
            }
            OidcDiscoveryDocument discoveryDocument = oidcClient.fetchDiscoveryDocument(issuerUrl);
            mv.getModel().put("discoveryDocument", discoveryDocument);
        } catch (Throwable e) {
            mv.getModel().put("discoveryDocument", new OidcDiscoveryDocument());
            mv.getModel().put("oidcClientError", e.getMessage());
        }
        return mv;
    }
}
