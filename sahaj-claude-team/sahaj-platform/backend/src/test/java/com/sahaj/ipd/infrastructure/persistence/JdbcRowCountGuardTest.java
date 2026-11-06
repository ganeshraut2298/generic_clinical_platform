package com.sahaj.ipd.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/**
 * Security review M1 (SAH-26): a bed change touches exactly one ADMITTED admission, and a bed status change exactly
 * one bed. Any other count is a server fault, not a user message. The statements are checked here; the database
 * guard itself is tested against the real database.
 */
class JdbcRowCountGuardTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ID = UUID.randomUUID();

    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final StatementSpec spec = mock(StatementSpec.class, RETURNS_SELF);

    private void updates(int count) {
        when(jdbc.sql(anyString())).thenReturn(spec);
        when(spec.update()).thenReturn(count);
    }

    private String sqlSent() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        return sql.getValue();
    }

    @Test
    void theAdmissionBedChangeOnlyTouchesAnAdmittedAdmissionAndNeedsExactlyOneRow() {
        updates(1);
        JdbcAdmissionRepository repository = new JdbcAdmissionRepository(jdbc);

        assertThatCode(() -> repository.updateBed(TENANT, ID, UUID.randomUUID())).doesNotThrowAnyException();

        assertThat(sqlSent()).contains("update ipd.admission").contains("status = 'ADMITTED'");
    }

    @Test
    void noRowChangedForTheAdmissionBedIsAServerFaultThatCarriesNoData() {
        updates(0);
        JdbcAdmissionRepository repository = new JdbcAdmissionRepository(jdbc);

        assertThatThrownBy(() -> repository.updateBed(TENANT, ID, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(ID.toString());
    }

    @Test
    void moreThanOneRowForTheAdmissionBedIsAServerFault() {
        updates(2);
        JdbcAdmissionRepository repository = new JdbcAdmissionRepository(jdbc);

        assertThatThrownBy(() -> repository.updateBed(TENANT, ID, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theBedStatusChangesNeedExactlyOneRow() {
        JdbcBedRepository repository = new JdbcBedRepository(jdbc);

        updates(1);
        assertThatCode(() -> repository.markOccupied(TENANT, ID)).doesNotThrowAnyException();
        assertThatCode(() -> repository.updateStatus(TENANT, ID, "clean")).doesNotThrowAnyException();

        updates(0);
        assertThatThrownBy(() -> repository.markOccupied(TENANT, ID)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> repository.updateStatus(TENANT, ID, "clean"))
                .isInstanceOf(IllegalStateException.class);

        updates(2);
        assertThatThrownBy(() -> repository.markOccupied(TENANT, ID)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> repository.updateStatus(TENANT, ID, "clean"))
                .isInstanceOf(IllegalStateException.class);
    }
}
