# TeamCity OIDC Authentication Plugin — Architecture Plan

## Overview

A TeamCity server-side plugin providing authentication via OpenID Connect (OIDC) / OAuth 2.0
Authorization Code Flow. Configuration is entirely file-based (no UI). The plugin registers an
`HttpAuthenticationScheme` that intercepts login requests, redirects users to the configured
Identity Provider (IdP), processes the authorization callback, validates the ID token, and
resolves/creates TeamCity users based on configurable claim mappings.

---

## Authentication Flow

```
Browser → TeamCity Login Page
  → GET /app/oidc/login
    → build authorization URL (with state & nonce)
    → store state + nonce in session
    → redirect to IdP authorization endpoint
  → IdP authentication & consent
  → GET /app/oidc/callback?code=...&state=...
    → validate state (anti-CSRF)
    → POST to token endpoint → TokenResponse (id_token, access_token)
    → validate id_token JWT (signature, iss, aud, exp, nonce)
    → optionally GET /userinfo for additional claims
    → resolve or create TeamCity user from claims
    → establish TeamCity session
```

---

## Configuration (file-based)

Config file location: `{teamcityDataDirectory}/config/oidc-auth-plugin.json`

The file is watched for changes at runtime (no restart required). All configuration is read from
this file; there is no database or TeamCity connection settings involved.

### Settings fields

| Field | Type | Description |
|---|---|---|
| `issuerUrl` | string | OIDC Issuer URL. Used to fetch discovery document at `{issuerUrl}/.well-known/openid-configuration`. If `discoveryEnabled` is false, individual endpoint URLs must be provided explicitly. |
| `discoveryEnabled` | boolean | Default `true`. When `true`, authorization/token/userinfo endpoints are resolved from the discovery document. |
| `authorizationEndpoint` | string | Authorization endpoint URL (required if discovery disabled). |
| `tokenEndpoint` | string | Token endpoint URL (required if discovery disabled). |
| `userInfoEndpoint` | string | UserInfo endpoint URL (optional, used to supplement ID token claims). |
| `jwksUri` | string | JWKS endpoint URL (required if discovery disabled, for ID token validation). |
| `clientId` | string | OAuth 2.0 client ID. |
| `clientSecret` | string | OAuth 2.0 client secret (stored in plaintext in the config file; protect via OS-level file permissions). |
| `scopes` | string[] | OAuth 2.0 scopes to request. Default: `["openid", "email", "profile"]`. |
| `callbackBaseUrl` | string | Base URL of this TeamCity instance as seen from the browser. Used to construct the redirect URI. If absent, derived from TeamCity `RootUrlHolder`. |
| `createUsersAutomatically` | boolean | If `true`, a TeamCity user is created on first login. Default `false`. |
| `allowedEmailDomains` | string[] | If non-empty, only emails with these domains are permitted to log in or be auto-created. |
| `assignGroups` | boolean | If `true`, sync IdP group claims to TeamCity groups. Default `false`. |
| `removeUnassignedGroups` | boolean | If `true` and `assignGroups` is `true`, remove user from TeamCity groups that are not present in the IdP claim. Default `false`. |
| `groupsClaimName` | string | Name of the JWT/userinfo claim that contains group list. Default: `"groups"`. |
| `usernameClaim` | `OidcClaimMappingSettings` | How to resolve the TeamCity username from token claims. |
| `emailClaim` | `OidcClaimMappingSettings` | How to resolve the user's email. |
| `displayNameClaim` | `OidcClaimMappingSettings` | How to resolve the user's display name. |
| `httpTimeoutSeconds` | integer | Timeout for HTTP calls to IdP endpoints. Default `30`. |
| `tokenClockSkewSeconds` | integer | Allowed clock skew when validating `iat`/`exp` in ID token. Default `30`. |

### OidcClaimMappingSettings fields

| Field | Type | Description |
|---|---|---|
| `mappingType` | enum | One of: `sub` (subject), `claim` (named claim), `none`. |
| `claimName` | string | Claim name to use when `mappingType` is `claim`. |

---

## Package Structure

All production code lives under `org.jetbrains.teamcity.oidc`.

```
org.jetbrains.teamcity.oidc
├── config/
│   ├── OidcPluginSettings          (data class — full plugin configuration)
│   ├── OidcClaimMappingSettings    (data class — single claim mapping)
│   ├── OidcPluginSettingsStorage   (interface — read/write settings)
│   └── OidcPluginSettingsStorageImpl (implementation — JSON file on disk)
│
├── oidc/
│   ├── OidcDiscoveryDocument       (data class — parsed well-known response)
│   ├── OidcTokenResponse           (data class — token endpoint response)
│   ├── OidcIdTokenClaims           (data class — parsed JWT payload)
│   ├── OidcUserInfo                (data class — userinfo endpoint response)
│   ├── OidcClient                  (service — HTTP client for all IdP calls)
│   ├── OidcIdTokenValidator        (service — JWT signature & claims validation)
│   ├── OidcClientException         (checked exception — IdP HTTP/network failures)
│   └── OidcAuthException           (checked exception — authentication logic failures)
│
├── auth/
│   ├── OidcAuthenticationScheme    (service — HttpAuthenticationSchemeAdapter impl)
│   └── OidcStateManager            (service — generate/validate state & nonce)
│
├── web/
│   ├── OidcLoginController         (Spring MVC controller — /app/oidc/login)
│   └── OidcCallbackController      (Spring MVC controller — /app/oidc/callback)
│
├── OidcConstants                   (constants — URLs, claim names, property keys)
└── OidcPluginConfiguration         (Spring @Configuration — wires all beans)
```

