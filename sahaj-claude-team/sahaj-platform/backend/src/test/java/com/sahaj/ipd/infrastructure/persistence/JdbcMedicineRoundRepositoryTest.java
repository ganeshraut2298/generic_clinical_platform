package com.sahaj.ipd.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-35 the uuid[] literal of query 2 ({@code admission_id = any(cast(:admissionIds as uuid[]))}). The statements run
 * against PostgreSQL in the integration tests of the qa-engineer; this proves the literal is only canonical UUID text.
 */
class JdbcMedicineRoundRepositoryTest {

    @Test
    void theLiteralIsABraceListOfCanonicalUuidsSeparatedByCommas() {
        UUID a = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID b = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Set<UUID> ids = new LinkedHashSet<>(List.of(a, b));

        assertThat(JdbcMedicineRoundRepository.arrayLiteral(ids))
                .isEqualTo("{11111111-1111-1111-1111-111111111111,22222222-2222-2222-2222-222222222222}");
    }

    @Test
    void oneIdIsOneElementAndNoIdsIsTheEmptyArray() {
        UUID a = UUID.randomUUID();

        assertThat(JdbcMedicineRoundRepository.arrayLiteral(List.of(a))).isEqualTo("{" + a + "}");
        assertThat(JdbcMedicineRoundRepository.arrayLiteral(List.of())).isEqualTo("{}");
    }

    @Test
    void aHundredAndFiftyIdsStayOneLiteralOfOnlyHexDigitsDashesCommasAndBraces() {
        Set<UUID> ids = new LinkedHashSet<>();
        for (int i = 0; i < 150; i++) {
            ids.add(UUID.randomUUID());
        }

        String literal = JdbcMedicineRoundRepository.arrayLiteral(ids);

        assertThat(literal).matches("^\\{[0-9a-f,-]+}$");
        assertThat(literal.split(",")).hasSize(150);
    }
}
