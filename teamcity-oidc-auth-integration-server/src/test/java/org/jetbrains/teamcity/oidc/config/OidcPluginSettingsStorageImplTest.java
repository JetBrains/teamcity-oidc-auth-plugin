package org.jetbrains.teamcity.oidc.config;

import jetbrains.buildServer.serverSide.ServerPaths;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class OidcPluginSettingsStorageImplTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    private ServerPaths serverPaths;
    private OidcPluginSettingsStorageImpl storage;

    @Before
    public void setUp() throws Exception {
        serverPaths = mock(ServerPaths.class);
        when(serverPaths.getConfigDir()).thenReturn(tmpDir.getRoot().getAbsolutePath());
        storage = new OidcPluginSettingsStorageImpl(serverPaths);
        storage.init();
    }

    @Test
    public void loadsDefaultsWhenFileAbsent() {
        // init() writes a default file; delete it and create a new storage instance
        File configFile = new File(tmpDir.getRoot(), "oidc-auth-plugin.json");
        assertTrue(configFile.delete());

        OidcPluginSettingsStorageImpl fresh = new OidcPluginSettingsStorageImpl(serverPaths);
        OidcPluginSettings settings = fresh.getSettings();

        assertNotNull(settings);
        assertTrue(settings.isDiscoveryEnabled());
        assertFalse(settings.isCreateUsersAutomatically());
        assertNotNull(settings.getScopes());
        assertFalse(settings.getScopes().isEmpty());
    }

    @Test
    public void savesAndReloadsSettings() throws IOException {
        OidcPluginSettings toSave = new OidcPluginSettings();
        toSave.setIssuerUrl("https://idp.example.com/realms/test");
        toSave.setClientId("my-client");
        toSave.setClientSecret("secret123");
        toSave.setCreateUsersAutomatically(true);

        storage.saveSettings(toSave);

        // Create a new instance pointing at the same file to verify persistence
        OidcPluginSettingsStorageImpl reloaded = new OidcPluginSettingsStorageImpl(serverPaths);
        OidcPluginSettings loaded = reloaded.getSettings();

        assertEquals("https://idp.example.com/realms/test", loaded.getIssuerUrl());
        assertEquals("my-client", loaded.getClientId());
        assertEquals("secret123", loaded.getClientSecret());
        assertTrue(loaded.isCreateUsersAutomatically());
    }

    @Test
    public void hotReloadOnFileChange() throws IOException {
        OidcPluginSettings initial = new OidcPluginSettings();
        initial.setIssuerUrl("https://original.example.com");
        storage.saveSettings(initial);

        assertEquals("https://original.example.com", storage.getSettings().getIssuerUrl());

        // Simulate external file modification
        File configFile = new File(tmpDir.getRoot(), "oidc-auth-plugin.json");
        String newJson = "{\"issuerUrl\":\"https://new.example.com\"}";
        Files.write(configFile.toPath(), newJson.getBytes());

        // Force reload (normally triggered by FileWatcher)
        storage.reload();

        assertEquals("https://new.example.com", storage.getSettings().getIssuerUrl());
    }

    @Test
    public void defaultScopesApplied() {
        OidcPluginSettings settings = storage.getSettings();
        assertNotNull(settings.getScopes());
        assertTrue(settings.getScopes().contains("openid"));
        assertTrue(settings.getScopes().contains("email"));
        assertTrue(settings.getScopes().contains("profile"));
    }

    @Test
    public void jacksonIgnoresUnknownFields() throws IOException {
        File configFile = new File(tmpDir.getRoot(), "oidc-auth-plugin.json");
        String json = "{\"issuerUrl\":\"https://idp.example.com\",\"unknownFutureField\":\"someValue\"}";
        Files.write(configFile.toPath(), json.getBytes());

        OidcPluginSettingsStorageImpl fresh = new OidcPluginSettingsStorageImpl(serverPaths);
        OidcPluginSettings settings = fresh.getSettings();

        assertEquals("https://idp.example.com", settings.getIssuerUrl());
    }
}
