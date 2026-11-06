package com.sahaj.platform.devtools.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.platform.application.admin.AdminPasswordPolicy;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DevPasswordGeneratorTest {

    private static final String CONTRACT_PATTERN = "^[A-HJ-NP-Za-km-z2-9]{24}$";

    @Test
    void theAlphabetHas57CharactersWithoutTheLookAlikes() {
        assertThat(DevPasswordGenerator.ALPHABET).hasSize(57).doesNotContain("I", "O", "l", "0", "1", "-");
        assertThat(DevPasswordGenerator.ALPHABET.chars().distinct().count()).isEqualTo(57);
    }

    @Test
    void passwordsAre24CharactersFromTheContractAlphabetAndPassThePolicy() {
        SecureRandom random = new SecureRandom();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String password = DevPasswordGenerator.generate(random);
            assertThat(password).matches(CONTRACT_PATTERN);
            assertThat(AdminPasswordPolicy.isAcceptable("sample-admin@example.test", password)).isTrue();
            seen.add(password);
        }
        assertThat(seen).hasSize(200);
    }

    @Test
    void everyCharacterOfTheAlphabetCanBeDrawn() {
        SecureRandom random = new SecureRandom();
        Set<Character> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            for (char c : DevPasswordGenerator.generate(random).toCharArray()) {
                seen.add(c);
            }
        }
        assertThat(seen).hasSize(57);
    }
}
