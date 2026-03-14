package org.jetbrains.teamcity.oidc.oidc;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.Map;

/** Deserialized from the userinfo endpoint. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OidcUserInfo {

    @JsonProperty("sub")
    private String sub;

    @JsonProperty("email")
    private String email;

    @JsonProperty("email_verified")
    private Boolean emailVerified;

    @JsonProperty("name")
    private String name;

    @JsonProperty("preferred_username")
    private String preferredUsername;

    @JsonProperty("given_name")
    private String givenName;

    @JsonProperty("family_name")
    private String familyName;

    /** All claims — populated by @JsonAnySetter for custom claim lookups. */
    private final Map<String, Object> raw = new HashMap<>();

    @JsonAnySetter
    public void setRawClaim(String key, Object value) {
        raw.put(key, value);
    }

    public String getSub() { return sub; }
    public String getEmail() { return email; }
    public Boolean getEmailVerified() { return emailVerified; }
    public String getName() { return name; }
    public String getPreferredUsername() { return preferredUsername; }
    public String getGivenName() { return givenName; }
    public String getFamilyName() { return familyName; }
    public Map<String, Object> getRaw() { return raw; }
}
