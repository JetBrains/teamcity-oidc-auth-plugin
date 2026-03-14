package org.jetbrains.teamcity.oidc;

import jetbrains.buildServer.RootUrlHolder;
import jetbrains.buildServer.controllers.AuthorizationInterceptor;
import jetbrains.buildServer.groups.UserGroupManager;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.ServerPaths;
import jetbrains.buildServer.serverSide.auth.LoginConfiguration;
import jetbrains.buildServer.users.UserModel;
import jetbrains.buildServer.util.HTTPRequestBuilder;
import jetbrains.buildServer.web.openapi.PagePlaces;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.teamcity.oidc.auth.OidcAuthenticationScheme;
import org.jetbrains.teamcity.oidc.auth.OidcStateManager;
import org.jetbrains.teamcity.oidc.config.OidcPluginSettingsStorage;
import org.jetbrains.teamcity.oidc.config.OidcPluginSettingsStorageImpl;
import org.jetbrains.teamcity.oidc.oidc.OidcClient;
import org.jetbrains.teamcity.oidc.oidc.OidcIdTokenValidator;
import org.jetbrains.teamcity.oidc.web.OidcCallbackController;
import org.jetbrains.teamcity.oidc.web.OidcLoginController;
import org.jetbrains.teamcity.oidc.web.OidcLoginPageExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Clock;

@Configuration
@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
public class OidcPluginConfiguration {

    @Bean
    public OidcPluginSettingsStorageImpl oidcSettingsStorage(@NotNull ServerPaths serverPaths) throws IOException {
        OidcPluginSettingsStorageImpl storage = new OidcPluginSettingsStorageImpl(serverPaths);
        storage.init();
        return storage;
    }

    @Bean
    public OidcClient oidcClient(
            @NotNull OidcPluginSettingsStorage settingsStorage,
            @NotNull HTTPRequestBuilder.RequestHandler requestHandler) {
        return new OidcClient(settingsStorage, requestHandler);
    }

    @Bean
    public OidcIdTokenValidator oidcIdTokenValidator(@NotNull OidcClient oidcClient) {
        return new OidcIdTokenValidator(oidcClient, Clock.systemUTC());
    }

    @Bean
    public OidcStateManager oidcStateManager() {
        return new OidcStateManager();
    }

    @Bean
    public OidcAuthenticationScheme oidcAuthenticationScheme(
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
        OidcAuthenticationScheme scheme = new OidcAuthenticationScheme(
                loginConfiguration, settingsStorage, oidcClient, tokenValidator, stateManager,
                userModel, userGroupManager, rootUrlHolder, webControllerManager, authInterceptor);
        loginConfiguration.registerAuthModuleType(scheme);
        return scheme;
    }

    @Bean
    public OidcLoginController oidcLoginController(
            @NotNull SBuildServer server,
            @NotNull WebControllerManager webControllerManager,
            @NotNull OidcPluginSettingsStorage settingsStorage,
            @NotNull OidcClient oidcClient,
            @NotNull OidcStateManager stateManager,
            @NotNull RootUrlHolder rootUrlHolder) {
        return new OidcLoginController(server, webControllerManager, settingsStorage, oidcClient, stateManager, rootUrlHolder);
    }

    @Bean
    public OidcCallbackController oidcCallbackController(
            @NotNull SBuildServer server,
            @NotNull WebControllerManager webControllerManager) {
        return new OidcCallbackController(server, webControllerManager);
    }

    @Bean
    public OidcLoginPageExtension oidcLoginPageExtension(
            @NotNull PagePlaces pagePlaces,
            @NotNull PluginDescriptor pluginDescriptor,
            @NotNull OidcPluginSettingsStorage settingsStorage,
            @NotNull OidcAuthenticationScheme scheme) {
        return new OidcLoginPageExtension(pagePlaces, pluginDescriptor, settingsStorage, scheme);
    }
}