---

## Class Descriptions and APIs

### `OidcPluginSettings`

Plain data class (POJO/record). Holds the complete deserialized contents of the config JSON file.
All fields have defaults matching the table above. Deserialized by Jackson.

```java
public class OidcPluginSettings {
    String issuerUrl;
    boolean discoveryEnabled = true;
    String authorizationEndpoint;
    String tokenEndpoint;
    String userInfoEndpoint;
    String jwksUri;
    String clientId;
    String clientSecret;
    List<String> scopes;             // default ["openid","email","profile"]
    String callbackBaseUrl;
    boolean createUsersAutomatically = false;
    List<String> allowedEmailDomains;
    boolean assignGroups = false;
    boolean removeUnassignedGroups = false;
    String groupsClaimName;          // default "groups"
    OidcClaimMappingSettings usernameClaim;
    OidcClaimMappingSettings emailClaim;
    OidcClaimMappingSettings displayNameClaim;
    int httpTimeoutSeconds = 30;
    int tokenClockSkewSeconds = 30;
}
```

---

### `OidcClaimMappingSettings`

Plain data class. Describes how to extract one user attribute from the token.

```java
public class OidcClaimMappingSettings {
    enum MappingType { SUB, CLAIM, NONE }
    MappingType mappingType;
    String claimName;   // used when mappingType == CLAIM
}
```

---

### `OidcPluginSettingsStorage` (interface)

```java
public interface OidcPluginSettingsStorage {
    /** Returns current settings, never null. Returns default/empty settings if file absent. */
    OidcPluginSettings getSettings();

    /** Persists settings to disk. */
    void saveSettings(OidcPluginSettings settings) throws IOException;
}
```

---

### `OidcPluginSettingsStorageImpl`

Implements `OidcPluginSettingsStorage`. Injected with `ServerPaths` to resolve the config
directory and `ExecutorServices` to obtain the executor required by `FileWatcher`.

**Behavior:**
- Config file: `{ServerPaths.getConfigDir()}/oidc-auth-plugin.json`
- Uses Jackson `ObjectMapper` for JSON serialization.
- Creates a `FileWatcher` via `FileWatcher(file, executor, callback)` on startup; `callback`
  calls `reload()` which re-parses the file and updates the in-memory cache.
- Caches settings in memory under a `ReadWriteLock` to avoid re-parsing on every request.
- On first access, if the file is absent, returns and caches a default `OidcPluginSettings`.

**Key methods:**
```java
OidcPluginSettingsStorageImpl(ServerPaths serverPaths, ExecutorServices executorServices)

OidcPluginSettings getSettings()          // thread-safe read
void saveSettings(OidcPluginSettings s)   // writes JSON, updates cache
void reload()                             // called by FileWatcher on external file change
```

---

### `OidcDiscoveryDocument`

Data class. Deserialized from the OIDC well-known discovery endpoint response.

```java
public class OidcDiscoveryDocument {
    String issuer;
    String authorizationEndpoint;
    String tokenEndpoint;
    String userInfoEndpoint;
    String jwksUri;
    List<String> scopesSupported;
    List<String> responseTypesSupported;
    List<String> claimsSupported;
}
```

---

### `OidcTokenResponse`

Data class. Deserialized from the token endpoint response.

```java
public class OidcTokenResponse {
    String accessToken;       // access_token
    String idToken;           // id_token
    String tokenType;         // token_type (always "Bearer")
    int expiresIn;            // expires_in
    String refreshToken;      // refresh_token (may be absent)
    String scope;             // scope
}
```

---

### `OidcIdTokenClaims`

Data class. Represents the decoded (but not yet validated) payload of the ID token JWT.

```java
public class OidcIdTokenClaims {
    String iss;               // Issuer
    String sub;               // Subject
    List<String> aud;         // Audience — nimbus-jose-jwt normalises both JSON string and array forms to List<String>
    long exp;                 // Expiration time (epoch seconds)
    long iat;                 // Issued at (epoch seconds)
    String nonce;             // Nonce
    String email;
    Boolean emailVerified;
    String name;
    String preferredUsername;
    Map<String, Object> raw;  // All claims for custom mappings
}
```

---

### `OidcUserInfo`

Data class. Deserialized from the userinfo endpoint. Mirrors common OIDC standard claims.

```java
public class OidcUserInfo {
    String sub;
    String email;
    Boolean emailVerified;
    String name;
    String preferredUsername;
    String givenName;
    String familyName;
    Map<String, Object> raw;  // All claims for custom mappings
}
```

---

### `OidcClient`

Service. Handles all HTTP communication with the Identity Provider. Uses TeamCity's internal
`HttpRequests` / `HTTPRequestBuilder` infrastructure (same pattern as Keycloak example).

**Constructor:**
```java
OidcClient(OidcPluginSettingsStorage settingsStorage)
```

**Methods:**

