package com.contextflow.credential;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;

import com.contextflow.credential.crypto.EnvelopeCryptoService;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class EnvelopeCryptoServiceTest {
    private static final String API_KEY = "synthetic-credential";
    private final UUID ownerId = UUID.randomUUID();
    private final UUID credentialId = UUID.randomUUID();
    private final EnvelopeCryptoService crypto =
            new EnvelopeCryptoService(
                    new MockEnvironment()
                            .withProperty(
                                    "CF_CREDENTIAL_KEK_V1",
                                    Base64.getEncoder().encodeToString(new byte[32])));

    @Test
    void encryptsWithIndependentRandomizedBlobsAndRoundTrips() throws GeneralSecurityException {
        var encrypted = crypto.encrypt(ownerId, credentialId, API_KEY);
        var another = crypto.encrypt(ownerId, credentialId, API_KEY);

        assertThat(encrypted.getKekVersion()).isEqualTo("v1");
        assertThat(encrypted.getEncryptedSecret())
                .hasSize(12 + API_KEY.length() + 16)
                .isNotEqualTo(another.getEncryptedSecret());
        assertThat(encrypted.getWrappedDek())
                .hasSize(12 + 32 + 16)
                .isNotEqualTo(another.getWrappedDek());
        assertThat(
                        crypto.decrypt(
                                ownerId,
                                credentialId,
                                encrypted.getKekVersion(),
                                encrypted.getEncryptedSecret(),
                                encrypted.getWrappedDek()))
                .isEqualTo(API_KEY.getBytes(UTF_8));
    }

    @Test
    void rejectsTamperedBlobWrongOwnerAndUnsupportedVersion() throws GeneralSecurityException {
        var encrypted = crypto.encrypt(ownerId, credentialId, API_KEY);
        byte[] corruptedSecret = encrypted.getEncryptedSecret().clone();
        corruptedSecret[corruptedSecret.length - 1] ^= 1;
        byte[] corruptedDek = encrypted.getWrappedDek().clone();
        corruptedDek[0] ^= 1;

        assertThatThrownBy(() ->
                        crypto.decrypt(
                                ownerId,
                                credentialId,
                                "v1",
                                corruptedSecret,
                                encrypted.getWrappedDek()))
                .isInstanceOf(GeneralSecurityException.class);
        assertThatThrownBy(() ->
                        crypto.decrypt(
                                ownerId,
                                credentialId,
                                "v1",
                                encrypted.getEncryptedSecret(),
                                corruptedDek))
                .isInstanceOf(GeneralSecurityException.class);
        assertThatThrownBy(() ->
                        crypto.decrypt(
                                UUID.randomUUID(),
                                credentialId,
                                "v1",
                                encrypted.getEncryptedSecret(),
                                encrypted.getWrappedDek()))
                .isInstanceOf(GeneralSecurityException.class);
        assertThatThrownBy(() ->
                        crypto.decrypt(
                                ownerId,
                                credentialId,
                                "v2",
                                encrypted.getEncryptedSecret(),
                                encrypted.getWrappedDek()))
                .isInstanceOf(GeneralSecurityException.class);
        assertThatThrownBy(
                        () -> crypto.decrypt(ownerId, credentialId, "v1", new byte[0], new byte[0]))
                .isInstanceOf(GeneralSecurityException.class);
    }

    @Test
    void rejectsMissingOrInvalidKek() {
        MockEnvironment environment = new MockEnvironment();
        EnvelopeCryptoService unconfigured = new EnvelopeCryptoService(environment);
        assertThatThrownBy(() -> unconfigured.encrypt(ownerId, credentialId, API_KEY))
                .isInstanceOf(GeneralSecurityException.class);

        environment.setProperty("CF_CREDENTIAL_KEK_V1", "malformed%config");
        assertThatThrownBy(() -> unconfigured.encrypt(ownerId, credentialId, API_KEY))
                .isInstanceOf(GeneralSecurityException.class)
                .hasMessage("Invalid encryption key configuration.");
    }
}
