package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.support.LogCapture;
import java.sql.Connection;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-31 ADR-009 D4, D5, D10: a refusal by the database (the unique index, a check, a foreign key, the guard trigger) is a
 * code fault. It is never swallowed and never shown: the caller gets a generic 500 problem, the whole transaction rolls
 * back (no order, no clinical event, no audit row, no record-access row, no PIN count lost or gained), and nothing in the
 * answer or the logs names a constraint, a table, an SQL state or a patient. The failures are forced by a trigger, made
 * and dropped by the table owner for one clinic only, that raises the chosen SQL state (23505, 23514, 23503) on an insert
 * or on a transition. Test plan: docs/test-plans/SAH-31.md.
 */
@OnTestDatabase
@WithPinPepper
class MedicineDbErrorsIT extends MedicineSupport {

    private static final String NONE_KNOWN = "None known";
    private static final String[][] ERRORS = {{"23505", "ux_medicine_order_tenant_admission_drug_live"},
            {"23514", "ck_medicine_order_status"}, {"23503", "fk_medicine_order_drug"}};

    /** Runs the action while a trigger raises {@code errcode} on every matching insert or update of this clinic's orders. */
    private void forced(Clinic clinic, String operation, String when, String errcode, String constraint, Action action)
            throws Exception {
        String suffix = clinic.tenantId().toString().replace('-', '_');
        String function = "ipd.it_fail_med_" + suffix;
        String trigger = "trg_it_fail_med_" + suffix;
        try (Connection c = db().migrator()) {
            c.setAutoCommit(true);
            DbAccess.execute(c, "create or replace function " + function + "() returns trigger language plpgsql as $$ begin "
                    + "raise exception 'forced failure for the rollback test' using errcode = '" + errcode + "', constraint = '"
                    + constraint + "'; end; $$");
            DbAccess.execute(c, "create trigger " + trigger + " before " + operation + " on ipd.medicine_order for each row "
                    + "when (new.tenant_id = '" + clinic.tenantId() + "'::uuid and " + when + ") execute function " + function + "()");
            try {
                action.run();
            } finally {
                DbAccess.execute(c, "drop trigger if exists " + trigger + " on ipd.medicine_order");
                DbAccess.execute(c, "drop function if exists " + function + "()");
            }
        }
    }

    private void assertGeneric500(JsonNode problem, String... neverShown) {
        assertThat(problem.get("type").asText()).endsWith("/internal-error");
        assertThat(problem.get("detail").asText()).isEqualTo(INTERNAL_TEXT);
        String text = problem.toString();
        assertThat(text).doesNotContain("forced failure").doesNotContain("ipd.").doesNotContain("medicine_order")
                .doesNotContain("constraint").doesNotContain("23505").doesNotContain("23514").doesNotContain("23503")
                .doesNotContain("Exception").doesNotContain("SQL");
        for (String never : neverShown) {
            assertThat(text).doesNotContain(never);
        }
    }

    @Test
    void aDatabaseRefusalOnTheInsertOfAnOrderIsAGeneric500WithTheWholeTransactionRolledBackAndTheSameKeyWorksAfterwards()
            throws Exception {
        World w = world("Penicillin Zq7Xk");
        for (String[] error : ERRORS) {
            MedSnap before = medSnap(w.clinic(), w.adm());
            String key = newKey();
            forced(w.clinic(), "insert", "true", error[0], error[1], () -> {
                try (LogCapture logs = new LogCapture()) {
                    JsonNode p = problem(call(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                            js(orderBody(key, "pcm", "od", null, null))), 500);
                    assertGeneric500(p, w.adm().name(), w.adm().mobile(), "Zq7Xk");
                    assertThat(logs.allText()).doesNotContain("Zq7Xk").doesNotContain(w.adm().name()).doesNotContain(w.adm().mobile());
                }
            });
            assertThat(orderCount(w.clinic(), w.adm().id())).as(error[0]).isZero();
            assertThat(medSnap(w.clinic(), w.adm())).as("nothing survived the rollback: " + error[0]).isEqualTo(before);
        }
        World clean = world(NONE_KNOWN);
        String key = newKey();
        forced(clean.clinic(), "insert", "true", "23505", ERRORS[0][1], () ->
                problem(call(HttpMethod.POST, ordersUrl(clean.adm().id()), clean.doctor().auth(),
                        js(orderBody(key, "pcm", "od", null, null))), 500));
        JsonNode retry = ok(HttpMethod.POST, ordersUrl(clean.adm().id()), clean.doctor().auth(),
                js(orderBody(key, "pcm", "od", null, null)), 201);
        assertThat(retry.get("replayed").asBoolean()).isFalse();
        assertThat(orderCount(clean.clinic(), clean.adm().id())).isOne();
    }

