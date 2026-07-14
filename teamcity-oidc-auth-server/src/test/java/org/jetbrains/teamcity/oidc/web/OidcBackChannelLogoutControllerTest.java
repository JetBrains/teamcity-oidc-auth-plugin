package org.jetbrains.teamcity.oidc.web;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jetbrains.buildServer.controllers.AuthorizationInterceptor;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.SecurityContextEx;
import jetbrains.buildServer.auth.SessionModel;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.users.UserModelEx;
import jetbrains.buildServer.users.impl.UserEx;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.jetbrains.teamcity.oidc.InMemoryOidcPluginSettingsStorage;
import org.jetbrains.teamcity.oidc.OidcConstants;
import org.jetbrains.teamcity.oidc.config.OidcPluginSettings;
import org.jetbrains.teamcity.oidc.oidc.OidcClient;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jetbrains.buildServer.users.UserSet;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class OidcBackChannelLogoutControllerTest {

    private static final String ISSUER    = "https://idp.example.com/realms/test";
    private static final String CLIENT_ID = "teamcity";
    private static final String SUB       = "user-sub-123";
    private static final long   USER_ID   = 42L;
    private static final String KID       = "test-kid";

    private static RSAKey testKey;
    private static String jwksJson;

    private UserModelEx mockUserModel;
    private SecurityContextEx mockSecurityContext;
    private SessionModel mockSessionModel;
    private OidcClient mockClient;
    private OidcBackChannelLogoutController controller;

    @BeforeClass
    public static void generateKey() throws Exception {
        testKey = new RSAKeyGenerator(2048).keyID(KID).generate();
        jwksJson = new com.nimbusds.jose.jwk.JWKSet(testKey.toPublicJWK()).toString();
    }

    @Before
    public void setUp() throws Throwable {
        OidcPluginSettings settings = new OidcPluginSettings();
        settings.setIssuerUrl(ISSUER);
        settings.setClientId(CLIENT_ID);
        settings.setDiscoveryEnabled(false);
        settings.setJwksUri("https://idp.example.com/jwks");

        mockClient = mock(OidcClient.class);
        when(mockClient.fetchJwks(anyString())).thenReturn(jwksJson);

        mockUserModel = mock(UserModelEx.class);
        when(mockUserModel.findUsersByAttributeValue(anyString(), anyString(), anyBoolean())).thenReturn(userSetOf());

        mockSecurityContext = mock(SecurityContextEx.class);
        doAnswer(inv -> {
            SecurityContextEx.RunAsActionWithResult<?> action = inv.getArgument(0);
            try {
                return action.run();
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }).when(mockSecurityContext).runAsSystem(any(SecurityContextEx.RunAsActionWithResult.class));

        doAnswer(inv -> {
            SecurityContextEx.RunAsAction action = inv.getArgument(1);
            action.run();
            return null;
        }).when(mockSecurityContext).runAs(any(SUser.class), any(SecurityContextEx.RunAsAction.class));

        mockSessionModel = mock(SessionModel.class);
        when(mockSessionModel.terminateSessionsByUserId(anyLong())).thenReturn(true);

        controller = new OidcBackChannelLogoutController(
                mock(SBuildServer.class),
                mock(WebControllerManager.class),
                new InMemoryOidcPluginSettingsStorage(settings),
                mockClient,
                mockUserModel,
                mockSecurityContext,
                mockSessionModel,
                mock(AuthorizationInterceptor.class));
    }

    @SafeVarargs
    private static <T extends jetbrains.buildServer.users.SUser> UserSet<T> userSetOf(T... users) {
        Set<T> set = new HashSet<>(Arrays.asList(users));
        return () -> set;
    }

    private UserEx mockUserWithSub(String sub, long userId) {
        UserEx user = mock(UserEx.class);
        when(user.getId()).thenReturn(userId);
        when(user.getAttribute(OidcConstants.OIDC_SUB_ATTRIBUTE)).thenReturn(sub);
        return user;
    }

    private static Map<String, Object> buildEventsMap() {
        Map<String, Object> events = new HashMap<>();
        events.put(OidcConstants.BACKCHANNEL_LOGOUT_EVENT, Collections.emptyMap());
        return events;
    }

    private String buildLogoutToken(String sub, String iss, String aud) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(iss)
                .subject(sub)
                .audience(aud)
                .issueTime(new Date())
                .jwtID("unique-jti")
                .claim("events", buildEventsMap())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build(), claims);
        jwt.sign(new RSASSASigner(testKey));
        return jwt.serialize();
    }

    private MockHttpServletResponse handle(String logoutToken) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", OidcConstants.BACKCHANNEL_LOGOUT_PATH);
        if (logoutToken != null) req.setParameter("logout_token", logoutToken);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.doHandle(req, resp);
        return resp;
    }

    @Test
    public void knownUser_sessionsAreTerminated() throws Exception {
        UserEx knownUser = mockUserWithSub(SUB, USER_ID);
        when(mockUserModel.findUsersByAttributeValue(OidcConstants.OIDC_SUB_ATTRIBUTE, SUB, true)).thenReturn(userSetOf(knownUser));

        MockHttpServletResponse resp = handle(buildLogoutToken(SUB, ISSUER, CLIENT_ID));

        assertEquals(200, resp.getStatus());
        verify(mockSessionModel).terminateSessionsByUserId(USER_ID);
    }

    @Test
    public void unknownSub_returnsOkWithoutTerminating() throws Exception {
        // No user has this sub attribute
        MockHttpServletResponse resp = handle(buildLogoutToken(SUB, ISSUER, CLIENT_ID));

        assertEquals(200, resp.getStatus());
        verify(mockSessionModel, never()).terminateSessionsByUserId(anyLong());
    }

    @Test
    public void missingLogoutToken_returns400() throws Exception {
        MockHttpServletResponse resp = handle(null);
        assertEquals(400, resp.getStatus());
    }

    @Test
    public void nonPostRequest_returns405() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", OidcConstants.BACKCHANNEL_LOGOUT_PATH);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.doHandle(req, resp);
        assertEquals(405, resp.getStatus());
    }

    @Test
    public void wrongIssuer_returns400() throws Exception {
        MockHttpServletResponse resp = handle(buildLogoutToken(SUB, "https://other.example.com", CLIENT_ID));
        assertEquals(400, resp.getStatus());
    }

    @Test
    public void wrongAudience_returns400() throws Exception {
        MockHttpServletResponse resp = handle(buildLogoutToken(SUB, ISSUER, "wrong-client"));
        assertEquals(400, resp.getStatus());
    }

    @Test
    public void tokenWithNonce_returns400() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject(SUB).audience(CLIENT_ID)
                .issueTime(new Date())
                .claim("events", buildEventsMap())
                .claim("nonce", "must-not-be-present")
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build(), claims);
        jwt.sign(new RSASSASigner(testKey));

        MockHttpServletResponse resp = handle(jwt.serialize());
        assertEquals(400, resp.getStatus());
    }

    @Test
    public void missingEventsClaim_returns400() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject(SUB).audience(CLIENT_ID)
                .issueTime(new Date())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build(), claims);
        jwt.sign(new RSASSASigner(testKey));

        MockHttpServletResponse resp = handle(jwt.serialize());
        assertEquals(400, resp.getStatus());
    }

    @Test
    public void invalidSignature_returns400() throws Exception {
        RSAKey otherKey = new RSAKeyGenerator(2048).keyID(KID).generate();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject(SUB).audience(CLIENT_ID)
                .issueTime(new Date())
                .claim("events", buildEventsMap())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build(), claims);
        jwt.sign(new RSASSASigner(otherKey));

        MockHttpServletResponse resp = handle(jwt.serialize());
        assertEquals(400, resp.getStatus());
    }
}
