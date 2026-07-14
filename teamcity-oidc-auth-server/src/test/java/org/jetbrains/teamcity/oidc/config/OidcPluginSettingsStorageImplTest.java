package org.jetbrains.teamcity.oidc.config;

import jetbrains.buildServer.TempFiles;
import jetbrains.buildServer.configuration.FileWatcher;
import jetbrains.buildServer.serverSide.PersistTask;
import jetbrains.buildServer.serverSide.ServerPaths;
import jetbrains.buildServer.serverSide.SettingsPersister;
import jetbrains.buildServer.serverSide.impl.CriticalErrorsImpl;
import jetbrains.buildServer.serverSide.impl.FileWatcherFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class OidcPluginSettingsStorageImplTest {

    private ServerPaths serverPaths;
    private OidcPluginSettingsStorageImpl storage;
    private SettingsPersister serverSettings;
    private FileWatcherFactory fileWatcherFactory;
    private TempFiles tempFiles;
    private File configDir;

    @Before
    public void setUp() throws Exception {
        tempFiles = new TempFiles();
        serverPaths = mock(ServerPaths.class);
        serverSettings = mock(SettingsPersister.class);
        PersistTask persistTask = mock(PersistTask.class);
        File dataDir = tempFiles.createTempDir();
        configDir = new File(dataDir, "config");
        configDir.mkdirs();
        when(serverPaths.getConfigDir()).thenReturn(configDir.getAbsolutePath());
        when(serverPaths.getDataDirectory()).thenReturn(dataDir);
        when(serverSettings.scheduleSaveFile(eq(OidcPluginSettingsStorageImpl.SAVE_CONFIG_DESCRIPTION), any(FileWatcher.class), any(byte[].class))).thenReturn(persistTask);

        fileWatcherFactory = new FileWatcherFactory(serverPaths, new CriticalErrorsImpl(serverPaths));
        storage = new OidcPluginSettingsStorageImpl(serverPaths, serverSettings, fileWatcherFactory);
        storage.init();
    }

    @After
    public void tearDown() {
        tempFiles.cleanup();
    }

    @Test
    public void loadsDefaultsWhenFileAbsent() {
        OidcPluginSettingsStorageImpl fresh = new OidcPluginSettingsStorageImpl(serverPaths, serverSettings, fileWatcherFactory);
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

        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(serverSettings, atLeast(2)).scheduleSaveFile(
                eq(OidcPluginSettingsStorageImpl.SAVE_CONFIG_DESCRIPTION),
                any(FileWatcher.class),
                bytesCaptor.capture()
        );

        assertEquals(storage.getSettings().getIssuerUrl(), toSave.getIssuerUrl());

        byte[] actualBytes = bytesCaptor.getAllValues().get(1);
        String savedJson = new String(actualBytes, StandardCharsets.UTF_8);

        assertTrue(savedJson, savedJson.contains(toSave.getIssuerUrl()));
    }

    @Test
    public void hotReloadOnFileChange() throws IOException {
        OidcPluginSettings initial = new OidcPluginSettings();
        initial.setIssuerUrl("https://original.example.com");
        storage.saveSettings(initial);

        assertEquals("https://original.example.com", storage.getSettings().getIssuerUrl());

        // Simulate external file modification
        File configFile = new File(configDir, "oidc-auth-plugin.json");
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
        File configFile = new File(configDir, "oidc-auth-plugin.json");
        String json = "{\"issuerUrl\":\"https://idp.example.com\",\"unknownFutureField\":\"someValue\"}";
        Files.write(configFile.toPath(), json.getBytes());

        OidcPluginSettingsStorageImpl fresh = new OidcPluginSettingsStorageImpl(serverPaths, serverSettings, fileWatcherFactory);
        OidcPluginSettings settings = fresh.getSettings();

        assertEquals("https://idp.example.com", settings.getIssuerUrl());
    }
}
