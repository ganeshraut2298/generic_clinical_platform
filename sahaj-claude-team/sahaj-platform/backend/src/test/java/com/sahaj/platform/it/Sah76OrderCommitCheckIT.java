package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-76 deferred head trigger {@code trg_medicine_order_check_current_version} (docs/database/SAH-76.md section 2): the check runs
 * at commit, so every write order the application may use must pass and every incomplete write must be refused at commit and leave
 * nothing behind. Each case runs as the application role with a bound clinic and COMMITS (a rolled-back run would never fire a
 * deferred trigger). Rows are made through the real API first where a committed order is needed.
 */
@OnTestDatabase
@WithPinPepper
class Sah76OrderCommitCheckIT extends Sah76Support {

    private static final String HEAD = "insert into ipd.medicine_order (admission_id, client_key, drug_key, frequency_key, ordered_by, "
            + "override_reason_key, warnings, status, priority) values (?::uuid, gen_random_uuid(), 'pcm', 'od', "
            + "?::uuid, null, '{}', ?, ?) returning id::text";
    private static final String V1 = "insert into ipd.medicine_order_version (order_id, version_no, kind, frequency_key, dose_amount, "
            + "dose_unit, route_key, start_at, priority, created_by, verification) values (?::uuid, 1, 'SCHEDULED', 'od', 1, 'TABLET', "
            + "'ORAL', now(), ?, ?::uuid, 'REQUIRED')";
    private static final String V2 = "insert into ipd.medicine_order_version (order_id, version_no, kind, frequency_key, dose_amount, "
            + "dose_unit, route_key, start_at, priority, change_reason, created_by, verification) values (?::uuid, 2, 'SCHEDULED', 'od', "
            + "2, 'TABLET', 'ORAL', now(), ?, 'Raise the dose', ?::uuid, 'REQUIRED')";
    private static final String VERIFY_VERSION = "update ipd.medicine_order_version set verified_by = ?::uuid where order_id = ?::uuid "
            + "and version_no = 1";
    private static final String ACTIVATE = "update ipd.medicine_order set status = 'ACTIVE', verified_by = ?::uuid, version = version + 1 "
            + "where id = ?::uuid";

    @FunctionalInterface
    private interface Steps {
        void run(Connection c) throws SQLException;
    }

    /** Runs the steps in one transaction as the application role and commits. Returns null on success or the refusal. */
    private SqlErr commit(Clinic clinic, Steps steps) throws SQLException {
        try (Connection c = db().app()) {
            c.setAutoCommit(false);
            try (PreparedStatement bind = c.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                bind.setString(1, clinic.tenantId().toString());
                bind.execute();
            }
            try {
                steps.run(c);
                c.commit();
                return null;
            } catch (SQLException e) {
                c.rollback();
                return new SqlErr(e.getSQLState(), e.getMessage());
            }
        }
    }

    private static String insertHead(Connection c, String admissionId, String status, String priority) throws SQLException {
        return DbAccess.text(c, HEAD, admissionId, UUID.randomUUID().toString(), status, priority);
    }

    private static void refused(SqlErr e, String fragment) {
        assertThat(e).as("the commit was refused").isNotNull();
        assertThat(e.state()).as(e.message()).isEqualTo("23514");
        assertThat(e.message()).containsIgnoringCase(fragment);
    }

    @Test
    void aNewOrderWithItsVersionOneCommits() throws Exception {
        Ward w = ward();
        String[] id = new String[1];
        SqlErr e = commit(w.clinic(), c -> {
            id[0] = insertHead(c, w.adm().id(), "PENDING", "ROUTINE");
            DbAccess.execute(c, V1, id[0], "ROUTINE", UUID.randomUUID().toString());
        });
        assertThat(e).isNull();
        assertThat(versions(w.clinic(), id[0])).isOne();
    }

    @Test
    void aHeadWithoutItsVersionIsRefusedAtCommitAndNothingStays() throws Exception {
        Ward w = ward();
        long before = orderCount(w.clinic(), w.adm().id());
        refused(commit(w.clinic(), c -> insertHead(c, w.adm().id(), "PENDING", "ROUTINE")), "version that does not exist");
        assertThat(orderCount(w.clinic(), w.adm().id())).isEqualTo(before);
    }

