package com.contextflow.credential.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EnvelopeCryptoService {
    private static final String KEK_VERSION = "v1";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Environment environment;
    private final SecureRandom random = new SecureRandom();

    public EncryptedSecret encrypt(UUID userId, UUID credentialId, String apiKey)
            throws GeneralSecurityException {
        byte[] kek = readKek(KEK_VERSION);
        byte[] dek = new byte[KEY_BYTES];
        byte[] plaintext = apiKey.getBytes(UTF_8);
        try {
            random.nextBytes(dek);
            return new EncryptedSecret(
                    seal(plaintext, dek, aad(userId, credentialId, "secret")),
                    seal(dek, kek, aad(userId, credentialId, "dek")),
                    KEK_VERSION);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(dek, (byte) 0);
            Arrays.fill(kek, (byte) 0);
        }
    }

    /** Returns the decrypted bytes to a caller that has checked ownership and lifecycle. */
    public byte[] decrypt(
            UUID userId,
            UUID credentialId,
            String kekVersion,
            byte[] encryptedSecret,
            byte[] wrappedDek)
            throws GeneralSecurityException {
        byte[] kek = readKek(kekVersion);
        try {
            byte[] dek = open(wrappedDek, kek, aad(userId, credentialId, "dek"));
            try {
                if (dek.length != KEY_BYTES) {
                    throw new GeneralSecurityException("Invalid data encryption key length.");
                }
                return open(encryptedSecret, dek, aad(userId, credentialId, "secret"));
            } finally {
                Arrays.fill(dek, (byte) 0);
            }
        } finally {
            Arrays.fill(kek, (byte) 0);
        }
    }

    private byte[] readKek(String version) throws GeneralSecurityException {
        if (!KEK_VERSION.equals(version)) {
            throw new GeneralSecurityException("Unsupported encryption key version.");
        }
        byte[] kek;
        try {
            kek = Base64.getDecoder().decode(environment.getProperty("CF_CREDENTIAL_KEK_V1", ""));
        } catch (IllegalArgumentException ignored) {
            throw new GeneralSecurityException("Invalid encryption key configuration.");
        }
        if (kek.length != KEY_BYTES) {
            Arrays.fill(kek, (byte) 0);
            throw new GeneralSecurityException("Invalid encryption key length.");
        }
        return kek;
    }

    private byte[] seal(byte[] plaintext, byte[] key, byte[] aad) throws GeneralSecurityException {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce, aad);
        byte[] ciphertext = cipher.doFinal(plaintext);
        return ByteBuffer.allocate(NONCE_BYTES + ciphertext.length)
                .put(nonce)
                .put(ciphertext)
                .array();
    }

    private static byte[] open(byte[] blob, byte[] key, byte[] aad)
            throws GeneralSecurityException {
        if (blob == null || blob.length < NONCE_BYTES + TAG_BITS / Byte.SIZE) {
            throw new GeneralSecurityException("Invalid encrypted payload.");
        }
        byte[] nonce = Arrays.copyOf(blob, NONCE_BYTES);
        return cipher(Cipher.DECRYPT_MODE, key, nonce, aad)
                .doFinal(blob, NONCE_BYTES, blob.length - NONCE_BYTES);
    }

    private static Cipher cipher(int mode, byte[] key, byte[] nonce, byte[] aad)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(aad);
        return cipher;
    }

    private static byte[] aad(UUID userId, UUID credentialId, String purpose) {
        return ("contextflow:user-credential:" + userId + ":" + credentialId + ":" + purpose)
                .getBytes(UTF_8);
    }

    @Getter
    @AllArgsConstructor
    public static class EncryptedSecret {
        private final byte[] encryptedSecret;
        private final byte[] wrappedDek;
        private final String kekVersion;
    }
}
