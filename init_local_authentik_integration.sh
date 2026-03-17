#!/usr/bin/env bash
# Initialises the Authentik ↔ TeamCity OIDC integration for local development.
# Requires Authentik >= 2024.6 (redirect_uris list format).
#
# Usage:
#   AUTHENTIK_TOKEN=<api-token> ./init_local_authentik_integration.sh
#
# AUTHENTIK_TOKEN: an Authentik API token with Intent "API".
# Create one in Admin UI → Directory → Tokens → Create, then copy the Key value.
#
# Environment variable overrides:
#   AUTHENTIK_URL       Authentik base URL              (default: http://localhost:9000)
#   TC_URL              TeamCity base URL               (default: http://localhost:8111)
#   TC_URL_FROM_AK      TC URL as seen from Authentik   (default: same as TC_URL)
#                       Override when Authentik runs in Docker: TC_URL_FROM_AK=http://host.docker.internal:8111
#   TC_DATADIR          TC data directory               (default: servers/2025.11/.datadir, relative to this script)
#   TC_CLIENT_ID        OIDC client ID / app slug       (default: teamcity)

set -euo pipefail

# ---- Configuration ---------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

AUTHENTIK_URL="${AUTHENTIK_URL:-http://localhost:9000}"
AUTHENTIK_TOKEN="${AUTHENTIK_TOKEN:-}"
TC_URL="${TC_URL:-http://localhost:8111}"
TC_URL_FROM_AK="${TC_URL_FROM_AK:-$TC_URL}"
TC_DATADIR="${TC_DATADIR:-$SCRIPT_DIR/servers/2025.11/.datadir}"
TC_CLIENT_ID="${TC_CLIENT_ID:-teamcity}"
TC_APP_SLUG="$TC_CLIENT_ID"
TC_CONFIG_FILE="$TC_DATADIR/config/oidc-auth-plugin.json"

# ---- Helpers ---------------------------------------------------------------

info()  { echo "[INFO]  $*"; }
error() { echo "[ERROR] $*" >&2; exit 1; }

require_cmd() { command -v "$1" &>/dev/null || error "'$1' is required but not found in PATH"; }

require_cmd curl
require_cmd python3

[[ -n "$AUTHENTIK_TOKEN" ]] \
  || error "AUTHENTIK_TOKEN is required. Create one in Authentik Admin UI → Directory → Tokens (Intent: API)."

# Authenticated API call: ak_api <METHOD> <URL>
# Exits with an error message (including the response body) on non-2xx status.
ak_api() {
  local method="$1" url="$2"
  local tmp body http_status
  tmp=$(mktemp)
  http_status=$(curl -s -o "$tmp" -w "%{http_code}" -X "$method" \
    -H "Authorization: Bearer $AUTHENTIK_TOKEN" \
    -H "Content-Type: application/json" \
    "$url")
  body=$(cat "$tmp"); rm -f "$tmp"
  if [[ "$http_status" -lt 200 || "$http_status" -ge 300 ]]; then
    error "$method $url failed (HTTP $http_status): $body"
  fi
  printf '%s' "$body"
}

# ---- Preflight checks ------------------------------------------------------

info "Checking Authentik at $AUTHENTIK_URL ..."
curl -sf "$AUTHENTIK_URL/-/health/ready/" -o /dev/null \
  || error "Authentik is not reachable at $AUTHENTIK_URL"

info "Checking TeamCity at $TC_URL ..."
curl -sf "$TC_URL/login.html" -o /dev/null \
  || error "TeamCity is not reachable at $TC_URL"

[[ -d "$TC_DATADIR/config" ]] \
  || error "TeamCity data directory not found at $TC_DATADIR"

# ---- Find default authorization flow ---------------------------------------

