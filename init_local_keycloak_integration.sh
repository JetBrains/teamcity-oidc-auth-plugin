#!/usr/bin/env bash
# Initialises the Keycloak ↔ TeamCity OIDC integration for local development.
#
# Usage:
#   ./init_local_keycloak_integration.sh [KC_USER [KC_PASS]]
#
# Defaults:
#   KC_USER=admin   KC_PASS=admin
#
# Environment variable overrides (take precedence over positional args):
#   KC_URL              Keycloak base URL              (default: http://localhost:8080)
#   TC_URL              TeamCity base URL              (default: http://localhost:8111)
#   TC_URL_FROM_KC      TC URL as seen from Keycloak   (default: same as TC_URL)
#                       Override when Keycloak runs in Docker: TC_URL_FROM_KC=http://host.docker.internal:8111
#   KC_REALM            Keycloak realm                 (default: master)
#   TC_DATADIR          TC data directory              (default: servers/2025.11/.datadir, relative to this script)
#   KC_SESSION_MAX_SECONDS
#                       Keycloak SSO session max lifetime in seconds (default: 2592000 = 30 days)
#                       Must be >= TeamCity's remember-me token lifetime so that Keycloak always has
#                       an active session to terminate when a user is deleted, ensuring back-channel
#                       logout is triggered. Match this to your TC "remember me" duration if changed.

set -euo pipefail

# ---- Configuration ---------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

KC_USER="${1:-admin}"
KC_PASS="${2:-admin}"
KC_URL="${KC_URL:-http://localhost:8080}"
TC_URL="${TC_URL:-http://localhost:8111}"
TC_URL_FROM_KC="${TC_URL_FROM_KC:-$TC_URL}"
KC_REALM="${KC_REALM:-master}"
TC_DATADIR="${TC_DATADIR:-$SCRIPT_DIR/servers/2025.11/.datadir}"
TC_CLIENT_ID="teamcity"
TC_CONFIG_FILE="$TC_DATADIR/config/oidc-auth.json"
# 30 days — matches TeamCity's default remember-me token lifetime
KC_SESSION_MAX_SECONDS="${KC_SESSION_MAX_SECONDS:-2592000}"

# ---- Helpers ---------------------------------------------------------------

info()  { echo "[INFO]  $*"; }
error() { echo "[ERROR] $*" >&2; exit 1; }

require_cmd() { command -v "$1" &>/dev/null || error "'$1' is required but not found in PATH"; }

require_cmd curl
require_cmd python3

# ---- Preflight checks ------------------------------------------------------

info "Checking Keycloak at $KC_URL ..."
curl -sf "$KC_URL/realms/$KC_REALM/.well-known/openid-configuration" -o /dev/null \
  || error "Keycloak is not reachable at $KC_URL (realm: $KC_REALM)"

info "Checking TeamCity at $TC_URL ..."
curl -sf "$TC_URL/login.html" -o /dev/null \
  || error "TeamCity is not reachable at $TC_URL"

[[ -d "$TC_DATADIR/config" ]] \
  || error "TeamCity data directory not found at $TC_DATADIR"

# ---- Keycloak admin token --------------------------------------------------

info "Obtaining Keycloak admin token for user '$KC_USER' ..."
TOKEN_RESPONSE=$(curl -sf -X POST "$KC_URL/realms/$KC_REALM/protocol/openid-connect/token" \
  -d "client_id=admin-cli" \
  -d "username=$KC_USER" \
  -d "password=$KC_PASS" \
  -d "grant_type=password") \
  || error "Failed to obtain Keycloak admin token — check credentials and realm"

KC_TOKEN=$(echo "$TOKEN_RESPONSE" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('access_token') or (lambda: (_ for _ in ()).throw(Exception(d.get('error_description','unknown error'))))())" \
  2>/dev/null) \
  || error "Could not parse admin token from Keycloak response"

# ---- Create or update Keycloak client --------------------------------------

info "Looking up client '$TC_CLIENT_ID' in realm '$KC_REALM' ..."
EXISTING=$(curl -sf \
  -H "Authorization: Bearer $KC_TOKEN" \
  "$KC_URL/admin/realms/$KC_REALM/clients?clientId=$TC_CLIENT_ID")
