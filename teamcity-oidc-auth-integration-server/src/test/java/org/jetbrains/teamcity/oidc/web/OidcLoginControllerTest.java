package org.jetbrains.teamcity.oidc.web;

import jetbrains.buildServer.RootUrlHolder;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.jetbrains.teamcity.oidc.InMemoryOidcPluginSettingsStorage;
import org.jetbrains.teamcity.oidc.auth.OidcStateManager;
import org.jetbrains.teamcity.oidc.config.OidcPluginSettings;
import org.jetbrains.teamcity.oidc.oidc.OidcClient;
import org.jetbrains.teamcity.oidc.oidc.OidcDiscoveryDocument;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.net.URLDecoder;
import java.util.Arrays;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class OidcLoginControllerTest {

    private static final String ROOT_URL = "http://localhost:8111";
    private static final String AUTH_ENDPOINT = "https://idp.example.com/auth";

    private OidcPluginSettings settings;
    private InMemoryOidcPluginSettingsStorage storage;
    private OidcClient mockClient;
    private OidcStateManager stateManager;
    private RootUrlHolder mockRootUrl;
    private OidcLoginController controller;

    @Before
    public void setUp() throws Exception {
        settings = new OidcPluginSettings();
        settings.setIssuerUrl("https://idp.example.com");
        settings.setClientId("teamcity");
        settings.setClientSecret("secret");
        settings.setDiscoveryEnabled(false);
        settings.setAuthorizationEndpoint(AUTH_ENDPOINT);
        storage = new InMemoryOidcPluginSettingsStorage(settings);

        mockClient = mock(OidcClient.class);
        stateManager = new OidcStateManager(); // use real state manager
        mockRootUrl = mock(RootUrlHolder.class);
        when(mockRootUrl.getRootUrl()).thenReturn(ROOT_URL);

        OidcDiscoveryDocument mockDoc = mock(OidcDiscoveryDocument.class);
        when(mockDoc.getAuthorizationEndpoint()).thenReturn(AUTH_ENDPOINT);
        when(mockClient.fetchDiscoveryDocument(anyString())).thenReturn(mockDoc);

        controller = new OidcLoginController(
                mock(SBuildServer.class), mock(WebControllerManager.class),
                storage, mockClient, stateManager, mockRootUrl);
    }

    private MockHttpServletResponse handle(MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.doHandle(req, resp);
        return resp;
    }

    @Test
    public void redirectsToAuthorizationEndpoint() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/app/oidc/login");
        req.setSession(new MockHttpSession());
        MockHttpServletResponse resp = handle(req);

        assertEquals(302, resp.getStatus());
        String location = resp.getRedirectedUrl();
        assertNotNull(location);
        assertTrue("Should redirect to auth endpoint: " + location, location.startsWith(AUTH_ENDPOINT));
    }

    @Test
    public void redirectUrlContainsRequiredParams() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/app/oidc/login");
        req.setSession(new MockHttpSession());
        MockHttpServletResponse resp = handle(req);

        String url = resp.getRedirectedUrl();
        assertNotNull(url);
        assertTrue("Missing response_type", url.contains("response_type=code"));
        assertTrue("Missing client_id", url.contains("client_id=teamcity"));
        assertTrue("Missing redirect_uri", url.contains("redirect_uri="));
        assertTrue("Missing scope", url.contains("scope="));
        assertTrue("Missing state", url.contains("state="));
        assertTrue("Missing nonce", url.contains("nonce="));
    }

    @Test
    public void stateAndNonceStoredInSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/app/oidc/login");
        req.setSession(session);
        handle(req);

        assertNotNull(session.getAttribute("oidc.state"));
        assertNotNull(session.getAttribute("oidc.nonce"));
    }

    @Test
    public void missingClientIdReturns500() throws Exception {
        settings.setClientId(null);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/app/oidc/login");
        req.setSession(new MockHttpSession());
        MockHttpServletResponse resp = handle(req);

        assertEquals(500, resp.getStatus());
    }

    @Test
    public void missingIssuerUrlReturns500() throws Exception {
        settings.setIssuerUrl(null);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/app/oidc/login");
        req.setSession(new MockHttpSession());
        MockHttpServletResponse resp = handle(req);

        assertEquals(500, resp.getStatus());
    }

    @Test
    public void scopesAreConcatenatedWithSpaces() throws Exception {
        settings.setScopes(Arrays.asList("openid", "email", "profile"));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/app/oidc/login");
        req.setSession(new MockHttpSession());
        MockHttpServletResponse resp = handle(req);

        String url = resp.getRedirectedUrl();
        assertNotNull(url);
        // Find scope param and decode it
        int scopeIdx = url.indexOf("scope=");
        assertTrue(scopeIdx >= 0);
        String scopeEncoded = url.substring(scopeIdx + 6).split("&")[0];
        String scope = URLDecoder.decode(scopeEncoded, "UTF-8");
        assertEquals("openid email profile", scope);
    }

    @Test
    public void callbackUrlUsesConfiguredBase() throws Exception {
        settings.setCallbackBaseUrl("https://tc.example.com");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/app/oidc/login");
        req.setSession(new MockHttpSession());
        MockHttpServletResponse resp = handle(req);

        String url = resp.getRedirectedUrl();
        assertNotNull(url);
        assertTrue("redirect_uri should use configured callbackBaseUrl: " + url,
                url.contains(URLDecoder.decode("https://tc.example.com/app/oidc/callback", "UTF-8"))
                || url.contains("tc.example.com"));
    }

    @Test
    public void callbackUrlFallsBackToRootUrlHolder() throws Exception {
        settings.setCallbackBaseUrl(null);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/app/oidc/login");
        req.setSession(new MockHttpSession());
        MockHttpServletResponse resp = handle(req);

        String url = resp.getRedirectedUrl();
        assertNotNull(url);
        assertTrue("redirect_uri should fall back to RootUrlHolder: " + url,
                url.contains("localhost%3A8111") || url.contains("localhost:8111"));
    }
}
