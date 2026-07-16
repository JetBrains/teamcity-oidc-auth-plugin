package org.jetbrains.teamcity.oidc.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import jetbrains.buildServer.serverSide.crypt.Encryption;

import java.io.IOException;

public class OidcSecretDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String storedValue = p.getValueAsString();
        if (storedValue == null || storedValue.isEmpty()) {
            return storedValue;
        }

        Object encryptionService = ctxt.getAttribute(OidcSecretSerializer.ENCRYPTION_CTX_KEY);
        if (!(encryptionService instanceof Encryption)) {
            throw new IOException("Encryption service is not configured for OIDC secret deserialization");
        }

        Encryption encryption = (Encryption) encryptionService;
        return encryption.decrypt(storedValue);
    }
}
