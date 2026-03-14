# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build the plugin ZIP (output: target/teamcity-oidc-auth-integration.zip)
mvn package

# Compile only, skip tests
mvn compile

# Run all tests
mvn test -pl teamcity-oidc-auth-integration-server

# Run a single test class
mvn test -pl teamcity-oidc-auth-integration-server -Dtest=OidcIdTokenValidatorTest

# Run a single test method
mvn test -pl teamcity-oidc-auth-integration-server -Dtest=OidcIdTokenValidatorTest#expiredTokenIsRejected
```

TeamCity API JARs are resolved from `https://download.jetbrains.com/teamcity-repository`. No local TeamCity installation is required to compile or test.

## Architecture

This is a **TeamCity server-side plugin** that adds OIDC authentication. It is not a standalone application — it runs inside the TeamCity server JVM and integrates through Spring and TeamCity's extension APIs.

### Module layout

| Module | Purpose |
|---|---|
| `teamcity-oidc-auth-integration-server/` | All plugin logic — compiled to a JAR |
| `build/` | Assembly only — packages the JAR + `teamcity-plugin.xml` into a deployable ZIP |
| `examples/` | Reference implementations (SAML plugin, Keycloak plugin) — not part of the build |

The final artifact is `target/teamcity-oidc-auth-integration.zip` (written to the **root** `target/`, not the module's).

### How TeamCity loads the plugin

1. The ZIP is dropped into `{teamcityDataDirectory}/plugins/`.
2. TeamCity unpacks it and loads the JAR in a separate classloader (`use-separate-classloader="true"` in `teamcity-plugin.xml`).
3. Spring reads `META-INF/build-server-plugin-teamcity-oidc-auth-integration.xml` inside the JAR and bootstraps the beans defined there.
4. `OidcPluginConfiguration` (`@Configuration`) wires all plugin beans and receives TeamCity service beans via constructor injection (TeamCity registers its own beans in the parent Spring context).

### Authentication flow

```
GET /app/oidc/login  (OidcLoginController)
  → builds authorization URL, stores state+nonce in HTTP session
  → redirects browser to IdP

GET /app/oidc/callback?code=...&state=...  (OidcCallbackController)
  → delegates to OidcAuthenticationScheme.processAuthenticationRequest()
  → validates state, exchanges code for tokens (OidcClient)
  → validates id_token JWT (OidcIdTokenValidator via JWKS)
  → resolves/creates TeamCity user (UserModel)
  → syncs groups if configured
  → returns HttpAuthenticationResult.authenticated(...)
```

Both `/app/oidc/login` and `/app/oidc/callback` are registered as unauthenticated paths via `AuthorizationInterceptor`.

### Configuration

The plugin reads `{teamcityDataDirectory}/config/oidc-auth-plugin.json` at runtime. The file is watched for changes via `FileWatcher` — no restart needed. `OidcPluginSettingsStorageImpl` owns this lifecycle and caches settings under a `ReadWriteLock`.

### Key TeamCity APIs in use

- `HttpAuthenticationSchemeAdapter` — base class for the auth scheme; `processAuthenticationRequest()` is the main entry point called by TeamCity on every request matching the registered URL
- `LoginConfiguration` — the auth scheme registers itself here in its constructor
- `WebControllerManager` — used to register Spring MVC controllers at specific URL paths
- `AuthorizationInterceptor` — exempts callback/login paths from requiring an existing session
- `UserModel` — user lookup and creation
- `ServerPaths` — resolves the config directory on disk

### Planned package structure (see PLAN.md for full detail)

```
org.jetbrains.teamcity.oidc
├── config/      OidcPluginSettings, OidcClaimMappingSettings, OidcPluginSettingsStorage[Impl]
├── oidc/        OidcClient, OidcIdTokenValidator, OidcClientException, OidcAuthException, DTO classes
├── auth/        OidcAuthenticationScheme, OidcStateManager
├── web/         OidcLoginController, OidcCallbackController
└── OidcConstants, OidcPluginConfiguration
```

### Examples (read-only reference)

- `examples/teamcity-plugin-saml/` — best reference for file-based config (`SamlPluginSettingsStorageImpl`), `HttpAuthenticationSchemeAdapter` usage, test patterns (JUnit 4 + Mockito, in-memory test doubles, real XML fixtures)
- `examples/teamcity-keycloak-base-support/` — best reference for the HTTP client pattern (`KeyCloakClient`), OIDC token exchange, and `OAuthProvider`/`OAuthFlow` APIs

Do not modify files under `examples/`.
