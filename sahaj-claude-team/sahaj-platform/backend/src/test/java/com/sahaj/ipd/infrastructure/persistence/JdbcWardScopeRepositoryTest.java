package com.sahaj.ipd.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * SAH-34 (docs/database/SAH-34.md section 7): the statements name only the columns the database role may write, judge a
 * grant by the database clock in SQL, and make the review a guarded update. No database: the statement text is checked.
 */
class JdbcWardScopeRepositoryTest {

    @Test
    void aGrantIsLiveOnlyByTheDatabaseClockInSqlAndOnlyOnAnAdmittedAdmission() {
        for (String sql : new String[] {JdbcWardScopeRepository.LIVE_GRANTS, JdbcWardScopeRepository.HAS_LIVE_GRANT}) {
            assertThat(sql).contains("g.expires_at > clock_timestamp()").contains("a.status = 'ADMITTED'")
                    .contains("g.membership_id = :membershipId").contains("g.role = :role");
        }
    }

    @Test
    void theGrantProbesCarryOneStableBoundAsAPreFilterNextToTheExactClockTest() {
        for (String sql : new String[] {JdbcWardScopeRepository.LIVE_GRANTS, JdbcWardScopeRepository.HAS_LIVE_GRANT}) {
            assertThat(sql).contains("g.expires_at > clock_timestamp()")
                    .contains("g.expires_at > now() - interval '1 minute'");
            assertThat(sql.split("now\\(\\)", -1)).hasSize(2);
            assertThat(sql.split("clock_timestamp\\(\\)", -1)).hasSize(2);
        }
    }

    @Test
    void theOwnerMarkIsOneStatementWithTheCountAndTheNewestUnreviewedGrant() {
        String sql = JdbcWardScopeRepository.UNREVIEWED_MARK;

        assertThat(sql).contains("count(*)").contains("reviewed_at is null")
                .contains("order by opened_at desc, id desc limit 1").contains("tenant_id = :tenantId");
    }

    @Test
    void theGrantInsertSendsNoTimeAndNoDurationOnlyTheColumnsTheRoleMayWrite() {
        String sql = JdbcWardScopeRepository.INSERT_GRANT;

        assertThat(sql).contains("insert into ipd.emergency_access_grant (admission_id, bed_id, user_id, membership_id, role, reason_key)")
                .contains("returning id, opened_at, expires_at")
                .doesNotContain("(id,").doesNotContain("tenant_id,");
        assertThat(sql.substring(0, sql.indexOf("values"))).doesNotContain("opened_at").doesNotContain("expires_at")
                .doesNotContain("reviewed");
    }

    @Test
    void theReviewIsOneGuardedUpdateThatNamesOnlyReviewedByAndVersion() {
        String sql = JdbcWardScopeRepository.MARK_REVIEWED;

        assertThat(sql).contains("set reviewed_by = :me, version = version + 1")
                .contains("where tenant_id = :tenantId and id = :id and reviewed_at is null")
                .contains("returning reviewed_at");
        assertThat(sql.substring(0, sql.indexOf("where"))).doesNotContain("reviewed_at =").doesNotContain("opened_at")
                .doesNotContain("expires_at");
    }

    @Test
    void anAssignmentIsEndedNotDeletedAndTheDatabaseStampsTheTime() {
        assertThat(JdbcWardScopeRepository.END_ASSIGNMENT).contains("set removed_by = :by, version = version + 1")
                .contains("removed_at is null").doesNotContain("removed_at =").doesNotContain("delete");
        assertThat(JdbcWardScopeRepository.INSERT_ASSIGNMENT)
                .contains("insert into ipd.ward_assignment (ward_id, membership_id, assigned_by)")
                .doesNotContain("assigned_at");
    }

    @Test
    void theScopeBuildReadsOnlyLiveAssignments() {
        assertThat(JdbcWardScopeRepository.LIVE_WARDS).contains("membership_id = :membershipId")
                .contains("removed_at is null");
        assertThat(JdbcWardScopeRepository.LIVE_ASSIGNMENTS).contains("removed_at is null");
    }

    @Test
    void theOwnerListIsOldestFirstWithAKeysetOnOpenedAtAndId() {
        assertThat(JdbcWardScopeRepository.UNREVIEWED_ORDER).contains("g.reviewed_at is null");
        assertThat(JdbcWardScopeRepository.AFTER).contains("(g.opened_at, g.id) > (:afterAt, :afterId)");
        assertThat(JdbcWardScopeRepository.UNREVIEWED_TAIL).isEqualTo(" order by g.opened_at, g.id limit :limit");
        assertThat(JdbcWardScopeRepository.COUNT_UNREVIEWED).contains("reviewed_at is null");
    }

    @Test
    void theSwitchIsWrittenOnTheConfigRowOnlyAndDefaultsToOffWhenThereIsNoRow() {
        assertThat(JdbcWardScopeRepository.UPSERT_SWITCH).contains("insert into ipd.config (tenant_id, nurse_ward_scope")
                .contains("on conflict (tenant_id) do update set").contains("nurse_ward_scope = excluded.nurse_ward_scope");
        assertThat(JdbcWardScopeRepository.FIND_SWITCH).contains("from ipd.config where tenant_id = :tenantId");
    }

    @Test
    void theAuditTrailReadsTheGranteeThroughTheGrantKeyAndTheScopedNurseFilterKeepsHerOwnEntries() {
        assertThat(JdbcClinicalEventRepository.PAGE_HEAD).contains("g.user_id as grant_user_id")
                .contains("left join ipd.emergency_access_grant g on g.tenant_id = e.tenant_id and g.id = e.grant_id");
        assertThat(JdbcClinicalEventRepository.SCOPED_NURSE).contains("e.actor = :nurse")
                .contains("a.status = 'ADMITTED'").contains("b.ward_id in (select unnest(cast(:wards as uuid[])))")
                .contains("a.id in (select unnest(cast(:grants as uuid[])))")
                .doesNotContain("= any(cast(");
        assertThat(JdbcClinicalEventRepository.INSERT_FOR_GRANT).contains("'bg'").contains("grant_id");
    }
}
