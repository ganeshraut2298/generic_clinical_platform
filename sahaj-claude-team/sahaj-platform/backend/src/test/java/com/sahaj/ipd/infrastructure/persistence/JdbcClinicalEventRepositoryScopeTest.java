package com.sahaj.ipd.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.ClinicalEventRepository.Position;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/**
 * SAH-34 (F-2 part 1, design note 1.7): which statement the scoped-nurse page sends. With no ward and no live grant the
 * only branch of the scope test that can be true is her own entries, so the statement carries {@code e.actor = :nurse}
 * alone and binds neither {@code wards} nor {@code grants}. Otherwise the statement is unchanged. The rows are proved
 * identical against the real database by the QA tests.
 */
class JdbcClinicalEventRepositoryScopeTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID NURSE = UUID.randomUUID();
    private static final UUID WARD = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();

    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final StatementSpec spec = mock(StatementSpec.class, RETURNS_SELF);

    private String sentBy(Set<UUID> wards, Set<UUID> grants, Position after) {
        when(jdbc.sql(anyString())).thenReturn(spec);
        try {
            new JdbcClinicalEventRepository(jdbc).findPageInScope(TENANT, NURSE, wards, grants, after, 15);
        } catch (RuntimeException e) {
            // the row mapping is not under test; the mock returns nothing after the statement
        }
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        return sql.getValue();
    }

    @Test
    void withNoWardAndNoGrantTheStatementIsTheActorConditionAloneAndBindsNeitherArray() {
        String sql = sentBy(Set.of(), Set.of(), null);

        assertThat(sql).isEqualTo(JdbcClinicalEventRepository.PAGE_HEAD + JdbcClinicalEventRepository.ACTOR_ONLY
                + JdbcClinicalEventRepository.PAGE_TAIL);
        assertThat(sql).contains("and e.actor = :nurse").doesNotContain("any(").doesNotContain("a.status")
                .doesNotContain(":wards").doesNotContain(":grants").doesNotContain("(e.at, e.seq) <");
        verify(spec, never()).param(eq("wards"), any());
        verify(spec, never()).param(eq("grants"), any());
        verify(spec).param("tenantId", TENANT);
        verify(spec).param("nurse", NURSE);
        verify(spec).param("limit", 15);
    }

    @Test
    void aLaterPageKeepsTheKeysetAndBindsTheCursorButStillNoArray() {
        Instant at = Instant.parse("2026-11-10T05:00:00Z");

        String sql = sentBy(Set.of(), Set.of(), new Position(at, 42L));

        assertThat(sql).isEqualTo(JdbcClinicalEventRepository.PAGE_HEAD + JdbcClinicalEventRepository.ACTOR_ONLY
                + JdbcClinicalEventRepository.AFTER + JdbcClinicalEventRepository.PAGE_TAIL);
        verify(spec).param(eq("afterAt"), any());
        verify(spec).param("afterSeq", 42L);
        verify(spec, never()).param(eq("wards"), any());
        verify(spec, never()).param(eq("grants"), any());
    }

    @Test
    void aWardAloneOrAGrantAloneKeepsTheScopedStatementAndBindsBothArrays() {
        for (Set<UUID>[] sets : new Set[][] {{Set.of(WARD), Set.of()}, {Set.of(), Set.of(ADMISSION)},
            {Set.of(WARD), Set.of(ADMISSION)}}) {
            JdbcClient client = mock(JdbcClient.class);
            StatementSpec statement = mock(StatementSpec.class, RETURNS_SELF);
            when(client.sql(anyString())).thenReturn(statement);
            try {
                new JdbcClinicalEventRepository(client).findPageInScope(TENANT, NURSE, sets[0], sets[1], null, 15);
            } catch (RuntimeException e) {
                // the row mapping is not under test
            }
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(client).sql(sql.capture());

            assertThat(sql.getValue()).isEqualTo(JdbcClinicalEventRepository.PAGE_HEAD
                    + JdbcClinicalEventRepository.SCOPED_NURSE + JdbcClinicalEventRepository.PAGE_TAIL);
            verify(statement).param(eq("wards"), any());
            verify(statement).param(eq("grants"), any());
        }
    }

    @Test
    void theSameDisplayColumnsOrderAndLimitAreKeptOnBothPaths() {
        for (boolean actorOnly : new boolean[] {true, false}) {
            String sql = JdbcClinicalEventRepository.pageInScopeSql(actorOnly, true);

            assertThat(sql).startsWith(JdbcClinicalEventRepository.PAGE_HEAD)
                    .endsWith(" order by e.at desc, e.seq desc limit :limit")
                    .contains("g.user_id as grant_user_id").contains("(e.at, e.seq) < (:afterAt, :afterSeq)");
        }
        assertThat(JdbcClinicalEventRepository.ACTOR_ONLY).isEqualTo(" and e.actor = :nurse\n");
    }
}
