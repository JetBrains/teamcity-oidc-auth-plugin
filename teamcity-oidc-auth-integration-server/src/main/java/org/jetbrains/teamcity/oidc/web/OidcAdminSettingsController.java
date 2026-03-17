package org.jetbrains.teamcity.oidc.web;

import jetbrains.buildServer.controllers.ActionErrors;
import jetbrains.buildServer.controllers.BaseFormXmlController;
import jetbrains.buildServer.controllers.FormUtil;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import jetbrains.buildServer.controllers.PublicKeyUtil;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.crypt.RSACipher;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.teamcity.oidc.OidcConstants;
import org.jetbrains.teamcity.oidc.config.OidcClaimMappingSettings;
import org.jetbrains.teamcity.oidc.config.OidcPluginSettings;
import org.jetbrains.teamcity.oidc.config.OidcPluginSettingsStorage;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class OidcAdminSettingsController extends BaseFormXmlController {

    private final OidcPluginSettingsStorage settingsStorage;
    private final String editAuthSchemePath;

    public OidcAdminSettingsController(
            @NotNull SBuildServer server,
            @NotNull WebControllerManager webControllerManager,
            @NotNull OidcPluginSettingsStorage settingsStorage,
            @NotNull PluginDescriptor pluginDescriptor) {
        super(server);
        this.settingsStorage = settingsStorage;
        this.editAuthSchemePath = pluginDescriptor.getPluginResourcesPath("oidc/editOidcAuthScheme.jsp");
        webControllerManager.registerController(OidcConstants.ADMIN_SETTINGS_PATH, this);
    }

    @Override
    protected ModelAndView doGet(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response) {
        // Rendered as the inline form inside TC's "Authentication" admin section.
        // The real settings UI is on the dedicated OIDC admin tab (OidcSettingsAdminPage).
        return new ModelAndView(editAuthSchemePath);
    }

    @Override
    protected void doPost(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull Element xmlResponse) {
        if (PublicKeyUtil.isPublicKeyExpired(request)) {
            PublicKeyUtil.writePublicKeyExpiredError(xmlResponse);
            return;
        }

        OidcPluginSettings settings = new OidcPluginSettings();
        FormUtil.bindFromRequest(request, settings);


        // Checkboxes are absent from the request when unchecked — bind explicitly.
        settings.setDiscoveryEnabled("true".equals(request.getParameter("discoveryEnabled")));
        settings.setCreateUsersAutomatically("true".equals(request.getParameter("createUsersAutomatically")));
        settings.setAssignGroups("true".equals(request.getParameter("assignGroups")));
        settings.setRemoveUnassignedGroups("true".equals(request.getParameter("removeUnassignedGroups")));

        // FormUtil cannot handle List<String> or nested POJOs — parse manually.
        settings.setScopes(parseSpaceSeparated(request.getParameter("scopes")));
        settings.setAllowedEmailDomains(parseCommaSeparated(request.getParameter("allowedEmailDomains")));
        settings.setUsernameClaim(parseClaimMapping(request, "usernameClaim"));
        settings.setEmailClaim(parseClaimMapping(request, "emailClaim"));
        settings.setDisplayNameClaim(parseClaimMapping(request, "displayNameClaim"));
        settings.setHttpTimeoutSeconds(parseIntOrDefault(request.getParameter("httpTimeoutSeconds"), OidcConstants.DEFAULT_HTTP_TIMEOUT_SECONDS));
        settings.setTokenClockSkewSeconds(parseIntOrDefault(request.getParameter("tokenClockSkewSeconds"), OidcConstants.DEFAULT_CLOCK_SKEW_SECONDS));

        // clientSecret is RSA-encrypted by BS.AbstractPasswordForm and arrives as encryptedClientSecret.
        // If blank (user left the field empty), keep the existing stored secret.
        String encryptedSecret = request.getParameter("encryptedClientSecret");
        if (!isBlank(encryptedSecret)) {
            settings.setClientSecret(RSACipher.decryptWebRequestData(encryptedSecret));
        } else {
            settings.setClientSecret(settingsStorage.getSettings().getClientSecret());
        }

        ActionErrors errors = new ActionErrors();
        if (isBlank(settings.getIssuerUrl())) {
            errors.addError("issuerUrl", "Issuer URL is required");
        }
        if (isBlank(settings.getClientId())) {
            errors.addError("clientId", "Client ID is required");
        }
        if (errors.hasErrors()) {
            errors.serialize(xmlResponse);
            return;
        }

        try {
            settingsStorage.saveSettings(settings);
        } catch (Exception e) {
            errors.addError("_general", "Failed to save settings: " + e.getMessage());
            errors.serialize(xmlResponse);
        }
    }

    private static List<String> parseCommaSeparated(String value) {
        if (value == null || value.trim().isEmpty()) return Collections.emptyList();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private static List<String> parseSpaceSeparated(String value) {
        if (value == null || value.trim().isEmpty()) return Collections.emptyList();
        return Arrays.stream(value.trim().split("\\s+"))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
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
