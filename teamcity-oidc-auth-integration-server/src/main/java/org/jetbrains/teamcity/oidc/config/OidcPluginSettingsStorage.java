package org.jetbrains.teamcity.oidc.config;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public interface OidcPluginSettingsStorage {

    /**
     * Returns current settings. Never null.
     * Returns default settings when the config file is absent.
     */
    @NotNull
    OidcPluginSettings getSettings();

    /** Persists settings to disk and updates the in-memory cache. */
    void saveSettings(@NotNull OidcPluginSettings settings) throws IOException;
}
