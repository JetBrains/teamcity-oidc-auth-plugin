package org.jetbrains.teamcity.oidc.oidc;

import com.fasterxml.jackson.databind.ObjectMapper;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.serverSide.IOGuard;
import jetbrains.buildServer.util.HTTPRequestBuilder;
import jetbrains.buildServer.util.http.HttpMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.teamcity.oidc.OidcConstants;
import org.jetbrains.teamcity.oidc.config.OidcPluginSettingsStorage;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HTTP client for all communication with the Identity Provider.
 * Uses TeamCity's {@link HTTPRequestBuilder} with {@link IOGuard} for network access.
 */
public class OidcClient {

    private final OidcPluginSettingsStorage settingsStorage;
    private final HTTPRequestBuilder.RequestHandler requestHandler;
    private final ObjectMapper objectMapper;

    /** Simple pair used for the discovery document cache. */
    private volatile CachedDiscovery discoveryCache;

    public OidcClient(
            @NotNull OidcPluginSettingsStorage settingsStorage,
            @NotNull HTTPRequestBuilder.RequestHandler requestHandler) {
        this.settingsStorage = settingsStorage;
        this.requestHandler = requestHandler;
        this.objectMapper = new ObjectMapper();
    }

    // ---- Public API --------------------------------------------------------

    /**
     * Fetches and parses the OIDC discovery document.
     * Result is cached keyed by issuerUrl; cache is invalidated when issuerUrl changes.
     */
    @NotNull
    public OidcDiscoveryDocument fetchDiscoveryDocument(@NotNull String issuerUrl) throws OidcClientException {
        CachedDiscovery cached = discoveryCache;
        if (cached != null && cached.issuerUrl.equals(issuerUrl)) {
            return cached.document;
        }
        String discoveryUrl = issuerUrl + OidcConstants.DISCOVERY_PATH;
        Loggers.SERVER.debug("OIDC: fetching discovery document from " + discoveryUrl);
        OidcDiscoveryDocument doc = doGet(discoveryUrl, OidcDiscoveryDocument.class,
                settingsStorage.getSettings().getHttpTimeoutSeconds() * 1000);
        discoveryCache = new CachedDiscovery(issuerUrl, doc);
        return doc;
    }

    /** Forces the discovery document cache to be cleared. */
    public void invalidateDiscoveryCache() {
        discoveryCache = null;
    }

    /**
     * Exchanges an authorization code for tokens at the token endpoint.
     * POST application/x-www-form-urlencoded.
     */
    @NotNull
    public OidcTokenResponse exchangeCodeForTokens(
            @NotNull String tokenEndpoint,
            @NotNull String code,
            @NotNull String redirectUri,
            @NotNull String clientId,
            @NotNull String clientSecret) throws OidcClientException {
        Loggers.SERVER.debug("OIDC: exchanging authorization code at " + tokenEndpoint);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        int timeout = settingsStorage.getSettings().getHttpTimeoutSeconds() * 1000;
        return doPost(tokenEndpoint, form, OidcTokenResponse.class, timeout);
    }

    /**
     * Calls the userinfo endpoint with a Bearer access token.
     */
    @NotNull
    public OidcUserInfo fetchUserInfo(@NotNull String userInfoEndpoint, @NotNull String accessToken) throws OidcClientException {
        Loggers.SERVER.debug("OIDC: fetching userinfo from " + userInfoEndpoint);
        int timeout = settingsStorage.getSettings().getHttpTimeoutSeconds() * 1000;
        return doGetWithBearer(userInfoEndpoint, accessToken, OidcUserInfo.class, timeout);
    }

    /**
     * Fetches the raw JWKS JSON string for ID token signature verification.
     */
    @NotNull
    public String fetchJwks(@NotNull String jwksUri) throws OidcClientException {
        Loggers.SERVER.debug("OIDC: fetching JWKS from " + jwksUri);
        int timeout = settingsStorage.getSettings().getHttpTimeoutSeconds() * 1000;
        return doGetRaw(jwksUri, timeout);
    }

    // ---- Internal helpers --------------------------------------------------

    @NotNull
    private <T> T doGet(@NotNull String url, @NotNull Class<T> responseType, int timeoutMs) throws OidcClientException {
        String body = doGetRaw(url, timeoutMs);
        return parseJson(body, responseType, url);
    }

