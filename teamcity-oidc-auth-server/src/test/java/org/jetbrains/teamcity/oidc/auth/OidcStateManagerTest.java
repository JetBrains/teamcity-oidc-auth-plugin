package org.jetbrains.teamcity.oidc.auth;

import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class OidcStateManagerTest {

    private OidcStateManager manager;

    @Before
    public void setUp() {
        manager = new OidcStateManager();
    }

    @Test
    public void generatedStateIsStoredInSession() {
        MockHttpSession session = new MockHttpSession();
        String state = manager.generateState(session);

        assertNotNull(state);
        assertFalse(state.isEmpty());
        assertEquals(state, session.getAttribute("oidc.state"));
    }

    @Test
    public void validStateIsConsumed() {
        MockHttpSession session = new MockHttpSession();
        String state = manager.generateState(session);

        assertTrue(manager.validateAndConsumeState(session, state));
        assertNull(session.getAttribute("oidc.state"));
    }

    @Test
    public void wrongStateIsRejected() {
        MockHttpSession session = new MockHttpSession();
        manager.generateState(session);

        assertFalse(manager.validateAndConsumeState(session, "wrong-state"));
    }

    @Test
    public void stateCannotBeReused() {
        MockHttpSession session = new MockHttpSession();
        String state = manager.generateState(session);

        assertTrue(manager.validateAndConsumeState(session, state));
        assertFalse(manager.validateAndConsumeState(session, state));
    }

    @Test
    public void nullStateIsRejected() {
        MockHttpSession session = new MockHttpSession();
        manager.generateState(session);

        assertFalse(manager.validateAndConsumeState(session, null));
    }

    @Test
    public void generateNonceIsStoredAndConsumed() {
        MockHttpSession session = new MockHttpSession();
        String nonce = manager.generateNonce(session);

        assertNotNull(nonce);
        assertFalse(nonce.isEmpty());
        assertEquals(nonce, session.getAttribute("oidc.nonce"));

        String consumed = manager.consumeNonce(session);
        assertEquals(nonce, consumed);
        assertNull(session.getAttribute("oidc.nonce"));
    }

    @Test
    public void consumeNonceOnEmptySessionReturnsNull() {
        MockHttpSession session = new MockHttpSession();
        assertNull(manager.consumeNonce(session));
    }

    @Test
    public void eachGeneratedStateIsUnique() {
        MockHttpSession session = new MockHttpSession();
        Set<String> states = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            states.add(manager.generateState(session));
        }
        assertEquals(1000, states.size());
    }
}
