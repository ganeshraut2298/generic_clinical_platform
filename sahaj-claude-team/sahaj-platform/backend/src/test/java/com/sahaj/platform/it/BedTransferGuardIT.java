package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-26 security review M1 (the database guard added by {@code V2026_11_19_1000__ipd_transfer_guards.sql}), on the real
 * {@code sahaj_test} database as {@code sahaj_app}: {@code ipd.admission.bed_id} changes only inside a recorded bed
 * transfer of the same transaction (SQLSTATE 42501 otherwise) and a bed holds at most one ADMITTED admission. Admit,
 * transfer and discharge through the API still work. Test plan: docs/test-plans/SAH-27.md (SAH-26 guard tests).
 */
@OnTestDatabase
class BedTransferGuardIT extends DischargeSupport {

    private static final String INSERT_TRANSFER = "insert into ipd.bed_transfer (admission_id, from_bed_id, to_bed_id, "
            + "from_category_key, to_category_key, segment_days, new_rate_from, reason, transferred_by) "
            + "values (?::uuid, ?, ?, 'gen', 'gen', 0, 'same', null, ?)";
    private static final String MOVE_BED = "update ipd.admission set bed_id = ? where id = ?::uuid";

    private record Step(String sql, Object... params) {
    }

    /** The SQL error of the statements run in one rolled-back tenant transaction, or null when all were accepted. */
    private SQLException failure(Clinic clinic, Step... steps) {
        try {
            db().inTenant(clinic.tenantId(), c -> {
                for (Step s : steps) {
                    DbAccess.execute(c, s.sql(), s.params());
                }
                return null;
            });
        } catch (SQLException e) {
            return e;
        }
        return null;
    }

    private void assertRefused(SQLException e) {
        assertError(e).as("the update was accepted").isNotNull();
        assertThat(e.getSQLState()).isEqualTo("42501");
        assertThat(e.getMessage()).contains("bed change refused");
    }

    private record World(Clinic clinic, String admission, UUID bed, UUID spare, UUID other) {
    }

