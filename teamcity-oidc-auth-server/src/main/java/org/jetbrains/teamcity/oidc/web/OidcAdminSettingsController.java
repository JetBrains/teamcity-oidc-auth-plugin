package org.jetbrains.teamcity.oidc.web;

import jetbrains.buildServer.controllers.ActionErrors;
import jetbrains.buildServer.controllers.BaseFormXmlController;
import jetbrains.buildServer.controllers.FormUtil;

import jetbrains.buildServer.controllers.PublicKeyUtil;
import jetbrains.buildServer.serverSide.crypt.RSACipher;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.teamcity.oidc.OidcConstants;
import org.jetbrains.teamcity.oidc.config.OidcClaimMappingSettings;
import org.jetbrains.teamcity.oidc.config.OidcPluginSettings;
import org.jetbrains.teamcity.oidc.config.OidcPluginSettingsStorage;
import org.jetbrains.teamcity.oidc.oidc.OidcClient;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class OidcAdminSettingsController extends BaseFormXmlController {

    private final OidcPluginSettingsStorage settingsStorage;
    private final String editAuthSchemePath;

    public OidcAdminSettingsController(
            @NotNull WebControllerManager webControllerManager,
            @NotNull OidcPluginSettingsStorage settingsStorage,
            @NotNull PluginDescriptor pluginDescriptor) {
        this.settingsStorage = settingsStorage;
        this.editAuthSchemePath = pluginDescriptor.getPluginResourcesPath("oidc/editOidcAuthScheme.jsp");
        webControllerManager.registerController(OidcConstants.ADMIN_SETTINGS_PATH, this);
    }

    @Override
    protected ModelAndView doGet(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response) {
        // Rendered as the inline form inside TC's "Authentication" admin section.
        // The real settings UI is on the dedicated OIDC admin tab (OidcSettingsAdminPage).
        ModelAndView modelAndView = new ModelAndView(editAuthSchemePath);
        modelAndView.getModel().put("oidcSettingsConfigured", settingsStorage.getSettings().settingsAreConfigured());
        modelAndView.getModel().put("issuerUrl", StringUtil.emptyIfNull(settingsStorage.getSettings().getIssuerUrl()));
        return modelAndView;
    }

    @Override
    protected void doPost(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull Element xmlResponse) {
        if (PublicKeyUtil.isPublicKeyExpired(request)) {
            PublicKeyUtil.writePublicKeyExpiredError(xmlResponse);
            return;
        }

        OidcPluginSettings settings = new OidcPluginSettings();
        FormUtil.bindFromRequest(request, settings);

        // FormUtil cannot handle List<String> or nested POJOs — parse manually.
        settings.setScopes(StringUtil.split(request.getParameter("scopes"), true, ' ', ','));
        settings.setAllowedEmailDomains(StringUtil.split(request.getParameter("allowedEmailDomains"), true, ' ', ','));
        settings.setUsernameClaim(parseClaimMapping(request, "usernameClaim"));
        settings.setEmailClaim(parseClaimMapping(request, "emailClaim"));
        settings.setDisplayNameClaim(parseClaimMapping(request, "displayNameClaim"));
        settings.setHttpTimeoutSeconds(parseIntOrDefault(request.getParameter("httpTimeoutSeconds"), OidcConstants.DEFAULT_HTTP_TIMEOUT_SECONDS));
        settings.setTokenClockSkewSeconds(parseIntOrDefault(request.getParameter("tokenClockSkewSeconds"), OidcConstants.DEFAULT_CLOCK_SKEW_SECONDS));

        // clientSecret is RSA-encrypted by BS.AbstractPasswordForm and arrives as encryptedClientSecret.
        // If blank (user left the field empty), keep the existing stored secret.
        String encryptedSecret = request.getParameter("encryptedClientSecret");
        settings.setClientSecret(RSACipher.decryptWebRequestData(encryptedSecret));

        ActionErrors errors = new ActionErrors();
        if (isBlank(settings.getIssuerUrl())) {
            errors.addError("issuerUrl", "Issuer URL is not specified");
        }
        if (isBlank(settings.getClientId())) {
            errors.addError("clientId", "Client ID is not specified");
        }
        if (isBlank(settings.getClientSecret())) {
            errors.addError("clientSecret", "Client secret is not specified");
        }
        if (isBlank(settings.getLoginButtonLabel())) {
            errors.addError("loginLabel", "Login button label is not specified");
        }
        if (errors.hasErrors()) {
            errors.serialize(xmlResponse);
            return;
        }

        try {
            settingsStorage.saveSettings(settings);
            getOrCreateMessages(request).addMessage("settingsSaved", "Settings have been saved");
        } catch (Exception e) {
            errors.addError("general", "Failed to save settings: " + e.getMessage());
            errors.serialize(xmlResponse);
        }
    }

    private static OidcClaimMappingSettings parseClaimMapping(@NotNull HttpServletRequest request, @NotNull String prefix) {
        OidcClaimMappingSettings mapping = new OidcClaimMappingSettings();
        String typeParam = request.getParameter(prefix + "_mappingType");
        if (typeParam != null) {
            try {
                mapping.setMappingType(OidcClaimMappingSettings.MappingType.valueOf(typeParam.toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // keep default
            }
        }
        mapping.setClaimName(request.getParameter(prefix + "_claimName"));
        return mapping;
    }

    private static int parseIntOrDefault(String value, int defaultValue) {
        if (value == null || value.trim().isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