```java
/**
 * Fetches and parses the OIDC discovery document.
 * URL: {issuerUrl}/.well-known/openid-configuration
 *
 * Caching: the result is cached keyed by issuerUrl. The cache entry is
 * invalidated whenever issuerUrl changes (detected by comparing against the
 * previously cached key). Callers that need a forced refresh (e.g. after a
 * network failure recovery) may call invalidateDiscoveryCache().
 */
OidcDiscoveryDocument fetchDiscoveryDocument(String issuerUrl) throws OidcClientException;

/** Clears the discovery document cache, forcing a fresh fetch on the next call. */
void invalidateDiscoveryCache();

/**
 * Exchanges an authorization code for tokens at the token endpoint.
 * POST application/x-www-form-urlencoded:
 *   grant_type=authorization_code, code, redirect_uri, client_id, client_secret
 */
OidcTokenResponse exchangeCodeForTokens(
    String tokenEndpoint,
    String code,
    String redirectUri,
    String clientId,
    String clientSecret
) throws OidcClientException;

/**
 * Calls the userinfo endpoint with a Bearer access token.
 * GET {userInfoEndpoint}, Authorization: Bearer {accessToken}
 */
OidcUserInfo fetchUserInfo(
    String userInfoEndpoint,
    String accessToken
) throws OidcClientException;

/**
 * Fetches the JWKS document for ID token signature verification.
 * GET {jwksUri}
 */
String fetchJwks(String jwksUri) throws OidcClientException;
```

**Exception:** `OidcClientException` — wraps HTTP errors, timeouts, and parse failures.

---

### `OidcIdTokenValidator`

Service. Validates a raw ID token (JWT string) against the OIDC spec requirements.

**Constructor:**
```java
OidcIdTokenValidator(OidcClient oidcClient, Clock clock)
```

`clock` defaults to `Clock.systemUTC()` in production; tests inject a fixed clock to make
expiry assertions deterministic without pre-generating tokens with far-future `exp` values.

**Method:**

```java
/**
 * Validates and decodes the ID token.
 *
 * Checks performed:
 *  - JWT structure (three Base64url-encoded parts)
 *  - Signature verification against JWKS fetched from jwksUri
 *  - iss matches expectedIssuer
 *  - aud contains clientId
 *  - exp > now (with clock skew tolerance)
 *  - iat is not in the future (with clock skew tolerance)
 *  - nonce matches expectedNonce (if expectedNonce is non-null)
 *
 * @throws OidcAuthException if any check fails
 */
OidcIdTokenClaims validateAndDecode(
    String rawIdToken,
    String jwksUri,
    String expectedIssuer,
    String clientId,
    String expectedNonce,
    int clockSkewSeconds
) throws OidcAuthException;
```

**JWT library:** Depend on `nimbus-jose-jwt`. It normalises the `aud` claim to a list
regardless of whether the raw JWT contains a JSON string or array, and handles JWKS key
selection by `kid` header automatically.

---

### `OidcClientException`

Checked exception thrown by `OidcClient` for any failure during communication with the IdP.

```java
public class OidcClientException extends Exception {
    private final int httpStatus;   // 0 if not an HTTP-level failure (e.g. timeout)
    private final String idpError;  // error field from IdP JSON response, may be null

    public OidcClientException(String message, int httpStatus, String idpError, Throwable cause) { ... }
    public int getHttpStatus() { ... }
    public String getIdpError() { ... }
}
```

---

### `OidcAuthException`

Checked exception thrown during the authentication processing logic (invalid state, expired
token, failed signature, domain not allowed, etc.). Translated to
`HttpAuthenticationResult.unauthenticated(message)` by `OidcAuthenticationScheme`.

```java
public class OidcAuthException extends Exception {
    public OidcAuthException(String message) { ... }
    public OidcAuthException(String message, Throwable cause) { ... }
}
```

---

### `OidcStateManager`

Service. Manages per-request state tokens and nonces stored in the HTTP session to prevent
CSRF and replay attacks.

```java
public class OidcStateManager {
    /** Generates a secure random state value, stores it in the session, returns it. */
    String generateState(HttpSession session);

    /** Validates that the given state matches what was stored in the session. Removes it from session. */
    boolean validateAndConsumeState(HttpSession session, String state);

    /** Generates a secure random nonce, stores it in the session, returns it. */
    String generateNonce(HttpSession session);

    /** Retrieves the nonce from the session (used during token validation). Removes it from session. */
    String consumeNonce(HttpSession session);
}
```

Session attribute keys are defined in `OidcConstants`.

---

### `OidcAuthenticationScheme`

Main authentication class. Extends `HttpAuthenticationSchemeAdapter` and registers with
TeamCity's `LoginConfiguration`.

**Constructor:**
```java
OidcAuthenticationScheme(
    LoginConfiguration loginConfiguration,
    OidcPluginSettingsStorage settingsStorage,
    OidcClient oidcClient,
    OidcIdTokenValidator tokenValidator,
    OidcStateManager stateManager,
    UserModel userModel,
    UserGroupManager userGroupManager,
    RootUrlHolder rootUrlHolder,
    WebControllerManager webControllerManager,
    AuthorizationInterceptor authInterceptor
)
```

**Lifecycle:**
- Calls `loginConfiguration.registerAuthModuleType(this)` in constructor.
- Registers `/app/oidc/login` and `/app/oidc/callback` as unauthenticated via
  `authInterceptor.addPathNotRequiringAuth()`. This is the **single place** where these
  paths are exempted — `OidcLoginController` and `OidcCallbackController` do **not** call
  `authInterceptor` themselves.

**Key methods:**

