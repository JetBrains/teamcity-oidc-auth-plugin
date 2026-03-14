package org.jetbrains.teamcity.oidc.auth;

import jetbrains.buildServer.RootUrlHolder;
import jetbrains.buildServer.controllers.AuthorizationInterceptor;
import jetbrains.buildServer.controllers.interceptors.auth.HttpAuthenticationResult;
import jetbrains.buildServer.groups.SUserGroup;
import jetbrains.buildServer.groups.UserGroupManager;
import jetbrains.buildServer.serverSide.auth.LoginConfiguration;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.users.UserModel;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.jetbrains.teamcity.oidc.InMemoryOidcPluginSettingsStorage;
import org.jetbrains.teamcity.oidc.config.OidcClaimMappingSettings;
import org.jetbrains.teamcity.oidc.config.OidcPluginSettings;
import org.jetbrains.teamcity.oidc.oidc.*;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class OidcAuthenticationSchemeTest {

    private static final String ISSUER    = "https://idp.example.com";
    private static final String CLIENT_ID = "teamcity";
    private static final String USERNAME  = "testuser";
    private static final String EMAIL     = "testuser@example.com";
    private static final String STATE     = "test-state";
    private static final String NONCE     = "test-nonce";
    private static final String CODE      = "auth-code-123";

    private OidcPluginSettings settings;
    private InMemoryOidcPluginSettingsStorage settingsStorage;
    private OidcClient mockClient;
    private OidcIdTokenValidator mockValidator;
    private OidcStateManager mockStateManager;
    private UserModel mockUserModel;
    private UserGroupManager mockGroupManager;
    private RootUrlHolder mockRootUrl;
    private SUser mockUser;
    private OidcIdTokenClaims validClaims;
    private OidcTokenResponse validTokenResponse;
    private OidcAuthenticationScheme scheme;

    @Before
    public void setUp() throws Exception {
        settings = new OidcPluginSettings();
        settings.setIssuerUrl(ISSUER);
        settings.setClientId(CLIENT_ID);
        settings.setClientSecret("secret");
        settings.setDiscoveryEnabled(false);
        settings.setTokenEndpoint("https://idp.example.com/token");
        settings.setJwksUri("https://idp.example.com/jwks");
        OidcClaimMappingSettings usernameMapping = new OidcClaimMappingSettings();
        usernameMapping.setMappingType(OidcClaimMappingSettings.MappingType.CLAIM);
        usernameMapping.setClaimName("preferred_username");
        settings.setUsernameClaim(usernameMapping);
        settingsStorage = new InMemoryOidcPluginSettingsStorage(settings);

        mockClient    = mock(OidcClient.class);
        mockValidator = mock(OidcIdTokenValidator.class);
        mockStateManager = mock(OidcStateManager.class);
        mockUserModel = mock(UserModel.class);
        mockGroupManager = mock(UserGroupManager.class);
        mockRootUrl   = mock(RootUrlHolder.class);
        mockUser      = mock(SUser.class);

        when(mockRootUrl.getRootUrl()).thenReturn("http://localhost:8111");
        when(mockUser.getUsername()).thenReturn(USERNAME);
        when(mockUser.getRealm()).thenReturn(null);
        when(mockUser.getUserGroups()).thenReturn(Collections.emptyList());
        when(mockGroupManager.getUserGroups()).thenReturn(Collections.emptyList());

        Map<String, Object> rawClaims = new HashMap<>();
        rawClaims.put("preferred_username", USERNAME);
        rawClaims.put("email", EMAIL);
        rawClaims.put("name", "Test User");
        validClaims = new OidcIdTokenClaims(
                ISSUER, "sub-123", Collections.singletonList(CLIENT_ID),
                System.currentTimeMillis() / 1000 + 3600,
                System.currentTimeMillis() / 1000 - 10,
                NONCE, EMAIL, true, "Test User", USERNAME, rawClaims);

        validTokenResponse = mock(OidcTokenResponse.class);
        when(validTokenResponse.getIdToken()).thenReturn("raw-id-token");
        when(validTokenResponse.getAccessToken()).thenReturn("access-token");

        when(mockClient.exchangeCodeForTokens(anyString(), eq(CODE), anyString(), eq(CLIENT_ID), eq("secret")))
                .thenReturn(validTokenResponse);
        when(mockValidator.validateAndDecode(anyString(), anyString(), eq(ISSUER), eq(CLIENT_ID), eq(NONCE), anyInt()))
                .thenReturn(validClaims);
        when(mockStateManager.validateAndConsumeState(any(), eq(STATE))).thenReturn(true);
        when(mockStateManager.consumeNonce(any())).thenReturn(NONCE);

        scheme = new OidcAuthenticationScheme(
                mock(LoginConfiguration.class), settingsStorage, mockClient, mockValidator,
                mockStateManager, mockUserModel, mockGroupManager, mockRootUrl,
                mock(WebControllerManager.class), mock(AuthorizationInterceptor.class));
    }

    private MockHttpServletRequest callbackRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/app/oidc/callback");
        req.setParameter("code", CODE);
        req.setParameter("state", STATE);
        req.setSession(new MockHttpSession());
        return req;
    }

    @Test
    public void existingUserIsAuthenticated() throws Exception {
        when(mockUserModel.findUserAccount(null, USERNAME)).thenReturn(mockUser);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        HttpAuthenticationResult result = scheme.processAuthenticationRequest(callbackRequest(), resp, new HashMap<>());

        assertEquals(HttpAuthenticationResult.Type.AUTHENTICATED, result.getType());
    }

    @Test
    public void unknownUserWithAutoCreateDisabled_isRejected() throws Exception {
        settings.setCreateUsersAutomatically(false);
        when(mockUserModel.findUserAccount(null, USERNAME)).thenReturn(null);

        HttpAuthenticationResult result = scheme.processAuthenticationRequest(callbackRequest(), new MockHttpServletResponse(), new HashMap<>());

        assertEquals(HttpAuthenticationResult.Type.UNAUTHENTICATED, result.getType());
        verify(mockUserModel, never()).createUserAccount(any(), any());
    }

    @Test
    public void unknownUserWithAutoCreateEnabled_isCreated() throws Exception {
        settings.setCreateUsersAutomatically(true);
        when(mockUserModel.findUserAccount(null, USERNAME)).thenReturn(null);
        when(mockUserModel.createUserAccount(null, USERNAME)).thenReturn(mockUser);

        HttpAuthenticationResult result = scheme.processAuthenticationRequest(callbackRequest(), new MockHttpServletResponse(), new HashMap<>());

        assertEquals(HttpAuthenticationResult.Type.AUTHENTICATED, result.getType());
        verify(mockUserModel).createUserAccount(null, USERNAME);
        verify(mockUser).updateUserAccount(eq(USERNAME), any(), eq(EMAIL));
    }

    @Test
    public void emailDomainAllowlistBlocks_disallowedDomain() throws Exception {
        settings.setAllowedEmailDomains(Collections.singletonList("allowed.com"));
        when(mockUserModel.findUserAccount(null, USERNAME)).thenReturn(mockUser);

        HttpAuthenticationResult result = scheme.processAuthenticationRequest(callbackRequest(), new MockHttpServletResponse(), new HashMap<>());

        assertEquals(HttpAuthenticationResult.Type.UNAUTHENTICATED, result.getType());
    }

    @Test
    public void emailDomainAllowlistPermits_allowedDomain() throws Exception {
        settings.setAllowedEmailDomains(Collections.singletonList("example.com"));
        when(mockUserModel.findUserAccount(null, USERNAME)).thenReturn(mockUser);

        HttpAuthenticationResult result = scheme.processAuthenticationRequest(callbackRequest(), new MockHttpServletResponse(), new HashMap<>());

        assertEquals(HttpAuthenticationResult.Type.AUTHENTICATED, result.getType());
    }

    @Test
    public void emptyAllowlistPermitsAll() throws Exception {
        settings.setAllowedEmailDomains(Collections.emptyList());
        when(mockUserModel.findUserAccount(null, USERNAME)).thenReturn(mockUser);

        HttpAuthenticationResult result = scheme.processAuthenticationRequest(callbackRequest(), new MockHttpServletResponse(), new HashMap<>());

        assertEquals(HttpAuthenticationResult.Type.AUTHENTICATED, result.getType());
    }

    @Test
    public void invalidStateIsRejected() throws Exception {
        when(mockStateManager.validateAndConsumeState(any(), any())).thenReturn(false);
        when(mockUserModel.findUserAccount(null, USERNAME)).thenReturn(mockUser);

        HttpAuthenticationResult result = scheme.processAuthenticationRequest(callbackRequest(), new MockHttpServletResponse(), new HashMap<>());

        assertEquals(HttpAuthenticationResult.Type.UNAUTHENTICATED, result.getType());
    }

    @Test
    public void missingCodeParameterReturnsNotApplicable() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/app/oidc/callback");
        // no 'code' param

        HttpAuthenticationResult result = scheme.processAuthenticationRequest(req, new MockHttpServletResponse(), new HashMap<>());

        assertEquals(HttpAuthenticationResult.Type.NOT_APPLICABLE, result.getType());
    }

    @Test
    public void requestOutsideCallbackPathReturnsNotApplicable() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/some/other/path");
        req.setParameter("code", CODE);

        HttpAuthenticationResult result = scheme.processAuthenticationRequest(req, new MockHttpServletResponse(), new HashMap<>());

        assertEquals(HttpAuthenticationResult.Type.NOT_APPLICABLE, result.getType());
    }

    @Test
    public void groupsAreAssignedFromClaims() throws Exception {
        settings.setAssignGroups(true);
        settings.setGroupsClaimName("groups");

        // Add groups to raw claims
        Map<String, Object> rawWithGroups = new HashMap<>(validClaims.getRaw());
        rawWithGroups.put("groups", Arrays.asList("devs", "admins"));
        OidcIdTokenClaims claimsWithGroups = new OidcIdTokenClaims(
                validClaims.getIss(), validClaims.getSub(), validClaims.getAud(),
                validClaims.getExp(), validClaims.getIat(), validClaims.getNonce(),
                validClaims.getEmail(), validClaims.getEmailVerified(),
                validClaims.getName(), validClaims.getPreferredUsername(), rawWithGroups);
        when(mockValidator.validateAndDecode(anyString(), anyString(), eq(ISSUER), eq(CLIENT_ID), eq(NONCE), anyInt()))
                .thenReturn(claimsWithGroups);

        SUserGroup devsGroup  = mock(SUserGroup.class);
        SUserGroup adminGroup = mock(SUserGroup.class);
        when(devsGroup.getKey()).thenReturn("devs");
        when(adminGroup.getKey()).thenReturn("admins");
        when(mockGroupManager.getUserGroups()).thenReturn(Arrays.asList(devsGroup, adminGroup));
        when(mockUserModel.findUserAccount(null, USERNAME)).thenReturn(mockUser);

        scheme.processAuthenticationRequest(callbackRequest(), new MockHttpServletResponse(), new HashMap<>());

        verify(devsGroup).addUser(mockUser);
        verify(adminGroup).addUser(mockUser);
    }

    @Test
    public void groupsAreRemovedWhenRemoveUnassignedEnabled() throws Exception {
        settings.setAssignGroups(true);
        settings.setRemoveUnassignedGroups(true);
        settings.setGroupsClaimName("groups");

        // Token has no groups
        when(mockValidator.validateAndDecode(anyString(), anyString(), eq(ISSUER), eq(CLIENT_ID), eq(NONCE), anyInt()))
                .thenReturn(validClaims);

        SUserGroup existingGroup = mock(SUserGroup.class);
        when(existingGroup.getKey()).thenReturn("old-group");
        when(mockGroupManager.getUserGroups()).thenReturn(Collections.singletonList(existingGroup));
        when(mockUser.getUserGroups()).thenReturn(Collections.singletonList(existingGroup));
        when(mockUserModel.findUserAccount(null, USERNAME)).thenReturn(mockUser);

        scheme.processAuthenticationRequest(callbackRequest(), new MockHttpServletResponse(), new HashMap<>());

        verify(existingGroup).removeUser(mockUser);
    }

    @Test
    public void groupsAreNotRemovedWhenRemoveUnassignedDisabled() throws Exception {
        settings.setAssignGroups(true);
        settings.setRemoveUnassignedGroups(false);
        settings.setGroupsClaimName("groups");

        SUserGroup existingGroup = mock(SUserGroup.class);
        when(existingGroup.getKey()).thenReturn("old-group");
        when(mockGroupManager.getUserGroups()).thenReturn(Collections.singletonList(existingGroup));
        when(mockUser.getUserGroups()).thenReturn(Collections.singletonList(existingGroup));
        when(mockUserModel.findUserAccount(null, USERNAME)).thenReturn(mockUser);

        scheme.processAuthenticationRequest(callbackRequest(), new MockHttpServletResponse(), new HashMap<>());

        verify(existingGroup, never()).removeUser(any());
    }

    @Test
    public void usernameFromSubClaim() throws Exception {
        OidcClaimMappingSettings subMapping = new OidcClaimMappingSettings();
        subMapping.setMappingType(OidcClaimMappingSettings.MappingType.SUB);
        settings.setUsernameClaim(subMapping);
        when(mockUserModel.findUserAccount(null, "sub-123")).thenReturn(mockUser);

        HttpAuthenticationResult result = scheme.processAuthenticationRequest(callbackRequest(), new MockHttpServletResponse(), new HashMap<>());

        assertEquals(HttpAuthenticationResult.Type.AUTHENTICATED, result.getType());
        verify(mockUserModel).findUserAccount(null, "sub-123");
    }

    @Test
    public void tokenValidationFailure_isRejected() throws Exception {
        when(mockValidator.validateAndDecode(anyString(), anyString(), eq(ISSUER), eq(CLIENT_ID), eq(NONCE), anyInt()))
                .thenThrow(new OidcAuthException("Token expired"));

        HttpAuthenticationResult result = scheme.processAuthenticationRequest(callbackRequest(), new MockHttpServletResponse(), new HashMap<>());

        assertEquals(HttpAuthenticationResult.Type.UNAUTHENTICATED, result.getType());
    }

    @Test
    public void tokenExchangeFailure_isRejected() throws Exception {
        when(mockClient.exchangeCodeForTokens(anyString(), eq(CODE), anyString(), eq(CLIENT_ID), eq("secret")))
                .thenThrow(new OidcClientException("Network error"));

        HttpAuthenticationResult result = scheme.processAuthenticationRequest(callbackRequest(), new MockHttpServletResponse(), new HashMap<>());

        assertEquals(HttpAuthenticationResult.Type.UNAUTHENTICATED, result.getType());
    }
}
