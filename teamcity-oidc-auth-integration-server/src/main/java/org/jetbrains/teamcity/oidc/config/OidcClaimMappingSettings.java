package org.jetbrains.teamcity.oidc.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class OidcClaimMappingSettings {

    public enum MappingType {
        /** Use the `sub` (subject) claim. */
        SUB,
        /** Use a named claim specified by {@link #claimName}. */
        CLAIM,
        /** Do not map this attribute. */
        NONE
    }

    private MappingType mappingType = MappingType.SUB;
    private String claimName;

    public MappingType getMappingType() {
        return mappingType;
    }

    public void setMappingType(MappingType mappingType) {
        this.mappingType = mappingType;
    }

    public String getClaimName() {
        return claimName;
    }

    public void setClaimName(String claimName) {
        this.claimName = claimName;
    }
}
