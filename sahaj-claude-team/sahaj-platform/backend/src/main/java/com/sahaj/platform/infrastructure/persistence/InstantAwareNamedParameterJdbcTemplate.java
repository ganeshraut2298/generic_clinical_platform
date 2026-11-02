package com.sahaj.platform.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.PreparedStatementCreatorFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * The one {@code NamedParameterJdbcTemplate} behind every {@code JdbcClient} in the application.
 *
 * <p>The PostgreSQL driver cannot infer an SQL type for {@link Instant} ("Can't infer the SQL type to
 * use for an instance of java.time.Instant") but accepts {@link OffsetDateTime}. Every named parameter
 * source is wrapped here so an {@code Instant} is sent as an {@code OffsetDateTime} at UTC, and no
 * repository has to convert by hand. Reading with {@code rs.getTimestamp} is unchanged.
 */
public class InstantAwareNamedParameterJdbcTemplate extends NamedParameterJdbcTemplate {

    public InstantAwareNamedParameterJdbcTemplate(JdbcOperations classicJdbcTemplate) {
        super(classicJdbcTemplate);
    }

    /** The value the driver can bind: an Instant becomes an OffsetDateTime at UTC, anything else is unchanged. */
    public static Object toDriverValue(Object value) {
        if (value instanceof Instant instant) {
            return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
        }
        return value;
    }

    @Override
    protected PreparedStatementCreator getPreparedStatementCreator(String sql, SqlParameterSource paramSource) {
        return super.getPreparedStatementCreator(sql, wrap(paramSource));
    }

    @Override
    protected PreparedStatementCreator getPreparedStatementCreator(
            String sql, SqlParameterSource paramSource, Consumer<PreparedStatementCreatorFactory> customizer) {
        return super.getPreparedStatementCreator(sql, wrap(paramSource), customizer);
    }

    private static SqlParameterSource wrap(SqlParameterSource source) {
        return source instanceof InstantConvertingSource ? source : new InstantConvertingSource(source);
    }

    private static final class InstantConvertingSource implements SqlParameterSource {
        private final SqlParameterSource delegate;

        InstantConvertingSource(SqlParameterSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean hasValue(String paramName) {
            return delegate.hasValue(paramName);
        }

        @Override
        public Object getValue(String paramName) throws IllegalArgumentException {
            return toDriverValue(delegate.getValue(paramName));
        }

        @Override
        public int getSqlType(String paramName) {
            return delegate.getSqlType(paramName);
        }

        @Override
        public String getTypeName(String paramName) {
            return delegate.getTypeName(paramName);
        }

        @Override
        public String[] getParameterNames() {
            return delegate.getParameterNames();
        }
    }
}