CLIENT_COUNT=$(echo "$EXISTING" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))")

CLIENT_PAYLOAD=$(python3 -c "
import json
print(json.dumps({
  'clientId': '$TC_CLIENT_ID',
  'name': 'TeamCity',
  'description': 'TeamCity OIDC authentication',
  'enabled': True,
  'publicClient': False,
  'standardFlowEnabled': True,
  'directAccessGrantsEnabled': False,
  'redirectUris': ['$TC_URL/app/oidc/callback'],
  'webOrigins': ['$TC_URL'],
  'protocol': 'openid-connect',
  'attributes': {
    'backchannel.logout.url': '$TC_URL_FROM_KC/app/oidc/backchannel-logout',
    'backchannel.logout.session.required': 'true'
  }
}))")

if [[ "$CLIENT_COUNT" -eq 0 ]]; then
  info "Creating client '$TC_CLIENT_ID' ..."
  HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
    -H "Authorization: Bearer $KC_TOKEN" \
    -H "Content-Type: application/json" \
    "$KC_URL/admin/realms/$KC_REALM/clients" \
    -d "$CLIENT_PAYLOAD")
  [[ "$HTTP_STATUS" == "201" ]] || error "Failed to create client (HTTP $HTTP_STATUS)"
  info "Client created."
else
  CLIENT_UUID=$(echo "$EXISTING" | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['id'])")
  info "Client already exists (id: $CLIENT_UUID), updating ..."
  HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X PUT \
    -H "Authorization: Bearer $KC_TOKEN" \
    -H "Content-Type: application/json" \
    "$KC_URL/admin/realms/$KC_REALM/clients/$CLIENT_UUID" \
    -d "$CLIENT_PAYLOAD")
  [[ "$HTTP_STATUS" == "204" ]] || error "Failed to update client (HTTP $HTTP_STATUS)"
  info "Client updated."
fi

# ---- Add group membership mapper to client ---------------------------------
#
# Keycloak does not include groups in tokens by default.
# This mapper adds a "groups" claim (leaf name only, not full path) to the ID token
# so the plugin can sync group membership from the token.

CLIENT_UUID=$(curl -sf \
  -H "Authorization: Bearer $KC_TOKEN" \
  "$KC_URL/admin/realms/$KC_REALM/clients?clientId=$TC_CLIENT_ID" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['id'])")

info "Configuring group membership mapper on client '$TC_CLIENT_ID' ..."
EXISTING_MAPPERS=$(curl -sf \
  -H "Authorization: Bearer $KC_TOKEN" \
  "$KC_URL/admin/realms/$KC_REALM/clients/$CLIENT_UUID/protocol-mappers/models")
MAPPER_EXISTS=$(echo "$EXISTING_MAPPERS" \
  | python3 -c "import sys,json; ms=json.load(sys.stdin); print(any(m.get('name')=='groups' for m in ms))")

MAPPER_PAYLOAD=$(python3 -c "import json; print(json.dumps({
  'name': 'groups',
  'protocol': 'openid-connect',
  'protocolMapper': 'oidc-group-membership-mapper',
  'config': {
    'full.path': 'false',
    'id.token.claim': 'true',
    'access.token.claim': 'true',
    'userinfo.token.claim': 'true',
    'claim.name': 'groups'
  }
}))")

if [[ "$MAPPER_EXISTS" == "False" ]]; then
  HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
    -H "Authorization: Bearer $KC_TOKEN" \
    -H "Content-Type: application/json" \
    "$KC_URL/admin/realms/$KC_REALM/clients/$CLIENT_UUID/protocol-mappers/models" \
    -d "$MAPPER_PAYLOAD")
  [[ "$HTTP_STATUS" == "201" ]] || error "Failed to create group membership mapper (HTTP $HTTP_STATUS)"
  info "Group membership mapper created."
