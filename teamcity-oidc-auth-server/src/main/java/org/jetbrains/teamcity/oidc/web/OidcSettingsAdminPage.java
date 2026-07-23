package org.jetbrains.teamcity.oidc.web;

import jetbrains.buildServer.controllers.admin.AdminPage;
import jetbrains.buildServer.serverSide.auth.Permission;
import jetbrains.buildServer.serverSide.crypt.RSACipher;
import jetbrains.buildServer.web.openapi.PagePlaces;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.teamcity.oidc.OidcConstants;
import org.jetbrains.teamcity.oidc.config.OidcPluginSettings;
import org.jetbrains.teamcity.oidc.config.OidcPluginSettingsStorage;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

import static jetbrains.buildServer.controllers.PublicKeyUtil.PUBLIC_KEY_PARAM;

public class OidcSettingsAdminPage extends AdminPage {

    private final OidcPluginSettingsStorage settingsStorage;

    public OidcSettingsAdminPage(
            @NotNull PagePlaces pagePlaces,
            @NotNull PluginDescriptor pluginDescriptor,
            @NotNull OidcPluginSettingsStorage settingsStorage) {
        super(pagePlaces,
                OidcConstants.ADMIN_TAB_ID,
                pluginDescriptor.getPluginResourcesPath("oidc/oidcAdminPage.jsp"),
                "OIDC Auth Settings");
        this.settingsStorage = settingsStorage;
        register();
    }

    @NotNull
    @Override
    public String getGroup() {
        return USER_MANAGEMENT_GROUP;
    }

    @Override
    public void fillModel(@NotNull Map<String, Object> model, @NotNull HttpServletRequest request) {
        super.fillModel(model, request);
        OidcPluginSettings s = settingsStorage.getSettings();
        model.put("settings", s);
        model.put("settingsActionUrl", OidcConstants.ADMIN_SETTINGS_PATH);
        model.put("discoveryInfoUrl", OidcConstants.ADMIN_DISCOVERY_INFO_PATH);
        model.put(PUBLIC_KEY_PARAM, RSACipher.getHexEncodedPublicKey());
        String secret = s.getClientSecret();
        model.put("encryptedClientSecret",
                secret != null && !secret.isEmpty() ? RSACipher.encryptDataForWeb(secret) : "");
    }

    @Override
    public boolean isAvailable(@NotNull HttpServletRequest request) {
        return super.isAvailable(request)
                && checkHasGlobalPermission(request, Permission.CHANGE_SERVER_SETTINGS);
    }
}
