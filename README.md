# TeamCity OIDC Authentication Plugin

A TeamCity server plugin that enables user authentication via **OpenID Connect (OIDC)**
/ OAuth 2.0 Authorization Code Flow. It works with any standards-compliant identity provider
(Keycloak, Okta, Azure AD, Google Workspace, Authentik, etc.).

## Features

- Standard OIDC Authorization Code Flow with PKCE-ready state/nonce handling
- ID token signature validation (RS256/ES256) via JWKS endpoint
- Automatic discovery via `/.well-known/openid-configuration`
- Configurable claim mappings for username, email, and display name
- Optional automatic user creation on first login
- Optional group synchronization from OIDC claims
- Email domain allowlist for coarse-grained access control
- File-based configuration — no TeamCity UI required, hot-reload on file change

## Requirements

- TeamCity 2025.11 or later
- Java 8+
- An OIDC-compatible Identity Provider

## Installation

1. Download the plugin ZIP from the releases page.
2. Go to **Administration → Plugins** in TeamCity and upload the ZIP, or copy it to
   `{teamcityDataDirectory}/plugins/` and restart TeamCity.
3. In **Administration → Authentication**, add the **OpenID Connect** module.
4. Create the configuration file (see below).

## Configuration

Create or edit the file:

```
{teamcityDataDirectory}/config/oidc-auth.json
```

The file is watched for changes; TeamCity does not need to be restarted after editing it.

### Minimal example

```json
{
  "issuerUrl": "https://idp.example.com/realms/my-realm",
  "clientId": "teamcity",
  "clientSecret": "******"
}
```

With only these three fields, the plugin will:
- Discover all OIDC endpoints automatically
- Request scopes `openid email profile`
- Use the `sub` claim as the TeamCity username
- Require the user to already exist in TeamCity

### Full example

```json
{
  "issuerUrl": "https://idp.example.com/realms/my-realm",
  "clientId": "teamcity",
  "clientSecret": "*****",
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

### Configuration reference

| Field | Default | Description |
|---|---|---|
| `issuerUrl` | — | **Required.** OIDC issuer URL. Discovery document is fetched from `{issuerUrl}/.well-known/openid-configuration`. |
| `discoveryEnabled` | `true` | When `false`, endpoint URLs must be provided explicitly. |
| `authorizationEndpoint` | (from discovery) | Authorization endpoint URL. |
| `tokenEndpoint` | (from discovery) | Token endpoint URL. |
| `userInfoEndpoint` | (from discovery) | UserInfo endpoint URL. |
| `jwksUri` | (from discovery) | JWKS endpoint URL for ID token signature verification. |
| `clientId` | — | **Required.** OAuth 2.0 client ID. |
| `clientSecret` | — | **Required.** OAuth 2.0 client secret. Protect this file with `chmod 600`. |
| `scopes` | `["openid","email","profile"]` | OAuth 2.0 scopes to request. |
| `createUsersAutomatically` | `true` | Auto-create TeamCity users on first OIDC login. |
| `allowedEmailDomains` | (none) | If set, only emails from these domains can log in. |
| `assignGroups` | `false` | Sync user's TeamCity group membership from OIDC claims. |
| `removeUnassignedGroups` | `false` | Remove user from TeamCity groups absent in the OIDC claim. Requires `assignGroups: true`. |
| `groupsClaimName` | `"groups"` | JWT/userinfo claim that holds the group list. |
| `usernameClaim.mappingType` | `"SUB"` | `SUB` uses the `sub` claim; `CLAIM` uses a named claim. |
| `usernameClaim.claimName` | — | Claim name when `mappingType` is `CLAIM`. |
| `emailClaim.mappingType` | `"CLAIM"` | Same options as `usernameClaim`. |
| `emailClaim.claimName` | `"email"` | Claim name for email. |
| `displayNameClaim.mappingType` | `"CLAIM"` | Same options as `usernameClaim`. |
| `displayNameClaim.claimName` | `"name"` | Claim name for display name. |
| `httpTimeoutSeconds` | `30` | Timeout for HTTP calls to the IdP. |
| `tokenClockSkewSeconds` | `30` | Allowed clock skew when validating `exp`/`iat`. |

## Identity Provider Setup

Register a confidential OAuth 2.0 / OIDC client in your IdP with:

- **Redirect URI**: `https://your-teamcity.example.com/app/oidc/callback`
- **Grant type**: Authorization Code
- **Response type**: `code`

## Security Notes

- ID tokens are validated against the IdP's JWKS on every login.
- State and nonce parameters are generated per-request to prevent CSRF and replay attacks.

## Building from Source

```bash
mvn package
```

The plugin ZIP is produced at `build/target/oidc-auth.zip`.

## Architecture

See [PLAN.md](PLAN.md) for the detailed architecture, class descriptions, and API design.

## License

Apache 2.0
