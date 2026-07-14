package org.jetbrains.teamcity.oidc.oidc;

public class OidcClientException extends Exception {

    private final int httpStatus;
    private final String idpError;

    public OidcClientException(String message, int httpStatus, String idpError, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.idpError = idpError;
    }

    public OidcClientException(String message) {
        this(message, 0, null, null);
    }

    public OidcClientException(String message, Throwable cause) {
        this(message, 0, null, cause);
    }

    /** HTTP status code from the IdP response, or 0 for non-HTTP failures (e.g. timeout). */
    public int getHttpStatus() { return httpStatus; }

    /** The {@code error} field from the IdP JSON error response, or null if not available. */
    public String getIdpError() { return idpError; }
}
