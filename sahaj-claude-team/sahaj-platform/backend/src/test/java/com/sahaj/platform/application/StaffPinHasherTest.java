package com.sahaj.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-31, ADR-010 D2 and D3: HMAC with the pepper then BCrypt, fail-closed when the pepper is missing, refuse to start
 * when it is present but invalid. Tests generate their own random pepper; no fixed value is committed. BCrypt strength 4
 * is used for speed (strength 12 only differs in cost); one test proves the production strength is 12.
 */
class StaffPinHasherTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final String JWT_SECRET = "a-jwt-secret-that-is-at-least-32-characters-long";

    private static String randomPepper(int bytes) {
        byte[] value = new byte[bytes];
        new SecureRandom().nextBytes(value);
        return Base64.getEncoder().encodeToString(value);
    }

    private static StaffPinHasher hasher() {
        return StaffPinHasher.create(randomPepper(32), JWT_SECRET, 4);
    }

    @Test
    void aRightPinMatchesAndAWrongOneDoesNot() {
        StaffPinHasher hasher = hasher();
        String hash = hasher.hash(TENANT, MEMBER, "4821");

        assertThat(hasher.matches(TENANT, MEMBER, "4821", hash)).isTrue();
        assertThat(hasher.matches(TENANT, MEMBER, "4822", hash)).isFalse();
        assertThat(hasher.matches(TENANT, MEMBER, null, hash)).isFalse();
        assertThat(hasher.matches(TENANT, MEMBER, "", hash)).isFalse();
        assertThat(hasher.matches(TENANT, MEMBER, "48211", hash)).isFalse();
        assertThat(hasher.matches(TENANT, MEMBER, "4821", null)).isFalse();
    }

    @Test
    void theStoredValueIsABcryptHashThatTheDatabaseCheckAccepts() {
        String hash = hasher().hash(TENANT, MEMBER, "4821");

        assertThat(hash).matches("^\\$2[aby]\\$[0-9]{2}\\$[./A-Za-z0-9]{53}$").doesNotContain("4821");
    }

    @Test
    void productionStrengthIsTwelve() {
        StaffPinHasher production = StaffPinHasher.create(randomPepper(32), JWT_SECRET);

        assertThat(production.hash(TENANT, MEMBER, "4821")).startsWith("$2a$12$");
    }

    @Test
    void theSamePinGetsADifferentHashEachTime() {
        StaffPinHasher hasher = hasher();
        assertThat(hasher.hash(TENANT, MEMBER, "4821")).isNotEqualTo(hasher.hash(TENANT, MEMBER, "4821"));
    }

    @Test
    void aHashCopiedToAnotherMembershipOrClinicNoLongerMatches() {
        StaffPinHasher hasher = hasher();
        String hash = hasher.hash(TENANT, MEMBER, "4821");

        assertThat(hasher.matches(TENANT, UUID.randomUUID(), "4821", hash)).as("another person").isFalse();
        assertThat(hasher.matches(UUID.randomUUID(), MEMBER, "4821", hash)).as("another clinic").isFalse();
    }

    @Test
    void aDifferentPepperNeverMatches() {
        String hash = hasher().hash(TENANT, MEMBER, "4821");

        assertThat(hasher().matches(TENANT, MEMBER, "4821", hash)).isFalse();
    }

    @Test
    void aMissingOrBlankPepperLeavesTheHasherUnavailableAndStartsFine() {
        for (String missing : new String[] {null, "", "   "}) {
            StaffPinHasher off = StaffPinHasher.create(missing, JWT_SECRET, 4);
            assertThat(off.isAvailable()).isFalse();
            assertThatThrownBy(() -> off.hash(TENANT, MEMBER, "4821")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> off.matches(TENANT, MEMBER, "4821", "$2a$12$" + "a".repeat(53)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void aValidPepperMakesTheHasherAvailableEvenWithSurroundingWhitespace() {
        assertThat(StaffPinHasher.create(" " + randomPepper(48) + "\n", JWT_SECRET, 4).isAvailable()).isTrue();
        assertThat(StaffPinHasher.create(randomPepper(32), null, 4).isAvailable()).isTrue();
    }

    @Test
    void anInvalidPepperRefusesToStartAndTheMessageNeverEchoesTheValue() {
        String notBase64 = "this is not base64 !!! " + UUID.randomUUID();
        String tooShort = randomPepper(31);
        for (String bad : new String[] {notBase64, tooShort}) {
            assertThatThrownBy(() -> StaffPinHasher.create(bad, JWT_SECRET, 4))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SAHAJ_PIN_PEPPER")
                    .message().doesNotContain(bad);
        }
    }

    @Test
    void aPepperEqualToTheJwtSecretRefusesToStart() {
        String sameText = randomPepper(40);
        assertThatThrownBy(() -> StaffPinHasher.create(sameText, sameText, 4))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SAHAJ_JWT_SECRET")
                .message().doesNotContain(sameText);
        String asBase64OfSecret = Base64.getEncoder().encodeToString(
                "a-jwt-secret-that-is-at-least-32-characters-long".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> StaffPinHasher.create(asBase64OfSecret, JWT_SECRET, 4))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void exactly32BytesIsEnough() {
        assertThat(StaffPinHasher.create(randomPepper(32), JWT_SECRET, 4).isAvailable()).isTrue();
    }

    @Test
    void toStringShowsNothingSecret() {
        String pepper = randomPepper(32);
        StaffPinHasher hasher = StaffPinHasher.create(pepper, JWT_SECRET, 4);

        assertThat(hasher.toString()).doesNotContain(pepper).isEqualTo("StaffPinHasher[available=true]");
    }
}