    private World world() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, false, "same");
        String id = quickAdmit(clinic).get("id").asText();
        return new World(clinic, id, bedOf(clinic, id), newBed(clinic, "gen"), newBed(clinic, "gen"));
    }

    // ---------------- the trigger ----------------

    @Test
    void aDirectBedChangeWithNoTransferRowIsRefusedWith42501AndNothingChanges() throws Exception {
        World w = world();
        assertRefused(failure(w.clinic(), new Step(MOVE_BED, w.spare(), w.admission())));
        // a free bed, an occupied bed and a bed that does not exist: the same refusal or a key error, never a change
        assertRefused(failure(w.clinic(), new Step(MOVE_BED, w.other(), w.admission())));
        SQLException ghost = failure(w.clinic(), new Step(MOVE_BED, UUID.randomUUID(), w.admission()));
        assertError(ghost).isNotNull();
        assertThat(bedOf(w.clinic(), w.admission())).isEqualTo(w.bed());
        assertThat(transferRows(w.clinic())).isZero();
        // other columns of the same row are still updatable by the service (the guard fires on bed_id only)
        assertError(failure(w.clinic(), new Step("update ipd.admission set version = version + 1 where id = ?::uuid", w.admission())))
                .isNull();
    }

    @Test
    void aBedChangeOfADischargedAdmissionIsRefusedEvenWithAMatchingTransferRow() throws Exception {
        World w = world();
        signAndDischarge(w.clinic(), w.admission());
        assertRefused(failure(w.clinic(), new Step(MOVE_BED, w.spare(), w.admission())));
        assertRefused(failure(w.clinic(), new Step(INSERT_TRANSFER, w.admission(), w.bed(), w.spare(), w.clinic().ownerUserId()),
                new Step(MOVE_BED, w.spare(), w.admission())));
        assertThat(bedOf(w.clinic(), w.admission())).isEqualTo(w.bed());
        assertThat(transferRows(w.clinic())).isZero();
    }

    @Test
    void aTransferRowWithOtherBedsOrForAnotherAdmissionDoesNotAuthoriseTheChange() throws Exception {
        World w = world();
        String second = quickAdmit(w.clinic()).get("id").asText();
        UUID by = w.clinic().ownerUserId();
        // from is right, to is another bed
        assertRefused(failure(w.clinic(), new Step(INSERT_TRANSFER, w.admission(), w.bed(), w.other(), by),
                new Step(MOVE_BED, w.spare(), w.admission())));
        // to is right, from is another bed
        assertRefused(failure(w.clinic(), new Step(INSERT_TRANSFER, w.admission(), w.other(), w.spare(), by),
                new Step(MOVE_BED, w.spare(), w.admission())));
        // both reversed
        assertRefused(failure(w.clinic(), new Step(INSERT_TRANSFER, w.admission(), w.spare(), w.bed(), by),
                new Step(MOVE_BED, w.spare(), w.admission())));
        // a row that matches, but for another admission
        assertRefused(failure(w.clinic(), new Step(INSERT_TRANSFER, second, w.bed(), w.spare(), by),
                new Step(MOVE_BED, w.spare(), w.admission())));
        assertThat(bedOf(w.clinic(), w.admission())).isEqualTo(w.bed());
        assertThat(transferRows(w.clinic())).isZero();
    }

    @Test
    void aMatchingTransferRowInsertedInTheSameTransactionAllowsTheChange() throws Exception {
        World w = world();
        UUID by = w.clinic().ownerUserId();
        SQLException none = failure(w.clinic(), new Step(INSERT_TRANSFER, w.admission(), w.bed(), w.spare(), by),
                new Step(MOVE_BED, w.spare(), w.admission()),
                new Step("select 1/(case when (select bed_id from ipd.admission where id = ?::uuid) = ? then 1 else 0 end)",
                        w.admission(), w.spare()));
        assertError(none).as("the update with its transfer row is accepted").isNull();
        assertThat(bedOf(w.clinic(), w.admission())).as("rolled back by the test helper").isEqualTo(w.bed());
    }

    @Test
    void aTransferRowFromAnEarlierTransactionDoesNotAuthoriseALaterChange() throws Exception {
        World w = world();
        moveOk(w.clinic(), w.admission(), w.spare());                                   // row 1: bed -> spare
        ok(HttpMethod.POST, BEDS + "/" + w.bed() + "/ready", w.clinic().owner(), null, 200);
        moveOk(w.clinic(), w.admission(), w.bed());                                     // row 2: spare -> bed
        assertThat(transferRows(w.clinic())).isEqualTo(2);
        // the admission is in bed again; row 1 (bed -> spare) matches from and to, but it is not from this transaction
        assertRefused(failure(w.clinic(), new Step(MOVE_BED, w.spare(), w.admission())));
        assertThat(bedOf(w.clinic(), w.admission())).isEqualTo(w.bed());
    }

    // ---------------- one ADMITTED admission per bed ----------------

    @Test
    void aSecondAdmittedAdmissionOnTheSameBedIsRefusedByTheUniqueIndex() throws Exception {
        World w = world();
        signAndDischarge(w.clinic(), w.admission());
        ok(HttpMethod.POST, BEDS + "/" + w.bed() + "/ready", w.clinic().owner(), null, 200);
        String next = admitOk(w.clinic(), body(newPatient(w.clinic()), w.clinic().doctorMembershipId(), w.bed())).get("id").asText();
        assertThat(bedOf(w.clinic(), next)).isEqualTo(w.bed());
        // putting the discharged admission back to ADMITTED would make two ADMITTED rows in one bed
        SQLException e = failure(w.clinic(), new Step("update ipd.admission set status = 'ADMITTED' where id = ?::uuid", w.admission()));
        assertError(e).isNotNull();
        assertThat(e.getSQLState()).isEqualTo("23505");
        assertThat(e.getMessage()).contains("ux_admission_tenant_bed_admitted");
        assertThat(values(w.clinic(), "select status from ipd.admission where id = ?::uuid", w.admission())).containsExactly("DISCHARGED");
        // the API says the same in plain words
        problem(call(HttpMethod.POST, ADMISSIONS, w.clinic().owner(), js(body(newPatient(w.clinic()), w.clinic().doctorMembershipId(), w.bed()))),
                409);
    }

    @Test
    void theIndexAndTheTriggerAreInThePlaceTheMigrationPromisesAndTheAppRoleCannotCallTheFunction() throws Exception {
        String index = db().asMigrator(c -> DbAccess.text(c, "select indexdef from pg_indexes where schemaname = 'ipd' "
                + "and indexname = 'ux_admission_tenant_bed_admitted'"));
        assertThat(index).contains("UNIQUE").contains("(tenant_id, bed_id)").contains("ADMITTED");
        String trigger = db().asMigrator(c -> DbAccess.text(c, "select pg_get_triggerdef(oid) from pg_trigger "
                + "where tgname = 'trg_admission_guard_bed_change' and tgrelid = 'ipd.admission'::regclass"));
        assertThat(trigger).containsIgnoringCase("before update of bed_id").contains("guard_admission_bed_change");
        assertThat(ownerText("select has_function_privilege('sahaj_app', "
                + "'ipd.guard_admission_bed_change()', 'EXECUTE')::text")).isEqualTo("false");
        assertThat(ownerText("select coalesce(array_to_string(proconfig, ','), '') "
                + "from pg_proc where oid = 'ipd.guard_admission_bed_change()'::regprocedure")).contains("search_path");
    }

    // ---------------- the normal paths still work ----------------

    @Test
    void admitTransferAndDischargeStillWorkThroughTheApiWithTheGuardInPlace() throws Exception {
        World w = world();
        JsonNode moved = moveOk(w.clinic(), w.admission(), w.spare());
        assertThat(moved.get("status").asText()).isEqualTo("ADMITTED");
        assertThat(bedOf(w.clinic(), w.admission())).isEqualTo(w.spare());
        assertThat(bedStatus(w.clinic(), w.bed())).isEqualTo("clean");
        signNormal(w.clinic(), w.admission());
        JsonNode done = dischargeNow(w.clinic(), w.admission(), "normal", "general", "UPI");
        assertThat(done.get("status").asText()).isEqualTo("DISCHARGED");
        assertThat(bedStatus(w.clinic(), w.spare())).isEqualTo("clean");
        // a new patient takes the bed that was just cleaned
        ok(HttpMethod.POST, BEDS + "/" + w.spare() + "/ready", w.clinic().owner(), null, 200);
        admitOk(w.clinic(), body(newPatient(w.clinic()), w.clinic().doctorMembershipId(), w.spare()));
        assertThat(count(w.clinic(), "select count(*) from ipd.admission where bed_id = ? and status = 'ADMITTED'", w.spare())).isOne();
    }
}
