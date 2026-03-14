package org.jetbrains.teamcity.oidc.oidc;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import jetbrains.buildServer.util.HTTPRequestBuilder;
import org.jetbrains.teamcity.oidc.InMemoryOidcPluginSettingsStorage;
import org.jetbrains.teamcity.oidc.config.OidcPluginSettings;
import org.junit.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class OidcClientTest {

    private static WireMockServer wireMock;
    private static String baseUrl;

    private OidcClient client;

    @BeforeClass
    public static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        baseUrl = "http://localhost:" + wireMock.port();
    }

    @AfterClass
    public static void stopWireMock() {
        wireMock.stop();
    }

    @Before
    public void setUp() {
        wireMock.resetAll();
        OidcPluginSettings settings = new OidcPluginSettings();
        settings.setHttpTimeoutSeconds(5);
        InMemoryOidcPluginSettingsStorage storage = new InMemoryOidcPluginSettingsStorage(settings);
        // Use TC's built-in Apache HTTP client implementation
        HTTPRequestBuilder.RequestHandler requestHandler = new HTTPRequestBuilder.ApacheClient43RequestHandler();
        client = new OidcClient(storage, requestHandler);
    }

    // ---- Discovery ---------------------------------------------------------

    @Test
    public void fetchDiscoveryDocument_success() throws Exception {
        wireMock.stubFor(get(urlEqualTo("/.well-known/openid-configuration"))
                .willReturn(okJson("{" +
                        "\"issuer\":\"https://idp.example.com\"," +
                        "\"authorization_endpoint\":\"" + baseUrl + "/auth\"," +
                        "\"token_endpoint\":\"" + baseUrl + "/token\"," +
                        "\"jwks_uri\":\"" + baseUrl + "/jwks\"" +
                        "}")));

        OidcDiscoveryDocument doc = client.fetchDiscoveryDocument(baseUrl);

        assertEquals("https://idp.example.com", doc.getIssuer());
        assertEquals(baseUrl + "/auth", doc.getAuthorizationEndpoint());
        assertEquals(baseUrl + "/token", doc.getTokenEndpoint());
        assertEquals(baseUrl + "/jwks", doc.getJwksUri());
    }

    @Test(expected = OidcClientException.class)
    public void fetchDiscoveryDocument_404() throws Exception {
        wireMock.stubFor(get(urlEqualTo("/.well-known/openid-configuration"))
                .willReturn(notFound()));
        client.fetchDiscoveryDocument(baseUrl);
    }

    @Test
    public void fetchDiscoveryDocument_isCached() throws Exception {
        wireMock.stubFor(get(urlEqualTo("/.well-known/openid-configuration"))
                .willReturn(okJson("{\"issuer\":\"https://idp.example.com\"," +
                        "\"authorization_endpoint\":\"" + baseUrl + "/auth\"," +
                        "\"token_endpoint\":\"" + baseUrl + "/token\"," +
                        "\"jwks_uri\":\"" + baseUrl + "/jwks\"}")));

        client.fetchDiscoveryDocument(baseUrl);
        client.fetchDiscoveryDocument(baseUrl); // second call — should hit cache

        wireMock.verify(1, getRequestedFor(urlEqualTo("/.well-known/openid-configuration")));
    }

    // ---- Token exchange ----------------------------------------------------

    @Test
    public void exchangeCodeForTokens_success() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/token"))
                .willReturn(okJson("{" +
                        "\"access_token\":\"at-123\"," +
                        "\"id_token\":\"it-456\"," +
                        "\"token_type\":\"Bearer\"," +
                        "\"expires_in\":3600" +
                        "}")));

        OidcTokenResponse resp = client.exchangeCodeForTokens(
                baseUrl + "/token", "mycode", "http://localhost/callback", "client-id", "secret");

        assertEquals("at-123", resp.getAccessToken());
        assertEquals("it-456", resp.getIdToken());
        assertEquals("Bearer", resp.getTokenType());
        assertEquals(3600, resp.getExpiresIn());

        wireMock.verify(postRequestedFor(urlEqualTo("/token"))
                .withRequestBody(containing("grant_type=authorization_code"))
                .withRequestBody(containing("code=mycode"))
                .withRequestBody(containing("client_id=client-id")));
    }

    @Test(expected = OidcClientException.class)
    public void exchangeCodeForTokens_invalidClient() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/token"))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"invalid_client\",\"error_description\":\"Client authentication failed\"}")));

        client.exchangeCodeForTokens(
                baseUrl + "/token", "code", "http://localhost/cb", "bad-client", "bad-secret");
    }

    @Test
    public void exchangeCodeForTokens_exceptionCarriesIdpError() {
        wireMock.stubFor(post(urlEqualTo("/token"))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"invalid_grant\"}")));

        try {
            client.exchangeCodeForTokens(baseUrl + "/token", "code", "http://localhost/cb", "c", "s");
            fail("Expected OidcClientException");
        } catch (OidcClientException e) {
            assertEquals("invalid_grant", e.getIdpError());
            assertEquals(400, e.getHttpStatus());
        }
    }

    // ---- UserInfo ----------------------------------------------------------

    @Test
    public void fetchUserInfo_success() throws Exception {
        wireMock.stubFor(get(urlEqualTo("/userinfo"))
                .withHeader("Authorization", equalTo("Bearer mytoken"))
                .willReturn(okJson("{" +
                        "\"sub\":\"user-123\"," +
                        "\"email\":\"user@example.com\"," +
                        "\"name\":\"Test User\"," +
                        "\"preferred_username\":\"testuser\"" +
                        "}")));

        OidcUserInfo info = client.fetchUserInfo(baseUrl + "/userinfo", "mytoken");

        assertEquals("user-123", info.getSub());
        assertEquals("user@example.com", info.getEmail());
        assertEquals("Test User", info.getName());
        assertEquals("testuser", info.getPreferredUsername());
    }

    @Test(expected = OidcClientException.class)
    public void fetchUserInfo_unauthorized() throws Exception {
        wireMock.stubFor(get(urlEqualTo("/userinfo"))
                .willReturn(aResponse().withStatus(401)));
        client.fetchUserInfo(baseUrl + "/userinfo", "bad-token");
    }

    // ---- JWKS --------------------------------------------------------------

    @Test
    public void fetchJwks_success() throws Exception {
        String jwks = "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"k1\",\"n\":\"abc\",\"e\":\"AQAB\"}]}";
        wireMock.stubFor(get(urlEqualTo("/jwks"))
                .willReturn(okJson(jwks)));

        String result = client.fetchJwks(baseUrl + "/jwks");
        assertTrue(result.contains("\"kty\""));
    }
}