    @Test
    void aDatabaseRefusalOnTheVerifyTransitionIsAGeneric500TheOrderStaysPendingAndTheRightPinWasStillCountedAsRight()
            throws Exception {
        for (String[] error : ERRORS) {
            World w = world(NONE_KNOWN);
            String id = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
            String token = tokenOfOrder(w.pharmacist().auth(), id);
            // one wrong try first, so the PIN row has something a failed request must not lose
            problem(verifyCall(w.pharmacist(), id, "9306".equals(w.pin()) ? "9307" : "9306", token), 403);
            MedSnap before = medSnap(w.clinic(), w.adm());
            forced(w.clinic(), "update", "new.status = 'ACTIVE'", error[0], error[1], () -> {
                JsonNode p = problem(verifyCall(w.pharmacist(), id, w.pin(), token), 500);
                assertGeneric500(p);
            });
            MedSnap after = medSnap(w.clinic(), w.adm());
            assertThat(after.orders()).as(error[0]).isEqualTo(before.orders());
            assertThat(after.events()).isEqualTo(before.events());
            assertThat(after.audits()).isEqualTo(before.audits());
            assertThat(state(w.clinic(), id).status()).isEqualTo("PENDING");
            assertThat(pinRow(w.clinic(), w.pharmacist().membershipId()).failed())
                    .as("the PIN check committed before the failed transaction: the right PIN cleared the wrong try").isZero();
            ok(HttpMethod.POST, verifyUrl(id), w.pharmacist().auth(), verifyBody(w.pin(), token), 200);
            assertThat(state(w.clinic(), id).status()).isEqualTo("ACTIVE");
        }
    }

    @Test
    void aDatabaseRefusalOnStopOrRejectIsAGeneric500WithNothingWrittenAndTheSameCallWorksAfterwards() throws Exception {
        for (String[] error : ERRORS) {
            World w = world(NONE_KNOWN);
            String toStop = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
            String toReject = orderIdOf(place(w.doctor().auth(), w.adm().id(), "panto"));
            MedSnap before = medSnap(w.clinic(), w.adm());
            forced(w.clinic(), "update", "new.status = 'STOPPED'", error[0], error[1], () ->
                    assertGeneric500(problem(call(HttpMethod.POST, stopUrl(toStop), w.doctor().auth(), null), 500)));
            forced(w.clinic(), "update", "new.status = 'REJECTED'", error[0], error[1], () ->
                    assertGeneric500(problem(rejectCall(w.pharmacist().auth(), toReject, "WRONG_DOSE"), 500)));
            assertThat(medSnap(w.clinic(), w.adm())).as(error[0]).isEqualTo(before);
            assertThat(state(w.clinic(), toStop).status()).isEqualTo("PENDING");
            assertThat(state(w.clinic(), toReject).status()).isEqualTo("PENDING");
            stopOk(w.doctor().auth(), toStop);
            rejectOk(w.pharmacist().auth(), toReject, "WRONG_DOSE");
        }
    }

    @Test
    void theGuardTriggerItselfIsReachedAsA500WhenTheJavaCodeSkipsItsOwnCheckForExampleAStopAfterDischargeRacedPastTheLock()
            throws Exception {
        // The Java check answers 409 not-admitted first; the trigger is the backstop. Prove the backstop by making the
        // admission look discharged to the trigger only: a trigger that raises the guard's own SQL state and message.
        World w = world(NONE_KNOWN);
        String id = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        MedSnap before = medSnap(w.clinic(), w.adm());
        forced(w.clinic(), "update", "new.status = 'STOPPED'", "23514", "ck_medicine_order_stopped_at", () -> {
            JsonNode p = problem(call(HttpMethod.POST, stopUrl(id), w.doctor().auth(), null), 500);
            assertGeneric500(p, "only while the patient is admitted");
        });
        assertThat(medSnap(w.clinic(), w.adm())).isEqualTo(before);
    }
}