    @Test
    void aHeadWhosePriorityDiffersFromItsVersionIsRefusedAtCommit() throws Exception {
        Ward w = ward();
        long before = orderCount(w.clinic(), w.adm().id());
        refused(commit(w.clinic(), c -> {
            String id = insertHead(c, w.adm().id(), "PENDING", "URGENT");
            DbAccess.execute(c, V1, id, "ROUTINE", UUID.randomUUID().toString());
        }), "priority");
        assertThat(orderCount(w.clinic(), w.adm().id())).isEqualTo(before);
    }

    @Test
    void anActiveHeadAtInsertWithAnUnverifiedRequiredVersionIsRefusedAtCommit() throws Exception {
        Ward w = ward();
        refused(commit(w.clinic(), c -> {
            String id = insertHead(c, w.adm().id(), "ACTIVE", "ROUTINE");
            DbAccess.execute(c, V1, id, "ROUTINE", UUID.randomUUID().toString());
        }), "active medicine order needs a verified version");
    }

    @Test
    void verifyWritesCommitInEitherOrderAndAreRefusedWhenTheVersionIsNotVerified() throws Exception {
        Ward w = ward();
        String verifier = w.pharmacist().userId().toString();
        String first = placed(w, "pcm");
        String second = placed(w, "asp");
        String third = placed(w, "met");
        // version stamp first, then the head
        assertThat(commit(w.clinic(), c -> {
            DbAccess.execute(c, VERIFY_VERSION, verifier, first);
            DbAccess.execute(c, ACTIVATE, verifier, first);
        })).isNull();
        // head first, then the version stamp
        assertThat(commit(w.clinic(), c -> {
            DbAccess.execute(c, ACTIVATE, verifier, second);
            DbAccess.execute(c, VERIFY_VERSION, verifier, second);
        })).isNull();
        // head alone
        refused(commit(w.clinic(), c -> DbAccess.execute(c, ACTIVATE, verifier, third)), "active medicine order needs a verified version");
        assertThat(status(w.clinic(), first)).isEqualTo("ACTIVE");
        assertThat(status(w.clinic(), second)).isEqualTo("ACTIVE");
        assertThat(status(w.clinic(), third)).as("the refused verify left the order waiting").isEqualTo("PENDING");
    }

    @Test
    void aChangeWritesTheNewVersionBeforeTheHeadAndAHeadMovedWithoutItIsRefused() throws Exception {
        Ward w = ward();
        String id = placed(w, "pcm");
        String doctor = w.doctor().userId().toString();
        String moveHead = "update ipd.medicine_order set current_version = 2, version = version + 1 where id = ?::uuid";
        refused(commit(w.clinic(), c -> DbAccess.execute(c, moveHead, id)), "does not exist");
        assertThat(commit(w.clinic(), c -> {
            DbAccess.execute(c, V2, id, "ROUTINE", doctor);
            DbAccess.execute(c, "update ipd.medicine_order_version set superseded_at = now() where order_id = ?::uuid and version_no = 1", id);
            DbAccess.execute(c, moveHead, id);
        })).isNull();
        assertThat(versions(w.clinic(), id)).isEqualTo(2);
        assertThat(one(w.clinic(), "select current_version::text from ipd.medicine_order where id = ?::uuid", id)).isEqualTo("2");
        // the head cannot go first: the version guard wants number current_version + 1
        String id2 = placed(w, "asp");
        SqlErr headFirst = commit(w.clinic(), c -> {
            DbAccess.execute(c, "update ipd.medicine_order set current_version = 2, version = version + 1 where id = ?::uuid", id2);
            DbAccess.execute(c, V2, id2, "ROUTINE", doctor);
        });
        assertThat(headFirst).as("version after the head moved is refused").isNotNull();
        assertThat(versions(w.clinic(), id2)).isOne();
    }

    @Test
    void theApplicationRoleCannotDeleteOrRewriteAVersionRow() throws Exception {
        Ward w = ward();
        String id = placed(w, "pcm");
        SqlErr del = commit(w.clinic(), c -> DbAccess.execute(c, "delete from ipd.medicine_order_version where order_id = ?::uuid", id));
        assertThat(del).isNotNull();
        SqlErr edit = commit(w.clinic(), c -> DbAccess.execute(c,
                "update ipd.medicine_order_version set dose_amount = 9 where order_id = ?::uuid", id));
        assertThat(edit).isNotNull();
        assertThat(one(w.clinic(), "select dose_amount::text from ipd.medicine_order_version where order_id = ?::uuid", id))
                .isEqualTo("1.000");
    }
}
