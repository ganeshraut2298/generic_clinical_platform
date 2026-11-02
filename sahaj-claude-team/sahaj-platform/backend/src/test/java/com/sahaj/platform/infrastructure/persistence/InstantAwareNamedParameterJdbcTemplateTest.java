package com.sahaj.platform.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** Guards the pgjdbc rule: an Instant parameter must reach the driver as an OffsetDateTime. */
class InstantAwareNamedParameterJdbcTemplateTest {

    private static final Instant WHEN = Instant.parse("2026-11-01T10:15:30Z");

    private final InstantAwareNamedParameterJdbcTemplate template =
            new InstantAwareNamedParameterJdbcTemplate(mock(JdbcTemplate.class));

    @Test
    void convertsInstantToOffsetDateTimeAtUtc() {
        Object converted = InstantAwareNamedParameterJdbcTemplate.toDriverValue(WHEN);

        assertThat(converted).isEqualTo(OffsetDateTime.of(2026, 11, 1, 10, 15, 30, 0, ZoneOffset.UTC));
    }

    @Test
    void leavesOtherValuesAndNullsUnchanged() {
        UUID id = UUID.randomUUID();

        assertThat(InstantAwareNamedParameterJdbcTemplate.toDriverValue(id)).isSameAs(id);
        assertThat(InstantAwareNamedParameterJdbcTemplate.toDriverValue("text")).isEqualTo("text");
        assertThat(InstantAwareNamedParameterJdbcTemplate.toDriverValue(null)).isNull();
    }

    @Test
    void bindsInstantParameterAsOffsetDateTimeOnTheStatement() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(connection.prepareStatement(anyString(), anyInt())).thenReturn(statement);
        when(connection.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(statement);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", 7)
                .addValue("expiresAt", WHEN);
        PreparedStatementCreator creator = template.getPreparedStatementCreator(
                "update platform.otp_challenge set expires_at = :expiresAt where id = :id", params);
        creator.createPreparedStatement(connection);

        OffsetDateTime expected = OffsetDateTime.of(2026, 11, 1, 10, 15, 30, 0, ZoneOffset.UTC);
        verify(statement).setObject(1, expected);
        verify(statement).setObject(2, 7);
    }
}