else
  MAPPER_ID=$(echo "$EXISTING_MAPPERS" \
    | python3 -c "import sys,json; ms=json.load(sys.stdin); print(next(m['id'] for m in ms if m.get('name')=='groups'))")
  HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X PUT \
    -H "Authorization: Bearer $KC_TOKEN" \
    -H "Content-Type: application/json" \
    "$KC_URL/admin/realms/$KC_REALM/clients/$CLIENT_UUID/protocol-mappers/models/$MAPPER_ID" \
    -d "$(echo "$MAPPER_PAYLOAD" | python3 -c "import sys,json; d=json.load(sys.stdin); d['id']='$MAPPER_ID'; print(json.dumps(d))")")
  [[ "$HTTP_STATUS" == "204" ]] || error "Failed to update group membership mapper (HTTP $HTTP_STATUS)"
  info "Group membership mapper updated."
fi

# ---- Get/generate client secret --------------------------------------------

info "Generating client secret ..."
SECRET_RESPONSE=$(curl -sf -X POST \
  -H "Authorization: Bearer $KC_TOKEN" \
  "$KC_URL/admin/realms/$KC_REALM/clients/$CLIENT_UUID/client-secret")
CLIENT_SECRET=$(echo "$SECRET_RESPONSE" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['value'])") \
  || error "Failed to retrieve client secret"

info "Client secret obtained."

# ---- Configure realm session lifetime --------------------------------------
#
# Back-channel logout is session-based: Keycloak only sends a logout_token for
# sessions that are active at the moment of user deletion. If the KC session has
# already expired while TeamCity's remember-me token is still valid, deleting the
# user in KC will NOT trigger back-channel logout and the TC session will persist.
#
# Fix: keep KC's SSO session lifetime >= TC's remember-me lifetime so there is
# always a live KC session to terminate whenever a user has a live TC session.

info "Setting realm '$KC_REALM' SSO session max lifetime to ${KC_SESSION_MAX_SECONDS}s ..."
REALM_PAYLOAD=$(python3 -c "import json; print(json.dumps({
  'ssoSessionMaxLifespan': $KC_SESSION_MAX_SECONDS,
  'ssoSessionIdleTimeout': $KC_SESSION_MAX_SECONDS
}))")
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X PUT \
  -H "Authorization: Bearer $KC_TOKEN" \
  -H "Content-Type: application/json" \
  "$KC_URL/admin/realms/$KC_REALM" \
  -d "$REALM_PAYLOAD")
[[ "$HTTP_STATUS" == "204" ]] || error "Failed to update realm session settings (HTTP $HTTP_STATUS)"
info "Realm session lifetime updated."

# ---- Write plugin config file ----------------------------------------------

info "Writing plugin config to $TC_CONFIG_FILE ..."
python3 -c "
import json
config = {
  'issuerUrl': '$KC_URL/realms/$KC_REALM',
  'discoveryEnabled': True,
  'authorizationEndpoint': None,
  'tokenEndpoint': None,
  'userInfoEndpoint': None,
  'jwksUri': None,
  'clientId': '$TC_CLIENT_ID',
  'clientSecret': '$CLIENT_SECRET',
  'scopes': ['openid', 'email', 'profile'],
  'callbackBaseUrl': None,
  'createUsersAutomatically': True,
  'allowedEmailDomains': [],
  'assignGroups': True,
  'removeUnassignedGroups': False,
  'groupsClaimName': 'groups',
  'usernameClaim': {'mappingType': 'CLAIM', 'claimName': 'preferred_username'},
  'emailClaim':    {'mappingType': 'CLAIM', 'claimName': 'email'},
  'displayNameClaim': {'mappingType': 'CLAIM', 'claimName': 'name'},
  'httpTimeoutSeconds': 30,
  'tokenClockSkewSeconds': 30
}
print(json.dumps(config, indent=2))
" > "$TC_CONFIG_FILE"

# ---- Done ------------------------------------------------------------------

info ""
info "Integration configured successfully."
info ""
info "  Keycloak client      : $TC_CLIENT_ID"
info "  Issuer URL           : $KC_URL/realms/$KC_REALM"
info "  Callback URL         : $TC_URL/app/oidc/callback"
info "  Config file          : $TC_CONFIG_FILE"
info "  KC session max       : ${KC_SESSION_MAX_SECONDS}s (must be >= TC remember-me lifetime)"
info ""
info "To test: open $TC_URL/app/oidc/login in your browser."
info "The plugin hot-reloads the config file — no TeamCity restart needed."
