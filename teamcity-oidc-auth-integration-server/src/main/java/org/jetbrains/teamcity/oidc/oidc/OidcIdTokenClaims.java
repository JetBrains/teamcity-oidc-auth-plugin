package org.jetbrains.teamcity.oidc.oidc;

import java.util.List;
import java.util.Map;

/**
 * Validated and decoded payload of the ID token JWT.
 * Populated by {@link OidcIdTokenValidator} using nimbus-jose-jwt.
 * {@code aud} is always a List — nimbus normalises both JSON string and array forms.
 */
public class OidcIdTokenClaims {

    private final String iss;
    private final String sub;
    private final List<String> aud;
    private final long exp;
    private final long iat;
    private final String nonce;
    private final String email;
    private final Boolean emailVerified;
    private final String name;
    private final String preferredUsername;
    private final Map<String, Object> raw;

    public OidcIdTokenClaims(
            String iss, String sub, List<String> aud,
            long exp, long iat, String nonce,
            String email, Boolean emailVerified,
            String name, String preferredUsername,
            Map<String, Object> raw) {
        this.iss = iss;
        this.sub = sub;
        this.aud = aud;
        this.exp = exp;
        this.iat = iat;
        this.nonce = nonce;
        this.email = email;
        this.emailVerified = emailVerified;
        this.name = name;
        this.preferredUsername = preferredUsername;
        this.raw = raw;
    }

    public String getIss() { return iss; }
    public String getSub() { return sub; }
    public List<String> getAud() { return aud; }
    public long getExp() { return exp; }
    public long getIat() { return iat; }
    public String getNonce() { return nonce; }
    public String getEmail() { return email; }
    public Boolean getEmailVerified() { return emailVerified; }
    public String getName() { return name; }
    public String getPreferredUsername() { return preferredUsername; }
    /** All raw claims from the JWT payload — use for custom claim mappings. */
    public Map<String, Object> getRaw() { return raw; }
}
