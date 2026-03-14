package org.jetbrains.teamcity.oidc;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.teamcity.oidc.config.OidcPluginSettings;
import org.jetbrains.teamcity.oidc.config.OidcPluginSettingsStorage;

/**
 * In-memory test double for {@link OidcPluginSettingsStorage}.
 * saveSettings() simply replaces the in-memory reference.
 */
public class InMemoryOidcPluginSettingsStorage implements OidcPluginSettingsStorage {

    private OidcPluginSettings settings;

    public InMemoryOidcPluginSettingsStorage() {
        this.settings = new OidcPluginSettings();
    }

    public InMemoryOidcPluginSettingsStorage(@NotNull OidcPluginSettings initial) {
        this.settings = initial;
    }

    @NotNull
    @Override
    public OidcPluginSettings getSettings() {
        return settings;
    }

    @Override
    public void saveSettings(@NotNull OidcPluginSettings settings) {
        this.settings = settings;
    }
}
