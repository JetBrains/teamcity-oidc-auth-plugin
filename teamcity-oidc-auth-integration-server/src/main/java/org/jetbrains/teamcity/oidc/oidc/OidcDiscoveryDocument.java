package org.jetbrains.teamcity.oidc.oidc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Deserialized from the OIDC well-known discovery endpoint. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OidcDiscoveryDocument {

    @JsonProperty("issuer")
    private String issuer;

    @JsonProperty("authorization_endpoint")
    private String authorizationEndpoint;

    @JsonProperty("token_endpoint")
    private String tokenEndpoint;

    @JsonProperty("userinfo_endpoint")
    private String userInfoEndpoint;

    @JsonProperty("jwks_uri")
    private String jwksUri;

    @JsonProperty("scopes_supported")
    private List<String> scopesSupported;

    @JsonProperty("response_types_supported")
    private List<String> responseTypesSupported;

    @JsonProperty("claims_supported")
    private List<String> claimsSupported;

    public String getIssuer() { return issuer; }
    public String getAuthorizationEndpoint() { return authorizationEndpoint; }
    public String getTokenEndpoint() { return tokenEndpoint; }
    public String getUserInfoEndpoint() { return userInfoEndpoint; }
    public String getJwksUri() { return jwksUri; }
    public List<String> getScopesSupported() { return scopesSupported; }
    public List<String> getResponseTypesSupported() { return responseTypesSupported; }
    public List<String> getClaimsSupported() { return claimsSupported; }
}
