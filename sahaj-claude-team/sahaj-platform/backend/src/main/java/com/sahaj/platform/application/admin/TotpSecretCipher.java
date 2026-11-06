package com.sahaj.platform.application.admin;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM protection of the authenticator secret (ADR-003 D2.3). The stored value is
 * {@code 12-byte nonce || ciphertext || 16-byte tag}; a fresh nonce is drawn for every encryption.
 * The associated data names the admin and the key version, so a ciphertext copied onto another
 * admin's row does not decrypt. The key is 32 bytes from the environment and never reaches the
 * database. Failure messages never carry a secret or a ciphertext.
 */
public final class TotpSecretCipher {

    public static final int CURRENT_KEY_VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random;

    public TotpSecretCipher(byte[] key, SecureRandom random) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("The authenticator key must be exactly 32 bytes");
        }
        this.key = new SecretKeySpec(key, "AES");
        this.random = random;
    }

    public byte[] encrypt(byte[] secret, UUID adminId, int keyVersion) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(adminId, keyVersion));
            byte[] sealed = cipher.doFinal(secret);
            return ByteBuffer.allocate(nonce.length + sealed.length).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not protect the authenticator secret");
        }
    }

    public byte[] decrypt(byte[] stored, UUID adminId, int keyVersion) {
        if (stored == null || stored.length <= NONCE_BYTES + TAG_BITS / 8) {
            throw new TotpSecretUnreadableException();
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 0, NONCE_BYTES));
            cipher.updateAAD(aad(adminId, keyVersion));
            return cipher.doFinal(stored, NONCE_BYTES, stored.length - NONCE_BYTES);
        } catch (GeneralSecurityException ex) {
            throw new TotpSecretUnreadableException();
        }
    }

    private static byte[] aad(UUID adminId, int keyVersion) {
        return ("sahaj-admin-totp:" + adminId + ":" + keyVersion).getBytes(StandardCharsets.UTF_8);
    }

    /** The stored secret does not decrypt: wrong key, wrong row or damaged data. Carries no detail. */
    public static final class TotpSecretUnreadableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public TotpSecretUnreadableException() {
            super("The authenticator secret could not be read");
        }
    }
}