```java
/** Auth scheme name shown in TeamCity login configuration. */
@Override
String getName();   // "OpenID Connect"

/** Short identifier used in URLs and Spring bean names. */
@Override
String getType();   // "oidc"

/**
 * Processes an incoming HTTP authentication request (the callback from IdP).
 * Called directly by OidcCallbackController — NOT by TeamCity's auth interceptor.
 * (HttpAuthenticationSchemeAdapter.processAuthenticationRequest() is only invoked by
 * the interceptor when the scheme is configured as the default login module. To keep
 * the flow explicit and portable, the controller owns the dispatch.)
 *
 * Steps:
 *  1. Extract `code` and `state` from request parameters.
 *  2. Validate state via OidcStateManager.
 *  3. Exchange code for tokens via OidcClient.
 *  4. Validate id_token via OidcIdTokenValidator.
 *  5. Optionally fetch userinfo for additional claims.
 *  6. Resolve TeamCity username from claims using configured mapping.
 *  7. Look up user: UserModel.findUserByUsername().
 *     - If not found and createUsersAutomatically=true: create user with mapped email/display name.
 *     - If not found and createUsersAutomatically=false: return unauthenticated result with message.
 *  8. Apply email domain restriction (allowedEmailDomains).
 *  9. Sync groups if assignGroups=true.
 * 10. Return HttpAuthenticationResult.authenticated(serverPrincipal).
 */
@Override
HttpAuthenticationResult processAuthenticationRequest(
    HttpServletRequest request,
    HttpServletResponse response,
    Map<String, String> properties
) throws IOException;
```

**Username resolution** (`resolveUsername()`):
- `SUB` mapping: use `sub` claim directly.
- `CLAIM` mapping: extract named claim from ID token (falls back to userinfo if absent).
- `NONE` mapping: disallow login (configuration error).

**Group sync** (`syncGroups()`):
- Reads group list from the configured `groupsClaimName` claim.
- Iterates all TeamCity groups. Adds user to groups present in claim. Optionally removes user
  from groups absent in claim (respects `removeUnassignedGroups`).

---

### `OidcLoginController`

Spring MVC controller. Handles `GET /app/oidc/login`. Registered via `WebControllerManager`.
The path is exempted from authentication by `OidcAuthenticationScheme` (not here).

**Constructor:**
```java
OidcLoginController(
    WebControllerManager webControllerManager,
    OidcPluginSettingsStorage settingsStorage,
    OidcClient oidcClient,
    OidcStateManager stateManager,
    RootUrlHolder rootUrlHolder
)
```

**handleRequest(request, response):**
1. Load settings. If `clientId` or `issuerUrl` is blank, return 500 with a plain-text error.
2. Resolve authorization endpoint (via discovery or explicit config).
3. Generate `state` and `nonce` via `OidcStateManager`; store both in session.
4. Build authorization URL:
   - `response_type=code`
   - `client_id`
   - `redirect_uri` = `{callbackBaseUrl}/app/oidc/callback`
   - `scope` = space-separated list from settings
   - `state`
   - `nonce`
5. `response.sendRedirect(authorizationUrl)`.

---

### `OidcCallbackController`

Spring MVC controller. Handles `GET /app/oidc/callback`. Registered via `WebControllerManager`.
The path is exempted from authentication by `OidcAuthenticationScheme` (not here).
This controller is the **sole entry point** for the IdP redirect; it owns the full dispatch
to `OidcAuthenticationScheme.processAuthenticationRequest()`.

**Constructor:**
```java
OidcCallbackController(
    WebControllerManager webControllerManager,
    OidcAuthenticationScheme authScheme
)
```

**handleRequest(request, response):**
- If the `error` parameter is present (IdP returned an error), redirect to the login page
  with an `authError` query parameter containing the IdP error description.
- Otherwise, call `authScheme.processAuthenticationRequest(request, response, properties)`.
- On `HttpAuthenticationResult.authenticated(...)`: establish the session and redirect to
  the originally requested URL (stored in session under `OidcConstants.SESSION_REDIRECT_URL`)
  or TeamCity root.
- On `HttpAuthenticationResult.unauthenticated(message)`: redirect to the login page with
  an `authError` query parameter.

---

### `OidcConstants`

Constants class (no instantiation). All string literals used by more than one class.

```java
public final class OidcConstants {
    // Auth scheme
    static final String AUTH_SCHEME_NAME = "OpenID Connect";
    static final String AUTH_SCHEME_TYPE = "oidc";

    // URL paths
    static final String LOGIN_PATH    = "/app/oidc/login";
    static final String CALLBACK_PATH = "/app/oidc/callback";

    // Config file name
    static final String CONFIG_FILE_NAME = "oidc-auth-plugin.json";

    // Session attribute keys
    static final String SESSION_STATE = "oidc.state";
    static final String SESSION_NONCE = "oidc.nonce";
    static final String SESSION_REDIRECT_URL = "oidc.redirectUrl";

    // OIDC well-known path suffix
    static final String DISCOVERY_PATH = "/.well-known/openid-configuration";

    // Default values
    static final List<String> DEFAULT_SCOPES = Arrays.asList("openid", "email", "profile");
    static final String DEFAULT_GROUPS_CLAIM = "groups";
    static final int DEFAULT_HTTP_TIMEOUT_SECONDS = 30;
    static final int DEFAULT_CLOCK_SKEW_SECONDS = 30;
}
```

---

### `OidcPluginConfiguration`

Spring `@Configuration` class. Wires all beans together. Read by the Spring context defined in
`META-INF/build-server-plugin-teamcity-oidc-auth-integration.xml`.

