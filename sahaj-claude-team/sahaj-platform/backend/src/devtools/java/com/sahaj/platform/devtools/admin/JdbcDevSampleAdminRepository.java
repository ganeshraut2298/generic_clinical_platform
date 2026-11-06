package com.sahaj.platform.devtools.admin;

import com.sahaj.platform.infrastructure.admin.IpLiterals;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Calls the dev-tools-only function with bound parameters (ADR-008 D5). Runs inside the caller's
 * transaction ({@link DevSampleAdminService}). A unique violation (SQLSTATE 23505) becomes
 * {@link DevSampleAdminUnavailableException}. Every other failure becomes a {@link DevSampleAdminFailedException}
 * with no message and no cause, because the driver's message and cause chain can hold the failing row (the email, the
 * hash, the ciphertext); only the SQLSTATE is kept (ADR-008 R4). Nothing here logs.
 */
final class JdbcDevSampleAdminRepository implements DevSampleAdminRepository {

    static final String UNIQUE_VIOLATION = "23505";

    private final JdbcClient jdbc;

    JdbcDevSampleAdminRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean createSampleAdmin(UUID adminId, String passwordHash, byte[] totpSecretEnc, int keyVersion,
                                     String remoteAddress, String requestId) {
        try {
            return Boolean.TRUE.equals(jdbc.sql("""
                            select platform.admin_dev_create_sample_admin(:id, :hash, :enc,
                                   cast(:version as smallint), cast(:ip as inet), cast(:req as text))
                            """)
                    .param("id", adminId).param("hash", passwordHash).param("enc", totpSecretEnc)
                    .param("version", keyVersion).param("ip", IpLiterals.forDatabase(remoteAddress))
                    .param("req", requestId)
                    .query(Boolean.class)
                    .single());
        } catch (RuntimeException ex) {
            String sqlState = sqlStateOf(ex);
            if (UNIQUE_VIOLATION.equals(sqlState)) {
                throw new DevSampleAdminUnavailableException();
            }
            throw new DevSampleAdminFailedException(sqlState);
        }
    }

    /** The SQLSTATE found in the cause chain, or null. */
    static String sqlStateOf(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof SQLException sql) {
                return sql.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }
}
