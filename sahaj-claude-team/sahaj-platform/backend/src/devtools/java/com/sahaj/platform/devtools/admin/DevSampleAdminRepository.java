package com.sahaj.platform.devtools.admin;

import java.util.UUID;

/** The one database call of the dev-only endpoint: {@code platform.admin_dev_create_sample_admin}. */
interface DevSampleAdminRepository {

    /**
     * @return true when the admin was created, false when an admin already exists (the refusal is audited and
     *         the caller must commit)
     * @throws DevSampleAdminUnavailableException on SQLSTATE 23505 (another admin was committed first)
     */
    boolean createSampleAdmin(UUID adminId, String passwordHash, byte[] totpSecretEnc, int keyVersion,
                              String remoteAddress, String requestId);
}