```java
@Configuration
public class OidcPluginConfiguration {

    @Bean OidcPluginSettingsStorageImpl oidcSettingsStorage(
        ServerPaths serverPaths,
        ExecutorServices executorServices
    );
    @Bean OidcClient oidcClient(OidcPluginSettingsStorage settingsStorage);
    @Bean OidcIdTokenValidator oidcIdTokenValidator(OidcClient oidcClient);
    // Clock.systemUTC() is passed directly — no need to make it a bean
    @Bean OidcStateManager oidcStateManager();
    @Bean OidcAuthenticationScheme oidcAuthenticationScheme(
        LoginConfiguration loginConfiguration,
        OidcPluginSettingsStorage settingsStorage,
        OidcClient oidcClient,
        OidcIdTokenValidator tokenValidator,
        OidcStateManager stateManager,
        UserModel userModel,
        UserGroupManager userGroupManager,
        RootUrlHolder rootUrlHolder,
        WebControllerManager webControllerManager,
        AuthorizationInterceptor authInterceptor
    );
    @Bean OidcLoginController oidcLoginController(
        WebControllerManager webControllerManager,
        OidcPluginSettingsStorage settingsStorage,
        OidcClient oidcClient,
        OidcStateManager stateManager,
        RootUrlHolder rootUrlHolder
    );
    @Bean OidcCallbackController oidcCallbackController(
        WebControllerManager webControllerManager,
        OidcAuthenticationScheme authScheme
    );
}
```

---

## Dependencies to Add (pom.xml)

| Artifact | Version | Scope | Purpose |
|---|---|---|---|
| `com.nimbusds:nimbus-jose-jwt` | 9.x | compile | JWT parsing, RS256/ES256 validation, JWKS handling |
| `com.fasterxml.jackson.core:jackson-databind` | 2.15.x | compile | JSON for config file and OIDC HTTP responses |
| `com.github.tomakehurst:wiremock-jre8` | 2.35.x | test | Stub IdP HTTP endpoints in `OidcClientTest` |
| `org.mockito:mockito-core` | 4.x | test | Mock TeamCity API interfaces |
| `org.springframework:spring-test` | 5.3.x | test | `MockHttpServletRequest` / `MockHttpServletResponse` |

---

## Error Handling

- **`OidcClientException`** — thrown by `OidcClient` for network errors, non-2xx responses, or
  parse failures. Carries HTTP status code and IdP error message where available.
- **`OidcAuthException`** — thrown during authentication processing (invalid state, expired token,
  failed signature, etc.). Translated to `HttpAuthenticationResult.unauthenticated()` with a
  user-visible message.
- All exceptions are logged at WARN or ERROR level using TeamCity's `Logger` (log4j-compatible).

---

## Configuration File Example

File: `{teamcityDataDirectory}/config/oidc-auth-plugin.json`

```json
{
  "issuerUrl": "https://idp.example.com/realms/my-realm",
  "clientId": "teamcity",
  "clientSecret": "s3cr3t",
  "scopes": ["openid", "email", "profile"],
  "createUsersAutomatically": true,
  "allowedEmailDomains": ["example.com"],
  "assignGroups": true,
  "removeUnassignedGroups": false,
  "groupsClaimName": "groups",
  "usernameClaim": {
    "mappingType": "CLAIM",
    "claimName": "preferred_username"
  },
  "emailClaim": {
    "mappingType": "CLAIM",
    "claimName": "email"
  },
  "displayNameClaim": {
    "mappingType": "CLAIM",
    "claimName": "name"
  }
}
```

---

## Security Considerations

- **State parameter**: cryptographically random (128-bit), stored in server-side session.
  Validated and consumed on callback; replay and CSRF protected.
- **Nonce**: cryptographically random, embedded in authorization request and validated in ID
  token to prevent token replay.
- **ID token validation**: issuer, audience, expiry, nonce, and signature (JWKS) all checked
  before trusting any claim.
- **Client secret**: stored in plaintext in the config file; rely on OS file permissions
  (`chmod 600`) to restrict access.
- **Email domain allowlist**: provides coarse-grained authorization on top of OIDC authentication.
- **JWKS caching**: cache the JWKS response with a TTL to avoid a JWKS fetch on every login,
  but support forced refresh on unknown key ID (`kid`).

---

## Build & Packaging

No changes needed to the existing Maven structure. The plugin is packaged as a single JAR
in a ZIP file following the existing `build/pom.xml` assembly descriptor. The Spring context
file `META-INF/build-server-plugin-teamcity-oidc-auth-integration.xml` is updated to import
`OidcPluginConfiguration` via annotation-driven component scanning or explicit `<bean>` import.

---

## Test Plan

### Frameworks and Dependencies

Follow the pattern established by the SAML example plugin. The test dependencies listed in
the **Dependencies to Add** section above cover Mockito, WireMock, and Spring Test. Add also:

| Dependency | Version | Purpose |
|---|---|---|
| JUnit 4 (`junit:junit`) | 4.13.x | Test runner and assertions |
| TeamCity `tests-support` | `${teamcity-version}` | TeamCity base test infrastructure |

All test dependencies use `<scope>test</scope>` in the server module's `pom.xml`.

---

### Test File Layout

```
src/test/java/org/jetbrains/teamcity/oidc/
├── config/
│   └── OidcPluginSettingsStorageImplTest.java
├── oidc/
│   ├── OidcIdTokenValidatorTest.java
│   └── OidcClientTest.java
├── auth/
│   ├── OidcStateManagerTest.java        ← OidcStateManager lives in auth/
│   └── OidcAuthenticationSchemeTest.java
├── web/
│   └── OidcLoginControllerTest.java
└── InMemoryOidcPluginSettingsStorage.java

src/test/resources/org/jetbrains/teamcity/oidc/
├── keys/
│   ├── test-private-key.pem      (RSA 2048, generated once for tests)
│   ├── test-public-key.pem
│   └── test-jwks.json            (JWKS document for test public key)
├── tokens/
│   ├── valid-id-token.txt        (signed with test-private-key, not yet expired)
│   ├── expired-id-token.txt
│   ├── wrong-issuer-id-token.txt
│   ├── wrong-audience-id-token.txt
│   └── bad-nonce-id-token.txt
├── discovery/
│   └── discovery-document.json   (sample well-known response)
└── config/
    ├── minimal-settings.json
    └── full-settings.json
```

