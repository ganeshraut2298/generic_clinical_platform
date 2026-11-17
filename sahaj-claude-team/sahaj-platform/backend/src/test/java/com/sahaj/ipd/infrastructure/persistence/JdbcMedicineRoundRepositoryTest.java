package com.sahaj.ipd.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

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

    // ---- SAH-76: the statements follow the new slot rule ----

    private String sqlOf(java.util.function.Consumer<JdbcMedicineRoundRepository> call) {
        JdbcClient jdbc = mock(JdbcClient.class);
        StatementSpec spec = mock(StatementSpec.class, RETURNS_SELF);
        when(jdbc.sql(anyString())).thenReturn(spec);
        try {
            call.accept(new JdbcMedicineRoundRepository(jdbc));
        } catch (RuntimeException e) {
            // the row mapping is not under test
        }
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        return sql.getValue().replaceAll("\s+", " ");
    }

    @Test
    void theOrdersReadHoldsActiveOrdersAndPendingStatOrdersOfAdmittedPatientsOnly() {
        String sql = sqlOf(r -> r.findActiveOfAdmitted(UUID.randomUUID()));

        assertThat(sql).contains("a.status = 'ADMITTED'")
                .contains("(o.status = 'ACTIVE' or (o.status = 'PENDING' and o.priority = 'STAT'))")
                .contains("o.status").doesNotContain("frequency_key").doesNotContain("verified_at");
    }

    @Test
    void theDosesReadLeavesOutUndoneAndInErrorDoses() {
        String sql = sqlOf(r -> r.findDosesBetween(UUID.randomUUID(), List.of(UUID.randomUUID()),
                java.time.Instant.EPOCH, java.time.Instant.EPOCH.plusSeconds(60)));

        assertThat(sql).contains("not exists (select 1 from ipd.dose_correction c where c.tenant_id = d.tenant_id "
                + "and c.dose_id = d.id)").contains("d.slot_at >= :from and d.slot_at < :to");
    }
}
