package org.jetbrains.teamcity.oidc.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jetbrains.buildServer.configuration.FileWatcher;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.serverSide.ServerPaths;
import jetbrains.buildServer.serverSide.SettingsPersister;
import jetbrains.buildServer.serverSide.crypt.Encryption;
import jetbrains.buildServer.serverSide.impl.FileWatcherFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.teamcity.oidc.OidcConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

public class OidcPluginSettingsStorageImpl implements OidcPluginSettingsStorage {

    public static final String SAVE_CONFIG_DESCRIPTION = "Saving OIDC Auth plugin configuration";
    private final File configFile;
    private final ObjectMapper objectMapper;
    private final Encryption encryption;
    private final FileWatcher fileWatcher;
    private final SettingsPersister settingsPersister;

    private volatile OidcPluginSettings cachedSettings;
    private final Object lock = new Object();

    public OidcPluginSettingsStorageImpl(@NotNull ServerPaths serverPaths,
                                         @NotNull SettingsPersister settingsPersister,
                                         @NotNull FileWatcherFactory fileWatcherFactory,
                                         @NotNull Encryption encryption) {
        this.configFile = Paths.get(serverPaths.getConfigDir(), OidcConstants.CONFIG_FILE_NAME).toFile();
        this.objectMapper = new ObjectMapper();
        this.encryption = encryption;
        this.fileWatcher = fileWatcherFactory.createFileWatcher(configFile);
        this.settingsPersister = settingsPersister;
    }

    /** Called once by {@link org.jetbrains.teamcity.oidc.OidcPluginConfiguration} after construction. */
    public void init() throws IOException {
        reloadInternal();
        fileWatcher.registerListener(changed -> {
            try {
                reloadInternal();
            } catch (IOException e) {
                Loggers.AUTH.error("OIDC: failed to reload settings on file change", e);
            }
        });
        fileWatcher.start();
    }

    @NotNull
    @Override
    public OidcPluginSettings getSettings() {
        if (cachedSettings != null) return cachedSettings;
        synchronized (lock) {
            if (cachedSettings != null) return cachedSettings;
            try {
                return reloadInternal();
            } catch (IOException e) {
                Loggers.AUTH.error("OIDC: failed to load settings, using defaults", e);
                cachedSettings = new OidcPluginSettings();
                return cachedSettings;
            }
        }
    }

    @Override
    public void saveSettings(@NotNull OidcPluginSettings settings) throws IOException {
        synchronized (lock) {
            try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
                objectMapper
                        .writerWithDefaultPrettyPrinter()
                        .withAttribute(OidcSecretSerializer.ENCRYPTION_CTX_KEY, encryption)
                        .writeValue(os, settings);
                settingsPersister.scheduleSaveFile(SAVE_CONFIG_DESCRIPTION, fileWatcher, os.toByteArray());
            }
            cachedSettings = settings;
        }
    }

    /** Exposed for testing; normally called by the FileWatcher listener. */
    public void reload() throws IOException {
        reloadInternal();
    }

    @NotNull
    private OidcPluginSettings reloadInternal() throws IOException {
        if (!configFile.exists() || configFile.length() == 0) {
            OidcPluginSettings defaults = new OidcPluginSettings();
            saveSettings(defaults);
            return defaults;
        }
        try {
            OidcPluginSettings result = objectMapper
                    .readerFor(OidcPluginSettings.class)
                    .withAttribute(OidcSecretSerializer.ENCRYPTION_CTX_KEY, encryption)
                    .readValue(configFile);
            cachedSettings = result;
            Loggers.AUTH.debug("OIDC: settings loaded from " + configFile.getAbsolutePath());
            return result;
        } catch (IOException e) {
            Loggers.AUTH.error("OIDC: failed to parse settings file " + configFile.getAbsolutePath(), e);
            throw e;
        }
    }
}