All test JWT tokens are pre-generated offline with the test RSA key pair and committed to the
repository. Because `OidcIdTokenValidator` accepts an injectable `Clock`, tokens can have any
fixed `exp` value — tests simply set the clock to a moment before expiry. This avoids tokens
that "expire" over time and makes the fixture intent explicit in the filename.

---

### Unit Tests

#### `OidcPluginSettingsStorageImplTest`

Tests the JSON file persistence layer in isolation. No mocks needed — uses real files in a
`java.io.tmpdir` temporary directory.

| Test | What it verifies |
|---|---|
| `loadsDefaultsWhenFileAbsent()` | Returns non-null default settings when config file does not exist |
| `savesAndReloadsSettings()` | Writes settings to file, creates new storage instance pointing at same file, reads back and asserts field equality |
| `hotReloadOnFileChange()` | Saves settings, modifies the file externally, calls `reload()`, asserts new values are returned |
| `defaultScopesApplied()` | Newly constructed settings have `scopes = ["openid","email","profile"]` |
| `jacksonIgnoresUnknownFields()` | A config JSON with an extra unknown field is deserialized without error |

#### `OidcStateManagerTest`

Tests state/nonce lifecycle. Uses `MockHttpSession` from Spring Test.

| Test | What it verifies |
|---|---|
| `generatedStateIsStoredInSession()` | After `generateState()`, session contains the value under `OidcConstants.SESSION_STATE` |
| `validStateIsConsumed()` | `validateAndConsumeState()` returns `true` and removes value from session |
| `wrongStateIsRejected()` | `validateAndConsumeState()` returns `false` for a different value |
| `stateCannotBeReused()` | After one successful validation, a second call with the same value returns `false` |
| `generateNonceIsStoredAndConsumed()` | `generateNonce()` stores nonce; `consumeNonce()` returns and removes it |
| `consumeNonceOnEmptySessionReturnsNull()` | `consumeNonce()` returns `null` when nothing was stored |
| `eachGeneratedStateIsUnique()` | 1000 calls to `generateState()` produce no collisions |

#### `OidcIdTokenValidatorTest`

Tests JWT validation logic. `OidcClient` is mocked to return the test JWKS JSON from
`src/test/resources`. A fixed `Clock` is injected via the constructor for all time-sensitive
tests — no pre-generated far-future tokens needed.

| Test | What it verifies |
|---|---|
| `validTokenIsAccepted()` | A correctly signed, non-expired token returns populated `OidcIdTokenClaims` |
| `expiredTokenIsRejected()` | Token with `exp` in the past throws `OidcAuthException` |
| `tokenNotYetValidIsRejected()` | Token with `iat` far in the future throws `OidcAuthException` |
| `wrongIssuerIsRejected()` | Token `iss` ≠ expected issuer throws `OidcAuthException` |
| `wrongAudienceIsRejected()` | Token `aud` does not contain clientId throws `OidcAuthException` |
| `wrongNonceIsRejected()` | Token `nonce` ≠ expected nonce throws `OidcAuthException` |
| `nullNonceSkipsNonceCheck()` | When `expectedNonce` is `null`, nonce mismatch is not checked |
| `invalidSignatureIsRejected()` | Token signed with a different key throws `OidcAuthException` |
| `malformedJwtIsRejected()` | Token with only two parts throws `OidcAuthException` |
| `clockSkewAllowsSlightlyExpiredToken()` | Token expired 20 seconds ago is accepted with `clockSkewSeconds=30` |
| `claimsAreMappedCorrectly()` | `sub`, `email`, `name`, `preferred_username` are returned in `OidcIdTokenClaims` |
| `unknownKidTriggersJwksRefresh()` | A token with an unknown `kid` causes `OidcClient.fetchJwks()` to be called a second time |

#### `OidcClientTest`

Tests HTTP communication. `OidcClient` is tested against a WireMock server started on a
random local port.

| Test | What it verifies |
|---|---|
| `fetchDiscoveryDocument_success()` | Parses all standard fields from a stubbed JSON response |
| `fetchDiscoveryDocument_404()` | Throws `OidcClientException` with HTTP 404 status |
| `exchangeCodeForTokens_success()` | Sends correct form-encoded POST body, returns populated `OidcTokenResponse` |
| `exchangeCodeForTokens_invalidClient()` | IdP returns `{"error":"invalid_client"}` → throws `OidcClientException` with message |
| `fetchUserInfo_success()` | Sends `Authorization: Bearer {token}` header, parses user info |
| `fetchUserInfo_unauthorized()` | IdP returns 401 → throws `OidcClientException` |
| `fetchJwks_success()` | Returns raw JWKS JSON string |
| `timeoutIsRespected()` | WireMock delays response beyond timeout → throws `OidcClientException` |

#### `OidcAuthenticationSchemeTest`

The central test class. Mocks all TeamCity API dependencies. Uses:
- `mock(UserModel.class)`, `mock(UserGroupManager.class)`, `mock(LoginConfiguration.class)`,
  `mock(RootUrlHolder.class)` via Mockito
