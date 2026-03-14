package org.jetbrains.teamcity.oidc.oidc;

/**
 * Thrown during authentication processing when a security check fails:
 * invalid state, expired token, signature mismatch, domain not allowed, etc.
 * Translated to {@code HttpAuthenticationResult.unauthenticated()} by the scheme.
 */
public class OidcAuthException extends Exception {

    public OidcAuthException(String message) {
        super(message);
    }

    public OidcAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
