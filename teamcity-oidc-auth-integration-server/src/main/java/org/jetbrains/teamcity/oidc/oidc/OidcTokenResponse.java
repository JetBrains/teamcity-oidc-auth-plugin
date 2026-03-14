package org.jetbrains.teamcity.oidc.oidc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Deserialized from the token endpoint response. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OidcTokenResponse {

    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("id_token")
    private String idToken;

    @JsonProperty("token_type")
    private String tokenType;

    @JsonProperty("expires_in")
    private int expiresIn;

    @JsonProperty("refresh_token")
    private String refreshToken;

    @JsonProperty("scope")
    private String scope;

    public String getAccessToken() { return accessToken; }
    public String getIdToken() { return idToken; }
    public String getTokenType() { return tokenType; }
    public int getExpiresIn() { return expiresIn; }
    public String getRefreshToken() { return refreshToken; }
    public String getScope() { return scope; }
}
