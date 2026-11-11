package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-34 database rules of the two new tables on the real {@code sahaj_test} database: {@code ipd.emergency_access_grant} (the
 * application cannot write a time; the trigger stamps 30 minutes; the only change is one review, never by the grantee; delete
 * and truncate are refused for every role; row level security and the composite keys keep another clinic out) and
 * {@code ipd.ward_assignment} (an assignment can only be ended, once; no delete; one live row per nurse and ward), plus the
 * {@code grant_id} column of {@code ipd.clinical_event}. The statements are sent as {@code sahaj_app} with the clinic bound, or
 * as the table owner for the rules that bind it too. Test plan: docs/test-plans/SAH-34.md.
 */
@OnTestDatabase
@WithPinPepper
class EmergencyAccessTableIT extends ScopeSupport {

    private static final String INSERT_APP = "insert into ipd.emergency_access_grant (admission_id, bed_id, user_id, membership_id, "
            + "role, reason_key) values ('%s', '%s', '%s', '%s', '%s', '%s')";

    private String insertSql(Hosp h, String role, String reason) throws Exception {
        return String.format(INSERT_APP, h.inB().id(), bedOf(h.clinic(), h.inB().id()), h.nurseA().userId(), h.nurseA().membershipId(),
                role, reason);
    }

    /** Runs the statements as sahaj_app in the clinic; every one but the last must succeed; returns the error of the last. */
    private SqlErr lastFails(Clinic clinic, String... statements) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            for (int i = 0; i < statements.length - 1; i++) {
                DbAccess.execute(c, statements[i]);
            }
            Savepoint sp = c.setSavepoint();
            try {
                DbAccess.execute(c, statements[statements.length - 1]);
            } catch (SQLException e) {
                c.rollback(sp);
                return new SqlErr(e.getSQLState(), e.getMessage());
            }
            throw new AssertionError("accepted: " + statements[statements.length - 1]);
        });
    }

    private String firstGrant(Clinic clinic) throws SQLException {
        return values(clinic, "select id::text from ipd.emergency_access_grant order by opened_at limit 1").get(0);
    }

    // ---------------- grant: what the application may and may not write ----------------

    @Test
    void theApplicationInsertStampsTheClockAndThirtyMinutesAndAColumnItMayNotWriteIsRefused() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        String insert = insertSql(h, "NURSE", "DETERIORATING");
        db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, insert);
            assertThat(DbAccess.count(c, "select count(*) from ipd.emergency_access_grant where expires_at - opened_at = interval '30 minutes' "
                    + "and opened_at > clock_timestamp() - interval '1 minute' and tenant_id = platform.current_tenant()")).isOne();
            return null;
        });
        for (String column : new String[] {"opened_at", "expires_at", "reviewed_at", "id", "tenant_id"}) {
            String value = column.endsWith("_at") ? "now()" : "gen_random_uuid()";
            SqlErr e = appErr(clinic, "insert into ipd.emergency_access_grant (admission_id, bed_id, user_id, membership_id, role, reason_key, "
                    + column + ") values ('" + h.inB().id() + "', '" + bedOf(clinic, h.inB().id()) + "', '" + h.nurseA().userId() + "', '"
                    + h.nurseA().membershipId() + "', 'NURSE', 'DETERIORATING', " + value + ")");
            assertThat(e.state()).as("insert of " + column + ": " + e.message()).isEqualTo("42501");
        }
        assertThat(grantRows(clinic)).isZero();
    }

    @Test
    void aRowCannotBeCreatedAlreadyReviewedWithAnUnknownRoleOrAnUnknownReasonOrForAnotherClinic() throws Exception {
        Hosp h = hosp();
        Hosp other = hosp();
        Clinic clinic = h.clinic();
        String doctorRole = insertSql(h, "DOCTOR", "DETERIORATING");
        assertThat(appErr(clinic, doctorRole).state()).isEqualTo("23514");
        assertThat(appErr(clinic, insertSql(h, "NURSE", "BORED")).state()).isEqualTo("23514");
        // a ward and admission of another clinic: the composite key to the clinic's own admission refuses it
        String crossAdmission = String.format(INSERT_APP, other.inB().id(), bedOf(other.clinic(), other.inB().id()), h.nurseA().userId(),
                h.nurseA().membershipId(), "NURSE", "DETERIORATING");
        SqlErr cross = appErr(clinic, crossAdmission);
        assertThat(cross.state()).as(cross.message()).isIn("23503", "42501");
        // row level security: a row for another tenant is refused outright
        SqlErr rls = appErr(clinic, "insert into ipd.emergency_access_grant (tenant_id, admission_id, bed_id, user_id, membership_id, role, "
                + "reason_key) values ('" + other.clinic().tenantId() + "', '" + other.inB().id() + "', '" + bedOf(other.clinic(), other.inB().id())
                + "', '" + h.nurseA().userId() + "', '" + h.nurseA().membershipId() + "', 'NURSE', 'DETERIORATING')");
        assertThat(rls.state()).as(rls.message()).isEqualTo("42501");
        assertThat(grantRows(clinic)).isZero();
        assertThat(grantRows(other.clinic())).isZero();
    }

    @Test
    void theExpiryRuleHoldsForTheOwnerToo() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        String bed = bedOf(clinic, h.inB().id()).toString();
        // with the stamping trigger off, a row whose end is not exactly thirty minutes after its start is refused by the check
        SqlErr e = asOwner(false, GRANT_TABLES, c -> {
            DbAccess.execute(c, "alter table ipd.emergency_access_grant disable trigger trg_emergency_access_grant_stamp");
            Savepoint sp = c.setSavepoint();
            try {
                DbAccess.execute(c, "insert into ipd.emergency_access_grant (tenant_id, admission_id, bed_id, user_id, membership_id, role, "
                        + "reason_key, opened_at, expires_at) values ('" + clinic.tenantId() + "', '" + h.inB().id() + "', '" + bed + "', '"
                        + h.nurseA().userId() + "', '" + h.nurseA().membershipId() + "', 'NURSE', 'DETERIORATING', now(), now() + interval '4 hours')");
            } catch (SQLException ex) {
                c.rollback(sp);
                return new SqlErr(ex.getSQLState(), ex.getMessage());
            }
            throw new AssertionError("a four hour grant was accepted");
        });
        assertThat(e.state()).isEqualTo("23514");
        assertThat(e.message()).contains("ck_emergency_access_grant_expiry");
        // with the trigger on, whatever times are sent are overwritten
        asOwner(false, GRANT_TABLES, c -> {
            DbAccess.execute(c, "insert into ipd.emergency_access_grant (tenant_id, admission_id, bed_id, user_id, membership_id, role, "
                    + "reason_key, opened_at, expires_at) values ('" + clinic.tenantId() + "', '" + h.inB().id() + "', '" + bed + "', '"
                    + h.nurseA().userId() + "', '" + h.nurseA().membershipId() + "', 'NURSE', 'DETERIORATING', now() - interval '1 year', "
                    + "now() + interval '1 year')");
            assertThat(DbAccess.count(c, "select count(*) from ipd.emergency_access_grant where expires_at - opened_at = interval '30 minutes' "
                    + "and opened_at > clock_timestamp() - interval '1 minute' and tenant_id = '" + clinic.tenantId() + "'")).isOne();
            return null;
        });
    }

    // ---------------- grant: update, review, delete ----------------

    @Test
    void anUpdateOfTheExpiryOrAnyOtherFixedColumnIsRefusedForTheApplicationAndForTheOwner() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        String id = openGrant(h.nurseA(), h.inB().id(), "DETERIORATING").get("id").asText();
        for (String column : new String[] {"expires_at = expires_at + interval '4 hours'", "opened_at = opened_at - interval '1 hour'",
            "reviewed_at = now()", "reason_key = 'COVERING_BREAK'", "role = 'VISITING_DOCTOR'", "admission_id = gen_random_uuid()",
            "membership_id = gen_random_uuid()", "user_id = gen_random_uuid()", "tenant_id = gen_random_uuid()"}) {
            SqlErr e = appErr(clinic, "update ipd.emergency_access_grant set " + column + " where id = '" + id + "'");
            assertThat(e.state()).as("app: " + column + " " + e.message()).isEqualTo("42501");
        }
        // the owner (no row level security, but the trigger binds it): only the review can change
        for (String column : new String[] {"expires_at = expires_at + interval '4 hours'", "reason_key = 'COVERING_BREAK'",
            "opened_at = opened_at - interval '1 hour'", "role = 'VISITING_DOCTOR'", "reviewed_at = now()"}) {
            SqlErr e = ownerErr("ipd.emergency_access_grant", "update ipd.emergency_access_grant set " + column + " where id = '" + id + "'");
            assertThat(e.state()).as("owner: " + column + " " + e.message()).isEqualTo("23514");
            assertThat(e.message()).contains("emergency access grant");
        }
        assertThat(values(clinic, "select (expires_at - opened_at = interval '30 minutes')::text, reason_key, (reviewed_at is null)::text "
                + "from ipd.emergency_access_grant where id = ?::uuid", id)).containsExactly("true", "DETERIORATING", "true");
    }

    @Test
    void theReviewCanBeMadeOnceNeverBySelfAndTheDatabaseStampsTheTime() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        UUID owner = clinic.ownerUserId();
        String id = openGrant(h.nurseA(), h.inB().id(), "DETERIORATING").get("id").asText();
        // self review
        SqlErr self = appErr(clinic, "update ipd.emergency_access_grant set reviewed_by = user_id, version = version + 1 where id = '" + id + "'");
        assertThat(self.state()).isEqualTo("23514");
        assertThat(self.message()).contains("cannot review it");
        // a review that names nobody
        SqlErr nobody = appErr(clinic, "update ipd.emergency_access_grant set version = version + 1 where id = '" + id + "'");
        assertThat(nobody.state()).isEqualTo("23514");
        // a first review: the time is the database's, whatever the application sends
        db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, "update ipd.emergency_access_grant set reviewed_by = '" + owner + "', version = version + 1 where id = '" + id + "'");
            assertThat(DbAccess.count(c, "select count(*) from ipd.emergency_access_grant where id = '" + id + "' and reviewed_at between "
                    + "clock_timestamp() - interval '1 minute' and clock_timestamp() and reviewed_by = '" + owner + "'")).isOne();
            return null;
        });
        // a second review, by the same or another person, in the same transaction as the first
        SqlErr second = lastFails(clinic, "update ipd.emergency_access_grant set reviewed_by = '" + owner + "', version = version + 1 where id = '" + id + "'",
                "update ipd.emergency_access_grant set reviewed_by = '" + UUID.randomUUID() + "', version = version + 1 where id = '" + id + "'");
        assertThat(second.state()).isEqualTo("23514");
        assertThat(second.message()).contains("already reviewed");
        // and the owner of the tables cannot undo or redo a committed review
        String reviewed = openGrant(h.nurseB(), h.inA().id(), "COVERING_BREAK").get("id").asText();
        ok(org.springframework.http.HttpMethod.POST, EA + "/" + reviewed + "/review", clinic.owner(), null, 200);
        SqlErr redo = ownerErr("ipd.emergency_access_grant", "update ipd.emergency_access_grant set reviewed_by = '" + UUID.randomUUID()
                + "' where id = '" + reviewed + "'");
        assertThat(redo.state()).isEqualTo("23514");
        SqlErr undo = ownerErr("ipd.emergency_access_grant", "update ipd.emergency_access_grant set reviewed_by = null where id = '" + reviewed + "'");
        assertThat(undo.state()).isEqualTo("23514");
        assertThat(values(clinic, "select reviewed_by::text from ipd.emergency_access_grant where id = ?::uuid", reviewed)).containsExactly(owner.toString());
    }

    @Test
    void aGrantCanNeverBeDeletedOrTruncatedByAnyRole() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        String id = openGrant(h.nurseA(), h.inB().id(), "DETERIORATING").get("id").asText();
        assertThat(appErr(clinic, "delete from ipd.emergency_access_grant where id = '" + id + "'").state()).isEqualTo("42501");
        assertThat(appErr(clinic, "truncate ipd.emergency_access_grant").state()).isEqualTo("42501");
        assertThat(ownerErr("ipd.emergency_access_grant", "delete from ipd.emergency_access_grant where id = '" + id + "'").state()).isEqualTo("42501");
        assertThat(ownerErr("ipd.emergency_access_grant", "truncate ipd.emergency_access_grant").state()).as("0A000: another table (clinical_event) refers to it; the trigger is the second line").isIn("42501", "0A000");
        assertThat(grantRows(clinic)).isOne();
    }

    @Test
    void anotherClinicsGrantsAreInvisibleAndCannotBeChangedOrReviewed() throws Exception {
        Hosp h = hosp();
        Hosp other = hosp();
        String theirs = openGrant(other.nurseA(), other.inB().id(), "DETERIORATING").get("id").asText();
        assertThat(count(h.clinic(), "select count(*) from ipd.emergency_access_grant")).isZero();
        assertThat(count(h.clinic(), "select count(*) from ipd.emergency_access_grant where id = ?::uuid", theirs)).isZero();
        // an update from this clinic matches no row (row level security), so nothing is reviewed
        db().inTenant(h.clinic().tenantId(), c -> {
            try (var ps = c.prepareStatement("update ipd.emergency_access_grant set reviewed_by = ?::uuid where id = ?::uuid")) {
                ps.setString(1, h.clinic().ownerUserId().toString());
                ps.setString(2, theirs);
                assertThat(ps.executeUpdate()).isZero();
            }
            return null;
        });
        assertThat(count(other.clinic(), "select count(*) from ipd.emergency_access_grant where reviewed_at is null")).isOne();
        // no tenant bound at all: nothing is visible and nothing can be written
        SqlErr noTenant = appErrNoTenant("insert into ipd.emergency_access_grant (admission_id, bed_id, user_id, membership_id, role, reason_key) "
                + "values (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'NURSE', 'DETERIORATING')");
        assertThat(noTenant.state()).isNotNull();
    }

    // ---------------- the bg entry's grant_id ----------------

    @Test
    void theGrantIdOfAnEntryMustBelongToABgEntryOfTheSameClinic() throws Exception {
        Hosp h = hosp();
        Hosp other = hosp();
        Clinic clinic = h.clinic();
        String mine = openGrant(h.nurseA(), h.inB().id(), "DETERIORATING").get("id").asText();
        String theirs = openGrant(other.nurseA(), other.inB().id(), "DETERIORATING").get("id").asText();
        String insert = "insert into ipd.clinical_event (admission_id, kind, text, actor, grant_id) values ('" + h.inB().id() + "', '%s', 'x', '"
                + h.nurseA().userId() + "', '%s')";
        SqlErr wrongKind = appErr(clinic, String.format(insert, "info", mine));
        assertThat(wrongKind.state()).isEqualTo("23514");
        assertThat(wrongKind.message()).contains("ck_clinical_event_grant");
        SqlErr foreign = appErr(clinic, String.format(insert, "bg", theirs));
        assertThat(foreign.state()).as(foreign.message()).isEqualTo("23503");
        assertThat(foreign.message()).contains("fk_clinical_event_grant");
        // a bg entry with no grant is still allowed (SAH-33 compatibility), and with her own grant too
        appOk(clinic, "insert into ipd.clinical_event (admission_id, kind, text, actor) values ('" + h.inB().id() + "', 'bg', 'x', '"
                + h.nurseA().userId() + "')");
        appOk(clinic, String.format(insert, "bg", mine));
    }

    // ---------------- ward assignment ----------------

    @Test
    void anAssignmentCanOnlyBeEndedOnceNeverChangedNeverDeletedAndOneLiveRowPerNurseAndWard() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        String row = values(clinic, "select id::text from ipd.ward_assignment where membership_id = ?::uuid", h.nurseA().membershipId().toString()).get(0);
        // the application cannot move it, re-date it or give it to another nurse
        for (String set : new String[] {"ward_id = gen_random_uuid()", "membership_id = gen_random_uuid()", "assigned_at = now()",
            "assigned_by = gen_random_uuid()", "tenant_id = gen_random_uuid()"}) {
            SqlErr e = appErr(clinic, "update ipd.ward_assignment set " + set + " where id = '" + row + "'");
            assertThat(e.state()).as(set + " " + e.message()).isEqualTo("42501");
        }
        for (String set : new String[] {"ward_id = ward_id", "membership_id = gen_random_uuid()", "assigned_by = gen_random_uuid()"}) {
            SqlErr e = ownerErr("ipd.ward_assignment", "update ipd.ward_assignment set " + set + " where id = '" + row + "'");
            assertThat(e.state()).as("owner " + set + " " + e.message()).isEqualTo("23514");
            assertThat(e.message()).contains("ward assignment");
        }
        // an update must name who ended it
        assertThat(appErr(clinic, "update ipd.ward_assignment set version = version + 1 where id = '" + row + "'").state()).isEqualTo("23514");
        // an end stamps the database clock whatever the application sends
        db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, "update ipd.ward_assignment set removed_by = '" + clinic.ownerUserId() + "', removed_at = now() - interval '1 year', "
                    + "version = version + 1 where id = '" + row + "'");
            assertThat(DbAccess.count(c, "select count(*) from ipd.ward_assignment where id = '" + row + "' and removed_at > clock_timestamp() - interval '1 minute'")).isOne();
            return null;
        });
        // ended once: an ended row never changes again
        commitAsTenant(clinic.tenantId(), "update ipd.ward_assignment set removed_by = '" + clinic.ownerUserId() + "', version = version + 1 where id = '" + row + "'");
        SqlErr again = appErr(clinic, "update ipd.ward_assignment set removed_by = '" + UUID.randomUUID() + "', version = version + 1 where id = '" + row + "'");
        assertThat(again.state()).isEqualTo("23514");
        assertThat(ownerErr("ipd.ward_assignment", "update ipd.ward_assignment set removed_by = null where id = '" + row + "'").state()).isEqualTo("23514");
        // delete and truncate: refused for every role
        assertThat(appErr(clinic, "delete from ipd.ward_assignment where id = '" + row + "'").state()).isEqualTo("42501");
        assertThat(ownerErr("ipd.ward_assignment", "delete from ipd.ward_assignment where id = '" + row + "'").state()).isEqualTo("42501");
        assertThat(ownerErr("ipd.ward_assignment", "truncate ipd.ward_assignment").state()).isIn("42501", "0A000");
        assertThat(appErr(clinic, "truncate ipd.ward_assignment").state()).isEqualTo("42501");
        // one live row per nurse and ward
        String dup = "insert into ipd.ward_assignment (ward_id, membership_id, assigned_by) values ('" + h.wardB() + "', '"
                + h.nurseB().membershipId() + "', '" + clinic.ownerUserId() + "')";
        SqlErr duplicate = appErr(clinic, dup);
        assertThat(duplicate.state()).isEqualTo("23505");
        assertThat(duplicate.message()).contains("ux_ward_assignment_live");
        // the ended row is history: the same nurse and ward may be assigned again
        appOk(clinic, "insert into ipd.ward_assignment (ward_id, membership_id, assigned_by) values ('" + h.wardA() + "', '"
                + h.nurseA().membershipId() + "', '" + clinic.ownerUserId() + "')");
        // a ward of another clinic: the composite key refuses it
        Hosp other = hosp();
        SqlErr foreignWard = appErr(clinic, "insert into ipd.ward_assignment (ward_id, membership_id, assigned_by) values ('" + other.wardA() + "', '"
                + h.nurseA().membershipId() + "', '" + clinic.ownerUserId() + "')");
        assertThat(foreignWard.state()).isEqualTo("23503");
    }
}
