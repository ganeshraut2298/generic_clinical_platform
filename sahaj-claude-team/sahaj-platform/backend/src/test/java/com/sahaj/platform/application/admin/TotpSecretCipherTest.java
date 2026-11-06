package com.sahaj.platform.application.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TotpSecretCipherTest {

    private static byte[] key(int fill) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) fill);
        return key;
    }

    private final TotpSecretCipher cipher = new TotpSecretCipher(key(7), new SecureRandom());
    private final byte[] secret = "12345678901234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private final UUID admin = UUID.randomUUID();

    @Test
    void roundTripsAndFitsTheDatabaseCheck() {
        byte[] stored = cipher.encrypt(secret, admin, 1);
        // 12 nonce + 20 secret + 16 tag: platform_admin.totp_secret_enc allows 48 to 64 bytes.
        assertThat(stored).hasSize(48);
        assertThat(cipher.decrypt(stored, admin, 1)).isEqualTo(secret);
    }

    @Test
    void theStoredBytesDoNotContainThePlainSecret() {
        byte[] stored = cipher.encrypt(secret, admin, 1);
        String asText = new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(asText).doesNotContain("1234567890");
    }

    @Test
    void everyEncryptionUsesAFreshNonce() {
        byte[] first = cipher.encrypt(secret, admin, 1);
        byte[] second = cipher.encrypt(secret, admin, 1);
        assertThat(Arrays.copyOfRange(first, 0, 12)).isNotEqualTo(Arrays.copyOfRange(second, 0, 12));
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void aCiphertextDoesNotDecryptOnAnotherAdminsRow() {
        byte[] stored = cipher.encrypt(secret, admin, 1);
        UUID other = UUID.randomUUID();
        assertThatThrownBy(() -> cipher.decrypt(stored, other, 1))
                .isInstanceOf(TotpSecretCipher.TotpSecretUnreadableException.class);
    }

    @Test
    void theKeyVersionIsBoundToo() {
        byte[] stored = cipher.encrypt(secret, admin, 1);
        assertThatThrownBy(() -> cipher.decrypt(stored, admin, 2))
                .isInstanceOf(TotpSecretCipher.TotpSecretUnreadableException.class);
    }

    @Test
    void aTamperedCiphertextIsRefused() {
        byte[] stored = cipher.encrypt(secret, admin, 1);
        stored[20] ^= 0x01;
        assertThatThrownBy(() -> cipher.decrypt(stored, admin, 1))
                .isInstanceOf(TotpSecretCipher.TotpSecretUnreadableException.class);
    }

    @Test
    void anotherKeyCannotRead() {
        byte[] stored = cipher.encrypt(secret, admin, 1);
        TotpSecretCipher other = new TotpSecretCipher(key(9), new SecureRandom());
        assertThatThrownBy(() -> other.decrypt(stored, admin, 1))
                .isInstanceOf(TotpSecretCipher.TotpSecretUnreadableException.class);
    }

    @Test
    void tooShortOrMissingDataIsUnreadable() {
        assertThatThrownBy(() -> cipher.decrypt(new byte[10], admin, 1))
                .isInstanceOf(TotpSecretCipher.TotpSecretUnreadableException.class);
        assertThatThrownBy(() -> cipher.decrypt(null, admin, 1))
                .isInstanceOf(TotpSecretCipher.TotpSecretUnreadableException.class);
    }

    @Test
    void theKeyMustBeThirtyTwoBytes() {
        assertThatThrownBy(() -> new TotpSecretCipher(new byte[16], new SecureRandom()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failureMessagesCarryNoSecret() {
        assertThatThrownBy(() -> cipher.decrypt(new byte[60], admin, 1))
                .hasMessageNotContaining("12345678901234567890");
    }
}
