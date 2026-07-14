package org.jetbrains.teamcity.oidc.oidc;

import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import org.jetbrains.annotations.NotNull;

/**
 * Verifies a {@link SignedJWT} signature using a nimbus {@link JWK}.
 * Supports RSA and EC keys.
 */
public final class JwtVerifier {

    private JwtVerifier() {}

    public static boolean verify(@NotNull SignedJWT jwt, @NotNull JWK jwk) throws Exception {
        JWSVerifier verifier;
        if (jwk instanceof RSAKey) {
            verifier = new RSASSAVerifier((RSAKey) jwk);
        } else if (jwk instanceof ECKey) {
            verifier = new ECDSAVerifier((ECKey) jwk);
        } else {
            throw new Exception("Unsupported JWK type: " + jwk.getKeyType());
        }
        return jwt.verify(verifier);
    }
}