- `InMemoryOidcPluginSettingsStorage` (test double, see below) for settings
- Mocked `OidcClient` and `OidcIdTokenValidator` (fixed `Clock`) to return controlled results
- `MockHttpServletRequest` / `MockHttpServletResponse` from Spring Test

| Test | What it verifies |
|---|---|
| `existingUserIsAuthenticated()` | `UserModel.findUserByUsername()` returns a user → result is `authenticated` |
| `unknownUserWithAutoCreateDisabled_isRejected()` | `findUserByUsername()` returns null, `createUsersAutomatically=false` → `unauthenticated` |
| `unknownUserWithAutoCreateEnabled_isCreated()` | `findUserByUsername()` returns null, `createUsersAutomatically=true` → `createUserAccount()` called, result is `authenticated` |
| `emailDomainAllowlistBlocks_disallowedDomain()` | Email `user@other.com` with `allowedEmailDomains=["example.com"]` → `unauthenticated` |
| `emailDomainAllowlistPermits_allowedDomain()` | Email `user@example.com` with `allowedEmailDomains=["example.com"]` → `authenticated` |
| `emptyAllowlistPermitsAll()` | No `allowedEmailDomains` configured → any email is accepted |
| `invalidStateIsRejected()` | Callback request carries wrong `state` → `unauthenticated` |
| `missingCodeParameterIsRejected()` | Callback request without `code` → `unauthenticated` |
| `groupsAreAssignedFromClaims()` | `assignGroups=true`, claim contains `["devs","admins"]` → user added to those TeamCity groups |
| `groupsAreRemovedWhenRemoveUnassignedEnabled()` | User is in group not present in claim, `removeUnassignedGroups=true` → removed from that group |
| `groupsAreNotRemovedWhenRemoveUnassignedDisabled()` | Same scenario, `removeUnassignedGroups=false` → user stays in group |
| `usernameFromSubClaim()` | `usernameClaim.mappingType=SUB` → `sub` value used as TeamCity username |
| `usernameFromNamedClaim()` | `usernameClaim.mappingType=CLAIM, claimName=preferred_username` → that claim used |
| `emailMappedFromClaim()` | On user creation, email is set from configured `emailClaim` |
| `displayNameMappedFromClaim()` | On user creation, display name is set from configured `displayNameClaim` |
| `tokenValidationFailure_isRejected()` | `OidcIdTokenValidator` throws `OidcAuthException` → `unauthenticated` |
| `tokenExchangeFailure_isRejected()` | `OidcClient.exchangeCodeForTokens()` throws `OidcClientException` → `unauthenticated` |

#### `OidcLoginControllerTest`

Tests the login redirect. Uses `MockHttpServletRequest` / `MockHttpServletResponse`.

| Test | What it verifies |
|---|---|
| `redirectsToAuthorizationEndpoint()` | Response is a 302 redirect whose URL starts with the configured authorization endpoint |
| `redirectUrlContainsRequiredParams()` | Redirect URL contains `response_type=code`, `client_id`, `redirect_uri`, `scope`, `state`, `nonce` |
| `stateAndNonceStoredInSession()` | After `handleRequest()`, session contains `oidc.state` and `oidc.nonce` |
| `missingClientIdReturns500()` | Settings with blank `clientId` → HTTP 500 response |
| `missingIssuerUrlReturns500()` | Settings with blank `issuerUrl` → HTTP 500 response |
| `scopesAreConcatenatedWithSpaces()` | `scopes=["openid","email","profile"]` → redirect URL contains `scope=openid+email+profile` (URL-encoded) |
| `callbackUrlUsesConfiguredBase()` | `callbackBaseUrl=https://tc.example.com` → redirect_uri is `https://tc.example.com/app/oidc/callback` |
| `callbackUrlFallsBackToRootUrlHolder()` | No `callbackBaseUrl` in settings → `RootUrlHolder.getRootUrl()` is used |

---

### Test Double: `InMemoryOidcPluginSettingsStorage`

A simple in-memory implementation of `OidcPluginSettingsStorage` for use across all test
classes. Stores a single `OidcPluginSettings` reference; `saveSettings()` replaces it.

```java
// src/test/java/org/jetbrains/teamcity/oidc/InMemoryOidcPluginSettingsStorage.java
public class InMemoryOidcPluginSettingsStorage implements OidcPluginSettingsStorage {
    private OidcPluginSettings settings = new OidcPluginSettings();

    public InMemoryOidcPluginSettingsStorage(OidcPluginSettings initial) {
        this.settings = initial;
    }

    @Override public OidcPluginSettings getSettings() { return settings; }
    @Override public void saveSettings(OidcPluginSettings s) { this.settings = s; }
}
```

---

### Test Resource Generation

Generate the test RSA key pair once and commit it:

```bash
# Generate 2048-bit RSA private key
openssl genrsa -out src/test/resources/org/jetbrains/teamcity/oidc/keys/test-private-key.pem 2048

# Extract public key
openssl rsa -in .../test-private-key.pem -pubout -out .../test-public-key.pem
```

