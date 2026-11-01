package com.sahaj.platform.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.support.TestTenantSecurity;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class JdbcAuditLogTest {

    private Connection connection;
    private PreparedStatement statement;
    private JdbcAuditLog auditLog;

    @BeforeEach
    void setUp() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class, RETURNS_DEEP_STUBS);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        when(statement.getConnection().getMetaData().getDriverName()).thenReturn("PostgreSQL JDBC Driver");
        auditLog = new JdbcAuditLog(JdbcClient.create(new JdbcTemplate(dataSource)), new ObjectMapper());
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void writesWhoWhatAndTheMaskedValuesButNeverATenantId() throws SQLException {
        UUID user = UUID.randomUUID();
        UUID entity = UUID.randomUUID();

        TestTenantSecurity.runAs(TestTenantSecurity.authentication(UUID.randomUUID(), user),
                () -> auditLog.record("OWNER_PHONE_CHANGED", "TENANT", entity,
                        Map.of("mobile", "9123457410"), Map.of("mobile", "9000000001", "name", "Sunrise")));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).contains("platform.audit_event").doesNotContain("tenant_id");

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(statement, org.mockito.Mockito.times(6)).setString(anyInt(), text.capture());
        assertThat(text.getAllValues().subList(0, 4))
                .containsExactly(user.toString(), "OWNER_PHONE_CHANGED", "TENANT", entity.toString());
        assertThat(text.getAllValues().get(4)).contains("••••••7410").doesNotContain("9123457410");
        // "name" is a bare business-name field (e.g. a clinic's own name), not a person's name, so it
        // stays visible (CLAUDE.md restricts patient names, mobiles, ABHA numbers and clinical notes -
        // a clinic's own name is none of those); only the mobile is masked here.
        assertThat(text.getAllValues().get(5)).contains("••••••0001").contains("Sunrise")
                .doesNotContain("9000000001");
    }

    @Test
    void acceptsMissingBeforeAndAfterValues() throws SQLException {
        TestTenantSecurity.runAs(TestTenantSecurity.authentication(UUID.randomUUID(), UUID.randomUUID()),
                () -> auditLog.record("CLINIC_CLOSED", "TENANT", null, null, null));

        verify(statement).executeUpdate();
    }

    @Test
    void refusesToRunWithoutALoggedInClinicUser() throws SQLException {
        assertThatThrownBy(() -> auditLog.record("X", "TENANT", null, null, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TestTenantSecurity.runAs(TestTenantSecurity.signedInWithoutTenant(),
                () -> auditLog.record("X", "TENANT", null, null, null)))
                .isInstanceOf(IllegalStateException.class);

        verify(connection, never()).prepareStatement(anyString());
    }

    @Test
    void mustJoinTheCallersTransaction() throws NoSuchMethodException {
        Method record = JdbcAuditLog.class.getMethod("record", String.class, String.class, UUID.class, Map.class, Map.class);

        Transactional transactional = record.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(AuditLog.class).isAssignableFrom(JdbcAuditLog.class);
    }
}
