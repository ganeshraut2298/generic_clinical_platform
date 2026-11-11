package com.sahaj.ipd.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** SAH-29: the Packages list statement (no database): active rows of one clinic, oldest first, read only. */
class JdbcPackageRepositoryTest {

    @Test
    void theListIsTheClinicsActivePackagesInTheOrderTheyWereCreated() {
        String sql = JdbcPackageRepository.FIND_ACTIVE;

        assertThat(sql).contains("from ipd.package")
                .contains("where tenant_id = :tenantId and active")
                .endsWith("order by created_at, id\n");
    }

    @Test
    void theStatementOnlyReads() {
        assertThat(JdbcPackageRepository.FIND_ACTIVE.strip()).startsWith("select")
                .doesNotContain("insert").doesNotContain("update").doesNotContain("delete");
    }
}