info "Looking up default authorization flow ..."
FLOWS=$(ak_api GET "$AUTHENTIK_URL/api/v3/flows/instances/?designation=authorization&ordering=slug")
AUTH_FLOW_PK=$(echo "$FLOWS" | python3 -c "
import sys, json
d = json.load(sys.stdin)
results = d.get('results', [])
if not results:
    raise SystemExit('No authorization flows found in Authentik')
# Prefer implicit-consent (no extra click) for local dev, fall back to first
preferred = next(
    (f for f in results if 'implicit' in f['slug']),
    results[0]
)
print(preferred['pk'])
") || error "Could not determine authorization flow"
info "Authorization flow pk: $AUTH_FLOW_PK"

# ---- Find default invalidation (logout) flow -------------------------------

info "Looking up invalidation flow ..."
INV_FLOWS=$(ak_api GET "$AUTHENTIK_URL/api/v3/flows/instances/?designation=invalidation&ordering=slug")
INV_FLOW_PK=$(echo "$INV_FLOWS" | python3 -c "
import sys, json
d = json.load(sys.stdin)
results = d.get('results', [])
if not results:
    raise SystemExit('No invalidation flows found in Authentik')
print(results[0]['pk'])
") || error "Could not determine invalidation flow"
info "Invalidation flow pk: $INV_FLOW_PK"

# ---- Find signing key ------------------------------------------------------

info "Looking up signing key ..."
KEYS=$(ak_api GET "$AUTHENTIK_URL/api/v3/crypto/certificatekeypairs/?has_key=true&ordering=name")
SIGNING_KEY_PK=$(echo "$KEYS" | python3 -c "
import sys, json
d = json.load(sys.stdin)
results = d.get('results', [])
if not results:
    raise SystemExit('No certificate/key pairs found. Authentik should create one automatically on startup.')
print(results[0]['pk'])
") || error "Could not find a signing key"
info "Signing key pk: $SIGNING_KEY_PK"

# ---- Find scope mappings ---------------------------------------------------
#
# We collect PKs for: openid, email, profile, and a groups scope (if present).
# Authentik's built-in profile scope mapping already includes a 'groups' claim
# via its Python expression. A dedicated groups scope mapping is included when
# available so the 'groups' claim is also accessible via the 'groups' scope.

info "Looking up scope mappings ..."
ALL_SCOPES=$(ak_api GET "$AUTHENTIK_URL/api/v3/propertymappings/provider/scope/?page_size=100")
SCOPE_PKS_JSON=$(echo "$ALL_SCOPES" | python3 -c "
import sys, json
d = json.load(sys.stdin)
mappings = d.get('results', [])
pks = []

for wanted in ('openid', 'email', 'profile'):
    matches = [m for m in mappings if m.get('scope_name') == wanted]
    if not matches:
        raise SystemExit(f'Built-in scope mapping \"{wanted}\" not found — is Authentik fully initialised?')
    pks.append(str(matches[0]['pk']))

# Optional: dedicated groups scope (scope_name='groups' or name contains 'groups')
groups = next(
    (m for m in mappings if m.get('scope_name') == 'groups'),
    next((m for m in mappings if 'group' in m.get('name', '').lower() and m.get('scope_name', '')), None)
)
if groups:
    pks.append(str(groups['pk']))

print(json.dumps(pks))
") || error "Failed to resolve scope mappings"
info "Scope mapping PKs: $SCOPE_PKS_JSON"

# ---- Create or update OAuth2 provider -------------------------------------

info "Looking up OAuth2 provider with client_id='$TC_CLIENT_ID' ..."
EXISTING_PROVIDERS=$(ak_api GET "$AUTHENTIK_URL/api/v3/providers/oauth2/?client_id=$TC_CLIENT_ID")
PROVIDER_COUNT=$(echo "$EXISTING_PROVIDERS" | python3 -c "import sys,json; print(json.load(sys.stdin)['pagination']['count'])")

# Generate a client secret for create/update.
# On create, Authentik auto-generates one and returns it in the response.
# On update, the secret is masked in API responses ("###hidden###"), so we
# inject a freshly generated secret so the config file stays consistent.
CLIENT_SECRET_NEW=$(python3 -c "import secrets; print(secrets.token_hex(32))")

PROVIDER_PAYLOAD=$(python3 -c "
import json, sys
print(json.dumps({
  'name': 'TeamCity',
  'client_type': 'confidential',
  'client_id': '$TC_CLIENT_ID',
  'client_secret': '$CLIENT_SECRET_NEW',
  'authorization_flow': '$AUTH_FLOW_PK',
  'invalidation_flow': '$INV_FLOW_PK',
  'redirect_uris': [{'url': '$TC_URL/app/oidc/callback', 'matching_mode': 'strict'}],
  'sub_mode': 'user_username',
  'property_mappings': $SCOPE_PKS_JSON,
  'signing_key': '$SIGNING_KEY_PK',
  'include_claims_in_id_token': True,
  'backchannel_logout_uri': '$TC_URL_FROM_AK/app/oidc/backchannel-logout',
  'backchannel_logout_session_required': True,
}))")

if [[ "$PROVIDER_COUNT" -eq 0 ]]; then
  info "Creating OAuth2 provider '$TC_CLIENT_ID' ..."
  local_tmp=$(mktemp)
  HTTP_STATUS=$(curl -s -o "$local_tmp" -w "%{http_code}" -X POST \
    -H "Authorization: Bearer $AUTHENTIK_TOKEN" \
    -H "Content-Type: application/json" \
    "$AUTHENTIK_URL/api/v3/providers/oauth2/" \
    -d "$PROVIDER_PAYLOAD")
  PROVIDER_RESPONSE=$(cat "$local_tmp"); rm -f "$local_tmp"
  [[ "$HTTP_STATUS" == "201" ]] || error "Failed to create OAuth2 provider (HTTP $HTTP_STATUS): $PROVIDER_RESPONSE"
  PROVIDER_PK=$(echo "$PROVIDER_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['pk'])")
  # Use the secret we sent (Authentik accepts an explicit client_secret on create)
  CLIENT_SECRET="$CLIENT_SECRET_NEW"
  info "Provider created (pk: $PROVIDER_PK)."
else
  PROVIDER_PK=$(echo "$EXISTING_PROVIDERS" | python3 -c "import sys,json; print(json.load(sys.stdin)['results'][0]['pk'])")
  info "Provider already exists (pk: $PROVIDER_PK), updating ..."
  local_tmp=$(mktemp)
  HTTP_STATUS=$(curl -s -o "$local_tmp" -w "%{http_code}" -X PUT \
    -H "Authorization: Bearer $AUTHENTIK_TOKEN" \
    -H "Content-Type: application/json" \
    "$AUTHENTIK_URL/api/v3/providers/oauth2/$PROVIDER_PK/" \
    -d "$PROVIDER_PAYLOAD")
  local_body=$(cat "$local_tmp"); rm -f "$local_tmp"
  [[ "$HTTP_STATUS" == "200" ]] || error "Failed to update provider (HTTP $HTTP_STATUS): $local_body"
  CLIENT_SECRET="$CLIENT_SECRET_NEW"
  info "Provider updated with new client secret."
fi

# ---- Create or update Application ------------------------------------------

info "Looking up application '$TC_APP_SLUG' ..."
EXISTING_APPS=$(ak_api GET "$AUTHENTIK_URL/api/v3/core/applications/?slug=$TC_APP_SLUG")
APP_COUNT=$(echo "$EXISTING_APPS" | python3 -c "import sys,json; print(json.load(sys.stdin)['pagination']['count'])")

APP_PAYLOAD=$(python3 -c "
import json
print(json.dumps({
  'name': 'TeamCity',
  'slug': '$TC_APP_SLUG',
  'provider': $PROVIDER_PK,
}))")

if [[ "$APP_COUNT" -eq 0 ]]; then
  info "Creating application '$TC_APP_SLUG' ..."
  local_tmp=$(mktemp)
  HTTP_STATUS=$(curl -s -o "$local_tmp" -w "%{http_code}" -X POST \
    -H "Authorization: Bearer $AUTHENTIK_TOKEN" \
    -H "Content-Type: application/json" \
    "$AUTHENTIK_URL/api/v3/core/applications/" \
    -d "$APP_PAYLOAD")
  local_body=$(cat "$local_tmp"); rm -f "$local_tmp"
  [[ "$HTTP_STATUS" == "201" ]] || error "Failed to create application (HTTP $HTTP_STATUS): $local_body"
  info "Application created."
else
  info "Application already exists, updating ..."
  local_tmp=$(mktemp)
  HTTP_STATUS=$(curl -s -o "$local_tmp" -w "%{http_code}" -X PUT \
    -H "Authorization: Bearer $AUTHENTIK_TOKEN" \
    -H "Content-Type: application/json" \
    "$AUTHENTIK_URL/api/v3/core/applications/$TC_APP_SLUG/" \
    -d "$APP_PAYLOAD")
  local_body=$(cat "$local_tmp"); rm -f "$local_tmp"
  [[ "$HTTP_STATUS" == "200" ]] || error "Failed to update application (HTTP $HTTP_STATUS): $local_body"
  info "Application updated."
fi

# ---- Write plugin config file ----------------------------------------------

ISSUER_URL="$AUTHENTIK_URL/application/o/$TC_APP_SLUG"

info "Writing plugin config to $TC_CONFIG_FILE ..."
python3 -c "
import json
config = {
  'issuerUrl': '$ISSUER_URL',
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
info "  Authentik provider   : TeamCity (client_id: $TC_CLIENT_ID)"
info "  Issuer URL           : $ISSUER_URL"
info "  Callback URL         : $TC_URL/app/oidc/callback"
info "  Config file          : $TC_CONFIG_FILE"
info ""
info "To test: open $TC_URL/app/oidc/login in your browser."
info "The plugin hot-reloads the config file — no TeamCity restart needed."
