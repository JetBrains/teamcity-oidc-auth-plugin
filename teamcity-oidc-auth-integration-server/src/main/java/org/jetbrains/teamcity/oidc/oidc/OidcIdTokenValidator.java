package org.jetbrains.teamcity.oidc.oidc;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jetbrains.buildServer.log.Loggers;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates and decodes OIDC ID tokens (JWT).
 * Uses nimbus-jose-jwt for signature verification against the IdP's JWKS.
 * An injectable {@link Clock} makes expiry checks deterministic in tests.
 */
public class OidcIdTokenValidator {

    private final OidcClient oidcClient;
    private final Clock clock;

    public OidcIdTokenValidator(@NotNull OidcClient oidcClient, @NotNull Clock clock) {
        this.oidcClient = oidcClient;
        this.clock = clock;
    }

    /**
     * Validates and decodes the raw ID token string.
     *
     * Checks: JWT structure, signature (JWKS), iss, aud, exp, iat, nonce.
     *
     * @param expectedNonce  when non-null, the token's nonce claim must equal this value
     * @throws OidcAuthException if any check fails
     */
    @NotNull
    public OidcIdTokenClaims validateAndDecode(
            @NotNull String rawIdToken,
            @NotNull String jwksUri,
            @NotNull String expectedIssuer,
            @NotNull String clientId,
            @Nullable String expectedNonce,
            int clockSkewSeconds) throws OidcAuthException {

        SignedJWT jwt = parseJwt(rawIdToken);
        JWTClaimsSet claims = extractClaims(jwt);

        verifySignature(jwt, jwksUri);
        checkIssuer(claims, expectedIssuer);
        checkAudience(claims, clientId);
        checkExpiry(claims, clockSkewSeconds);
        checkIssuedAt(claims, clockSkewSeconds);
        if (expectedNonce != null) {
            checkNonce(claims, expectedNonce);
        }

        return buildClaims(claims);
    }

    // ---- Validation steps --------------------------------------------------

    private SignedJWT parseJwt(String raw) throws OidcAuthException {
        try {
            return SignedJWT.parse(raw);
        } catch (Exception e) {
            throw new OidcAuthException("ID token is not a valid signed JWT: " + e.getMessage(), e);
        }
    }

    private JWTClaimsSet extractClaims(SignedJWT jwt) throws OidcAuthException {
        try {
            return jwt.getJWTClaimsSet();
        } catch (Exception e) {
            throw new OidcAuthException("Failed to parse ID token claims: " + e.getMessage(), e);
        }
    }

    private void verifySignature(SignedJWT jwt, String jwksUri) throws OidcAuthException {
        JWKSet jwkSet = fetchJwkSet(jwksUri);
        JWSHeader header = jwt.getHeader();
        String kid = header.getKeyID();

        JWK key = selectKey(jwkSet, kid);

        if (key == null && kid != null) {
            // kid not found — refresh JWKS once and retry
            Loggers.SERVER.debug("OIDC: unknown kid '" + kid + "', refreshing JWKS");
            oidcClient.invalidateDiscoveryCache();
            jwkSet = fetchJwkSet(jwksUri);
            key = selectKey(jwkSet, kid);
        }

        if (key == null) {
            throw new OidcAuthException("No matching key found in JWKS for kid=" + kid);
        }

        try {
            JWSVerifier verifier = buildVerifier(key, header.getAlgorithm());
            if (!jwt.verify(verifier)) {
                throw new OidcAuthException("ID token signature verification failed");
            }
        } catch (OidcAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new OidcAuthException("ID token signature verification error: " + e.getMessage(), e);
        }
    }

    private JWKSet fetchJwkSet(String jwksUri) throws OidcAuthException {
        try {
            String jwksJson = oidcClient.fetchJwks(jwksUri);
            return JWKSet.parse(jwksJson);
        } catch (OidcClientException e) {
            throw new OidcAuthException("Failed to fetch JWKS from " + jwksUri + ": " + e.getMessage(), e);
        } catch (Exception e) {
            throw new OidcAuthException("Failed to parse JWKS: " + e.getMessage(), e);
        }
    }