Build `test-jwks.json` manually from the public key modulus and exponent (use a helper script
or a small one-off Java main that calls `nimbus-jose-jwt`'s `RSAKey.Builder`). The resulting
`kid` value is hardcoded in all test token fixtures.

Generate test JWT tokens with a small helper class `TokenFixtureGenerator` (lives in
`src/test/java`, not shipped in production):

```java
// Usage: run main(), copy output into src/test/resources/tokens/
class TokenFixtureGenerator {
    public static void main(String[] args) throws Exception {
        RSAKey key = RSAKey.parseFromPEMEncodedObjects(
            Files.readString(Path.of("src/test/resources/.../test-private-key.pem"))
        );
        // generate valid, expired, wrong-issuer, etc. tokens and print them
    }
}
```

---

### Running the Tests

```bash
# All tests
mvn test -pl teamcity-oidc-auth-integration-server

# Single test class
mvn test -pl teamcity-oidc-auth-integration-server \
    -Dtest=OidcIdTokenValidatorTest

# With coverage report (add jacoco plugin to pom.xml)
mvn verify -pl teamcity-oidc-auth-integration-server
```

---

## Manual Testing on a Local Machine

The simplest local setup uses Docker Compose to run **Keycloak** as the OIDC provider and
**TeamCity** as the relying party. Both containers run on the same Docker network; the browser
hits them on `localhost`.

### Prerequisites

- Docker Desktop (or Docker Engine + Compose plugin)
- TeamCity plugin ZIP built: `mvn package`

### Step 1 — `docker-compose.yml`

Create a `docker-compose.yml` at the project root (not committed — already in `.gitignore`
via `*.yaml`/`*.yml` pattern if desired):

```yaml
version: "3.9"
services:

  keycloak:
    image: quay.io/keycloak/keycloak:25.0
    command: start-dev
    environment:
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: admin
    ports:
      - "8081:8080"

  teamcity:
    image: jetbrains/teamcity-server:2025.11
    ports:
      - "8111:8111"
    volumes:
      - tc-data:/data/teamcity_server/datadir
      - tc-logs:/opt/teamcity/logs

volumes:
  tc-data:
  tc-logs:
```

Start both services:

```bash
docker compose up -d
```

### Step 2 — Configure Keycloak

1. Open `http://localhost:8081` → log in as `admin / admin`.
2. Create a new realm, e.g. `dev`.
3. In the `dev` realm, create a client:
   - **Client ID**: `teamcity`
   - **Client authentication**: ON (confidential)
   - **Valid redirect URIs**: `http://localhost:8111/app/oidc/callback`
   - **Web origins**: `http://localhost:8111`
4. Open the **Credentials** tab and note the **Client secret**.
5. Create a test user (e.g. `testuser`, password `testpass`, email `testuser@example.com`).
6. (Optional) Create a group `developers` and assign the test user to it.
   In the client's **Client scopes** settings, add a mapper of type **Group Membership**
   with token claim name `groups`.

### Step 3 — Install the Plugin

```bash
mvn package
```

Copy the ZIP into the TeamCity data directory (which is persisted in the Docker volume):

```bash
docker cp build/target/teamcity-oidc-auth.zip \
    $(docker compose ps -q teamcity):/data/teamcity_server/datadir/plugins/
```

Restart the TeamCity container:

```bash
docker compose restart teamcity
```

### Step 4 — Create the Config File

```bash
docker exec -it $(docker compose ps -q teamcity) bash -c 'cat > \
  /data/teamcity_server/datadir/config/oidc-auth-plugin.json << EOF
{
  "issuerUrl": "http://keycloak:8080/realms/dev",
  "clientId": "teamcity",
  "clientSecret": "<paste secret from step 2>",
  "createUsersAutomatically": true,
  "usernameClaim": { "mappingType": "CLAIM", "claimName": "preferred_username" },
  "emailClaim":    { "mappingType": "CLAIM", "claimName": "email" },
  "displayNameClaim": { "mappingType": "CLAIM", "claimName": "name" }
}
EOF'
```

> **Note**: Inside the Docker network, TeamCity reaches Keycloak via the service name
> `keycloak:8080`. The browser's redirect uses `localhost:8081`. Because the `iss` claim in
> Keycloak tokens uses the URL the token endpoint was called with, set `callbackBaseUrl` to
> `http://localhost:8111` and verify that the `issuerUrl` in the config matches what Keycloak
> actually puts in the `iss` claim (check with `curl http://localhost:8081/realms/dev/.well-known/openid-configuration | jq .issuer`).
> Adjust accordingly — you may need a single hostname reachable by both containers and the
> browser, or use Keycloak's `KC_HOSTNAME` env var.

### Step 5 — Enable the Auth Module

1. Open `http://localhost:8111` → complete TeamCity first-run wizard.
2. Go to **Administration → Authentication**.
3. Add module → select **OpenID Connect**.
4. Save.

### Step 6 — Test Login

1. Open a private browser window and navigate to `http://localhost:8111`.
2. Click **Log in with OpenID Connect** (or navigate directly to `/app/oidc/login`).
3. You should be redirected to Keycloak's login page.
4. Log in as `testuser / testpass`.
5. You should be redirected back to TeamCity and logged in.
6. Verify the TeamCity user was created under **Administration → Users**.

### Verifying Config Hot-Reload

Edit the config file while both containers are running:

```bash
docker exec -it $(docker compose ps -q teamcity) \
    sed -i 's/"createUsersAutomatically": true/"createUsersAutomatically": false/' \
    /data/teamcity_server/datadir/config/oidc-auth-plugin.json
```

Attempt to log in with a new (non-existent) user — it should be rejected without restarting
TeamCity, confirming the `FileWatcher`-based hot-reload is working.

### Teardown

```bash
docker compose down -v   # -v removes named volumes (wipes TC data and Keycloak config)
```
