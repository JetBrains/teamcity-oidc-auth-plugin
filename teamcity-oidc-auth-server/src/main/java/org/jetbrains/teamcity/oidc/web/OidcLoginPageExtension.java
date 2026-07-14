package org.jetbrains.teamcity.oidc.web;

import jetbrains.buildServer.web.openapi.PagePlaces;
import jetbrains.buildServer.web.openapi.PlaceId;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import jetbrains.buildServer.web.openapi.SimplePageExtension;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.teamcity.oidc.OidcConstants;
import org.jetbrains.teamcity.oidc.auth.OidcAuthenticationScheme;
import org.jetbrains.teamcity.oidc.config.OidcPluginSettingsStorage;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * Injects the OIDC login icon onto TeamCity's login page ({@link PlaceId#LOGIN_PAGE}).
 * The fragment is only rendered when the plugin is fully configured (issuerUrl + clientId present).
 */
public class OidcLoginPageExtension extends SimplePageExtension {

    private final OidcPluginSettingsStorage settingsStorage;
    private final OidcAuthenticationScheme scheme;

    public OidcLoginPageExtension(
            @NotNull PagePlaces pagePlaces,
            @NotNull PluginDescriptor pluginDescriptor,
            @NotNull OidcPluginSettingsStorage settingsStorage,
            @NotNull OidcAuthenticationScheme scheme) {
        super(pagePlaces,
                PlaceId.LOGIN_PAGE,
                "OidcLogin",
                pluginDescriptor.getPluginResourcesPath("oidc/loginButton.jsp"));
        this.settingsStorage = settingsStorage;
        this.scheme = scheme;
        register();
    }

    @Override
    public boolean isAvailable(@NotNull HttpServletRequest request) {
        return scheme.isConfigured();
    }

    @Override
    public void fillModel(@NotNull Map<String, Object> model, @NotNull HttpServletRequest request) {
        super.fillModel(model, request);
        model.put("oidcLoginPath", OidcConstants.LOGIN_PATH);
        model.put("oidcSettings", settingsStorage.getSettings());
    }
}
