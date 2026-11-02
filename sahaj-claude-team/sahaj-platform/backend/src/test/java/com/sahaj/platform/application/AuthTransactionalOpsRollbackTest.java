package com.sahaj.platform.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mocks cannot show a rollback, so these guard the rule by annotation: writes made just before a
 * deliberate failure (revoking every session after a reused refresh token) must be kept.
 */
class AuthTransactionalOpsRollbackTest {

    @Test
    void reusedRefreshTokenKeepsItsRevocationWhenTheCallFails() throws NoSuchMethodException {
        Transactional transactional = AuthTransactionalOps.class
                .getMethod("rotateRefreshToken", UUID.class, String.class, String.class, Instant.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.noRollbackFor()).contains(RefreshInvalidException.class);
    }
}
