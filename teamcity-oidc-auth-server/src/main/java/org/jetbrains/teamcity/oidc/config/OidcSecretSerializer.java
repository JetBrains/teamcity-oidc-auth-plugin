package org.jetbrains.teamcity.oidc.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import jetbrains.buildServer.serverSide.crypt.Encryption;

import java.io.IOException;

public class OidcSecretSerializer extends JsonSerializer<String> {

    public static final String ENCRYPTION_CTX_KEY = "oidc.encryptionService";

    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null || value.isEmpty()) {
            gen.writeString(value);
            return;
        }

        Object encryptionService = serializers.getAttribute(ENCRYPTION_CTX_KEY);
        if (!(encryptionService instanceof Encryption)) {
            throw new IOException("Encryption service is not configured for OIDC secret serialization");
        }

        gen.writeString(((Encryption) encryptionService).encrypt(value));
    }
}