    @NotNull
    private <T> T doGetWithBearer(@NotNull String url, @NotNull String token,
                                  @NotNull Class<T> responseType, int timeoutMs) throws OidcClientException {
        AtomicReference<String> bodyRef = new AtomicReference<>();
        AtomicReference<OidcClientException> errorRef = new AtomicReference<>();

        HTTPRequestBuilder builder = buildRequest(url, timeoutMs);
        builder.withMethod(HttpMethod.GET)
               .withHeader("Authorization", "Bearer " + token)
               .withHeader("Accept", "application/json")
               .onErrorResponse(response -> {
                   int status = response.getStatusCode();
                   String respBody = response.getBodyAsString(StandardCharsets.UTF_8.name());
                   errorRef.set(new OidcClientException(
                           "UserInfo request failed with status " + status + " from " + url,
                           status, extractIdpError(respBody), null));
               })
               .onSuccess(response ->
                   bodyRef.set(response.getBodyAsString(StandardCharsets.UTF_8.name())));

        executeRequest(builder, url);
        if (errorRef.get() != null) throw errorRef.get();
        return parseJson(bodyRef.get(), responseType, url);
    }

    @NotNull
    private String doGetRaw(@NotNull String url, int timeoutMs) throws OidcClientException {
        AtomicReference<String> bodyRef = new AtomicReference<>();
        AtomicReference<OidcClientException> errorRef = new AtomicReference<>();

        HTTPRequestBuilder builder = buildRequest(url, timeoutMs);
        builder.withMethod(HttpMethod.GET)
               .withHeader("Accept", "application/json")
               .onErrorResponse(response -> {
                   int status = response.getStatusCode();
                   String respBody = response.getBodyAsString(StandardCharsets.UTF_8.name());
                   errorRef.set(new OidcClientException(
                           "GET request failed with status " + status + " from " + url,
                           status, extractIdpError(respBody), null));
               })
               .onSuccess(response ->
                   bodyRef.set(response.getBodyAsString(StandardCharsets.UTF_8.name())));

        executeRequest(builder, url);
        if (errorRef.get() != null) throw errorRef.get();
        String body = bodyRef.get();
        if (body == null) throw new OidcClientException("Empty response from " + url);
        return body;
    }

    @NotNull
    private <T> T doPost(@NotNull String url, @NotNull Map<String, String> formData,
                         @NotNull Class<T> responseType, int timeoutMs) throws OidcClientException {
        AtomicReference<String> bodyRef = new AtomicReference<>();
        AtomicReference<OidcClientException> errorRef = new AtomicReference<>();

        HTTPRequestBuilder builder = buildRequest(url, timeoutMs);
        builder.withMethod(HttpMethod.POST)
               .withHeader("Accept", "application/json")
               .withHeader("Content-Type", "application/x-www-form-urlencoded")
               .withData(formData)
               .onErrorResponse(response -> {
                   int status = response.getStatusCode();
                   String respBody = response.getBodyAsString(StandardCharsets.UTF_8.name());
                   errorRef.set(new OidcClientException(
                           "POST request failed with status " + status + " from " + url,
                           status, extractIdpError(respBody), null));
               })
               .onSuccess(response ->
                   bodyRef.set(response.getBodyAsString(StandardCharsets.UTF_8.name())));

        executeRequest(builder, url);
        if (errorRef.get() != null) throw errorRef.get();
        return parseJson(bodyRef.get(), responseType, url);
    }

    @NotNull
    private HTTPRequestBuilder buildRequest(@NotNull String url, int timeoutMs) {
        try {
            return HTTPRequestBuilder.request(url)
                    .allowNonSecureConnection(true)
                    .withTimeout(timeoutMs)
                    .onException(e -> Loggers.SERVER.warnAndDebugDetails("OIDC: HTTP request error for " + url, e));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL: " + url, e);
        }
    }

    private void executeRequest(@NotNull HTTPRequestBuilder builder, @NotNull String url) throws OidcClientException {
        try {
            HTTPRequestBuilder.Request request = builder.build();
            IOGuard.allowNetworkCall(() -> requestHandler.doRequest(request));
        } catch (Exception e) {
            throw new OidcClientException("Network error communicating with IdP at " + url, e);
        }
    }

    @NotNull
    private <T> T parseJson(@Nullable String body, @NotNull Class<T> type, @NotNull String url) throws OidcClientException {
        if (body == null || body.isEmpty()) {
            throw new OidcClientException("Empty response body from " + url);
        }
        try {
            return objectMapper.readValue(body, type);
        } catch (Exception e) {
            Loggers.SERVER.debug("OIDC: failed to parse response from " + url + ": " + body);
            throw new OidcClientException("Failed to parse response from " + url + ": " + e.getMessage(), e);
        }
    }

    @Nullable
    private String extractIdpError(@Nullable String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(body, Map.class);
            Object error = map.get("error");
            return error != null ? error.toString() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    // ---- Inner types -------------------------------------------------------

    private static final class CachedDiscovery {
        final String issuerUrl;
        final OidcDiscoveryDocument document;

        CachedDiscovery(String issuerUrl, OidcDiscoveryDocument document) {
            this.issuerUrl = issuerUrl;
            this.document = document;
        }
    }
}
