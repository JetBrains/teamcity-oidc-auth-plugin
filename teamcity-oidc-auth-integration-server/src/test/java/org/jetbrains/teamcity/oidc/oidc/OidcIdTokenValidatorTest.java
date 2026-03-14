package org.jetbrains.teamcity.oidc.oidc;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Date;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class OidcIdTokenValidatorTest {

    private static RSAKey testKey;
    private static String testKid = "test-kid-1";
    private static String jwksJson;

    private OidcClient mockClient;
    private OidcIdTokenValidator validator;

    // Fixed "now" for all time-sensitive tests: 2024-01-15 12:00:00 UTC
    private static final Instant NOW = Instant.parse("2024-01-15T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final String ISSUER   = "https://idp.example.com/realms/test";
    private static final String CLIENT_ID = "teamcity";

    @BeforeClass
    public static void generateKey() throws Exception {
        testKey = new RSAKeyGenerator(2048)
                .keyID(testKid)
                .generate();
        // Build a JWKS JSON from the public key
        com.nimbusds.jose.jwk.JWKSet jwkSet = new com.nimbusds.jose.jwk.JWKSet(testKey.toPublicJWK());
        jwksJson = jwkSet.toString();
    }

    @Before
    public void setUp() throws Exception {
        mockClient = mock(OidcClient.class);
        when(mockClient.fetchJwks(anyString())).thenReturn(jwksJson);
        validator = new OidcIdTokenValidator(mockClient, FIXED_CLOCK);
    }

    // ---- Helper: build a signed JWT ----------------------------------------

    private String buildToken(JWTClaimsSet claims) throws Exception {
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(testKid).build();
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new RSASSASigner(testKey));
        return jwt.serialize();
    }

    private JWTClaimsSet validClaims() {
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("user-sub-123")
                .audience(CLIENT_ID)
                .expirationTime(Date.from(NOW.plusSeconds(3600)))
                .issueTime(Date.from(NOW.minusSeconds(10)))
                .claim("nonce", "test-nonce")
                .claim("email", "user@example.com")
                .claim("name", "Test User")
                .claim("preferred_username", "testuser")
                .build();
    }

    // ---- Tests -------------------------------------------------------------

    @Test
    public void validTokenIsAccepted() throws Exception {
        String raw = buildToken(validClaims());
        OidcIdTokenClaims claims = validator.validateAndDecode(raw, "https://idp/jwks", ISSUER, CLIENT_ID, "test-nonce", 30);

        assertEquals("user-sub-123", claims.getSub());
        assertEquals("user@example.com", claims.getEmail());
        assertEquals("Test User", claims.getName());
        assertEquals("testuser", claims.getPreferredUsername());
        assertEquals(ISSUER, claims.getIss());
    }

    @Test(expected = OidcAuthException.class)
    public void expiredTokenIsRejected() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject("sub").audience(CLIENT_ID)
                .expirationTime(Date.from(NOW.minusSeconds(60))) // expired
                .issueTime(Date.from(NOW.minusSeconds(120)))
                .build();
        validator.validateAndDecode(buildToken(claims), "https://idp/jwks", ISSUER, CLIENT_ID, null, 30);
    }

    @Test(expected = OidcAuthException.class)
    public void tokenNotYetValidIsRejected() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject("sub").audience(CLIENT_ID)
                .expirationTime(Date.from(NOW.plusSeconds(3600)))
                .issueTime(Date.from(NOW.plusSeconds(120))) // issued in future
                .build();
        validator.validateAndDecode(buildToken(claims), "https://idp/jwks", ISSUER, CLIENT_ID, null, 30);
    }

    @Test(expected = OidcAuthException.class)
    public void wrongIssuerIsRejected() throws Exception {
        String raw = buildToken(validClaims());
        validator.validateAndDecode(raw, "https://idp/jwks", "https://other.example.com", CLIENT_ID, null, 30);
    }

    @Test(expected = OidcAuthException.class)
    public void wrongAudienceIsRejected() throws Exception {
        String raw = buildToken(validClaims());
        validator.validateAndDecode(raw, "https://idp/jwks", ISSUER, "wrong-client", null, 30);
    }

    @Test(expected = OidcAuthException.class)
    public void wrongNonceIsRejected() throws Exception {
        String raw = buildToken(validClaims());
        validator.validateAndDecode(raw, "https://idp/jwks", ISSUER, CLIENT_ID, "wrong-nonce", 30);
    }

    @Test
    public void nullNonceSkipsNonceCheck() throws Exception {
        String raw = buildToken(validClaims());
        // Should not throw even though token has a nonce
        OidcIdTokenClaims claims = validator.validateAndDecode(raw, "https://idp/jwks", ISSUER, CLIENT_ID, null, 30);
        assertNotNull(claims);
    }

    @Test(expected = OidcAuthException.class)
    public void invalidSignatureIsRejected() throws Exception {
        // Swap to a different key for signing
        RSAKey otherKey = new RSAKeyGenerator(2048).keyID(testKid).generate();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(testKid).build();
        SignedJWT jwt = new SignedJWT(header, validClaims());
        jwt.sign(new RSASSASigner(otherKey));
        validator.validateAndDecode(jwt.serialize(), "https://idp/jwks", ISSUER, CLIENT_ID, "test-nonce", 30);
    }

    @Test(expected = OidcAuthException.class)
    public void malformedJwtIsRejected() throws Exception {
        validator.validateAndDecode("not.a.jwt", "https://idp/jwks", ISSUER, CLIENT_ID, null, 30);
    }

    @Test
    public void clockSkewAllowsSlightlyExpiredToken() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject("sub").audience(CLIENT_ID)
                .expirationTime(Date.from(NOW.minusSeconds(20))) // expired 20s ago
                .issueTime(Date.from(NOW.minusSeconds(120)))
                .build();
        // 30s skew should allow a token expired 20s ago
        OidcIdTokenClaims result = validator.validateAndDecode(buildToken(claims), "https://idp/jwks", ISSUER, CLIENT_ID, null, 30);
        assertNotNull(result);
    }

    @Test
    public void unknownKidTriggersJwksRefresh() throws Exception {
        // Sign with a kid that's not in our test JWKS
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("unknown-kid").build();
        SignedJWT jwt = new SignedJWT(header, validClaims());
        jwt.sign(new RSASSASigner(testKey));

        try {
            validator.validateAndDecode(jwt.serialize(), "https://idp/jwks", ISSUER, CLIENT_ID, "test-nonce", 30);
        } catch (OidcAuthException ignored) { /* expected — key not found after refresh */ }

        // Verify JWKS was fetched twice (initial + refresh after unknown kid)
        verify(mockClient, times(2)).fetchJwks("https://idp/jwks");
    }

    @Test
    public void claimsAreMappedCorrectly() throws Exception {
        String raw = buildToken(validClaims());
        OidcIdTokenClaims claims = validator.validateAndDecode(raw, "https://idp/jwks", ISSUER, CLIENT_ID, "test-nonce", 30);

        assertEquals("user-sub-123", claims.getSub());
        assertEquals(ISSUER, claims.getIss());
        assertTrue(claims.getAud().contains(CLIENT_ID));
        assertEquals("user@example.com", claims.getEmail());
        assertEquals("Test User", claims.getName());
        assertEquals("testuser", claims.getPreferredUsername());
        assertNotNull(claims.getRaw());
        assertFalse(claims.getRaw().isEmpty());
    }
}