    @Nullable
    private JWK selectKey(JWKSet jwkSet, @Nullable String kid) {
        if (kid != null) {
            return jwkSet.getKeyByKeyId(kid);
        }
        List<JWK> keys = jwkSet.getKeys();
        return keys.isEmpty() ? null : keys.get(0);
    }

    private JWSVerifier buildVerifier(JWK key, JWSAlgorithm algorithm) throws Exception {
        if (key instanceof RSAKey) {
            return new RSASSAVerifier((RSAKey) key);
        } else if (key instanceof ECKey) {
            return new ECDSAVerifier((ECKey) key);
        }
        throw new OidcAuthException("Unsupported JWK key type: " + key.getKeyType());
    }

    private void checkIssuer(JWTClaimsSet claims, String expected) throws OidcAuthException {
        String iss = claims.getIssuer();
        if (!expected.equals(iss)) {
            throw new OidcAuthException("ID token issuer mismatch: expected '" + expected + "', got '" + iss + "'");
        }
    }

    private void checkAudience(JWTClaimsSet claims, String clientId) throws OidcAuthException {
        List<String> aud = claims.getAudience();
        if (aud == null || !aud.contains(clientId)) {
            throw new OidcAuthException("ID token audience does not contain client ID '" + clientId + "'");
        }
    }

    private void checkExpiry(JWTClaimsSet claims, int clockSkewSeconds) throws OidcAuthException {
        Date exp = claims.getExpirationTime();
        if (exp == null) throw new OidcAuthException("ID token missing exp claim");
        long nowMs = clock.millis();
        long skewMs = (long) clockSkewSeconds * 1000;
        if (exp.getTime() + skewMs < nowMs) {
            throw new OidcAuthException("ID token has expired (exp=" + exp.getTime() / 1000 + ")");
        }
    }

    private void checkIssuedAt(JWTClaimsSet claims, int clockSkewSeconds) throws OidcAuthException {
        Date iat = claims.getIssueTime();
        if (iat == null) return; // iat is optional
        long nowMs = clock.millis();
        long skewMs = (long) clockSkewSeconds * 1000;
        if (iat.getTime() > nowMs + skewMs) {
            throw new OidcAuthException("ID token issued in the future (iat=" + iat.getTime() / 1000 + ")");
        }
    }

    private void checkNonce(JWTClaimsSet claims, String expected) throws OidcAuthException {
        Object nonce = claims.getClaim("nonce");
        if (!expected.equals(nonce != null ? nonce.toString() : null)) {
            throw new OidcAuthException("ID token nonce mismatch");
        }
    }

    // ---- Claims extraction -------------------------------------------------

    private OidcIdTokenClaims buildClaims(JWTClaimsSet c) throws OidcAuthException {
        try {
            Map<String, Object> raw = new HashMap<>(c.getClaims());
            return new OidcIdTokenClaims(
                    c.getIssuer(),
                    c.getSubject(),
                    c.getAudience(),
                    c.getExpirationTime() != null ? c.getExpirationTime().getTime() / 1000 : 0,
                    c.getIssueTime() != null ? c.getIssueTime().getTime() / 1000 : 0,
                    claimString(c, "nonce"),
                    claimString(c, "email"),
                    claimBoolean(c, "email_verified"),
                    claimString(c, "name"),
                    claimString(c, "preferred_username"),
                    raw
            );
        } catch (Exception e) {
            throw new OidcAuthException("Failed to extract claims from ID token: " + e.getMessage(), e);
        }
    }

    @Nullable
    private String claimString(JWTClaimsSet claims, String name) {
        Object value = claims.getClaim(name);
        return value != null ? value.toString() : null;
    }

    @Nullable
    private Boolean claimBoolean(JWTClaimsSet claims, String name) {
        Object value = claims.getClaim(name);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) return Boolean.parseBoolean((String) value);
        return null;
    }
}
