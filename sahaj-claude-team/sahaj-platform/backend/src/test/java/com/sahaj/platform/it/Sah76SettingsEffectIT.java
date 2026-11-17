package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-76-6 what the clinic settings and the ward profile change, on the real {@code sahaj_test} database: whether a new order
 * needs the pharmacist check, the interval stamped on each vitals record and the vitals due time of the Nursing sheet, the dose
 * windows and the STAT window in the MAR (the windows block, the slot states and the Safety counts, from the ward profile
 * else the clinic settings else the built 60/60/15), the window named in the "not due yet" text, and every slot state that
 * {@code DoseSlots} decides without a hold, a stop or a change (those need the order changes of work package 1). Orders and
 * profiles are made through the real endpoints; a scheduled order is made old by the table owner so that the fixed clock of
 * {@link MarSupport} decides the states. Test plan: docs/test-plans/SAH-76-dose-settings.md (tests E-01 to E-14).
 */
@OnTestDatabase
@WithPinPepper
class Sah76SettingsEffectIT extends Sah76NursingSupport {

    private static Map<String, Object> profile(String name, boolean verification, int vitals, Integer before, Integer after) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("medVerificationRequired", verification);
        m.put("vitalsEveryMinutes", vitals);
        m.put("doseWindowBeforeMinutes", before);
        m.put("doseWindowAfterMinutes", after);
        return m;
    }

    private static Map<String, Object> clinicSettings(boolean verification, int before, int after, int stat) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("medVerificationRequired", verification);
        m.put("doseWindowBeforeMinutes", before);
        m.put("doseWindowAfterMinutes", after);
        m.put("statWindowMinutes", stat);
        m.put("unackFlagMinutes", 30);
        m.put("unackFlagStatMinutes", 5);
        m.put("undoSeconds", 10);
        return m;
    }

    private String wardOf(Ward w) throws Exception {
        return values(w.clinic(), "select b.ward_id::text from ipd.admission a join ipd.bed b on b.id = a.bed_id where a.id = ?::uuid",
                w.adm().id()).get(0);
    }

    private void chooseProfile(Ward w, String key) throws Exception {
        ok(HttpMethod.PUT, wardProfileUrl(wardOf(w)), w.clinic().owner(), "{\"profileKey\":" + (key == null ? "null" : "\"" + key + "\"") + "}",
                200);
    }

    private void newProfile(Ward w, Map<String, Object> body) throws Exception {
        ok(HttpMethod.POST, PROFILES, w.clinic().owner(), js(body), 201);
    }

    private String orderStatus(Ward w, String orderId) throws Exception {
        return values(w.clinic(), "select status from ipd.medicine_order where id = ?::uuid", orderId).get(0);
    }

    private String versionVerification(Ward w, String orderId) throws Exception {
        return values(w.clinic(), "select verification from ipd.medicine_order_version where order_id = ?::uuid and version_no = 1",
                orderId).get(0);
    }

    // ---------------- E-01 .. E-03 verification ----------------

    @Test
    void aWardProfileWithVerificationOffGivesAnActiveOrderAndTheOrdersAlreadyPlacedAreNotRewritten() throws Exception {
        Ward w = ward();
        String before = placeOrder(w.doctor().auth(), w.adm().id(), sched("pcm", "od", "TABLET", "ORAL"));
        assertThat(orderStatus(w, before)).isEqualTo("PENDING");
        assertThat(versionVerification(w, before)).isEqualTo("REQUIRED");

        newProfile(w, profile("No check", false, 240, null, null));
        chooseProfile(w, "no-check");
        String off = placeOrder(w.doctor().auth(), w.adm().id(), sched("panto", "od", "MG", "IV"));
        assertThat(orderStatus(w, off)).as("no pharmacist check: active at once").isEqualTo("ACTIVE");
        assertThat(versionVerification(w, off)).isEqualTo("NOT_REQUIRED");
        assertThat(queueRow(queue(w.pharmacist().auth(), "limit=100"), off)).as("not in the pharmacist queue").isNull();
        assertThat(queueRow(queue(w.pharmacist().auth(), "limit=100"), before)).as("an order placed before stays waiting").isNotNull();
        assertThat(orderStatus(w, before)).isEqualTo("PENDING");
        JsonNode mar = mar(w.nurse().auth(), w.adm().id());
        assertThat(marOrder(mar, off).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(marOrder(mar, off).get("details").get("pharmacistChecked").asBoolean()).isFalse();
        assertThat(count(w.clinic(), "select count(*) from ipd.order_notice where medicine_order_id = ?::uuid and kind = 'NEW'", off))
                .as("a notice is written at once for an order that needs no check").isOne();
        assertThat(count(w.clinic(), "select count(*) from ipd.order_notice where medicine_order_id = ?::uuid", before))
                .as("the waiting order has no notice until it is verified").isZero();

        // verification On again for the next order only
        ok(HttpMethod.PUT, profileUrl("no-check"), w.clinic().owner(), js(profile("No check", true, 240, null, null)), 200);
        String on = placeOrder(w.doctor().auth(), w.adm().id(), sched("ondan", "od", "MG", "IV"));
        assertThat(orderStatus(w, on)).isEqualTo("PENDING");
        assertThat(orderStatus(w, off)).as("past orders are never rewritten").isEqualTo("ACTIVE");
        assertThat(versionVerification(w, off)).isEqualTo("NOT_REQUIRED");
    }

    @Test
    void aWardWithoutAProfileFollowsTheClinicSettingForVerification() throws Exception {
        Ward w = ward();
        ok(HttpMethod.PUT, SETTINGS, w.clinic().owner(), js(clinicSettings(false, 60, 60, 15)), 200);
        String order = placeOrder(w.doctor().auth(), w.adm().id(), sched("pcm", "od", "TABLET", "ORAL"));
        assertThat(orderStatus(w, order)).isEqualTo("ACTIVE");
        // a ward profile wins over the clinic setting
        newProfile(w, profile("Checked", true, 240, null, null));
        chooseProfile(w, "checked");
        String checked = placeOrder(w.doctor().auth(), w.adm().id(), sched("panto", "od", "MG", "IV"));
        assertThat(orderStatus(w, checked)).isEqualTo("PENDING");
        chooseProfile(w, null);
        String again = placeOrder(w.doctor().auth(), w.adm().id(), sched("ceft", "od", "MG", "IV"));
        assertThat(orderStatus(w, again)).isEqualTo("ACTIVE");
    }

    @Test
    void aStatOrderIsNotWaitingOnTheCheckWhateverTheProfileSays() throws Exception {
        Ward w = ward();
        realClock();
        String stat = placeOrder(w.doctor().auth(), w.adm().id(), statOrder("panto", "MG", "IV"));
        assertThat(orderStatus(w, stat)).isEqualTo("PENDING");
        JsonNode slot = slot(mar(w.nurse().auth(), w.adm().id()), stat, 0);
        assertThat(slot.get("state").asText()).isNotEqualTo("WAITING");
        assertThat(count(w.clinic(), "select count(*) from ipd.order_notice where medicine_order_id = ?::uuid and kind = 'NEW'", stat))
                .as("a STAT order writes its notice at once").isOne();
    }

    // ---------------- E-04 .. E-05 vitals ----------------

    @Test
    void theVitalsIntervalOfTheWardProfileIsStampedOnEachRecordAndDecidesWhenTheNextIsDue() throws Exception {
        Ward w = ward();
        String nursing = nursingUrl(w.adm().id());
        JsonNode none = ok(HttpMethod.GET, nursing, w.nurse().auth(), null, 200);
        assertThat(none.get("vitalsIntervalMinutes").asInt()).as("the built interval is 4 hours").isEqualTo(240);
        assertThat(none.get("vitalsDueAt").isNull()).as("no vitals yet: due at once").isTrue();

        chooseProfile(w, "icu");
        assertThat(ok(HttpMethod.GET, nursing, w.nurse().auth(), null, 200).get("vitalsIntervalMinutes").asInt()).isEqualTo(60);
        ok(HttpMethod.POST, vitalsUrl(w.adm().id()), w.nurse().auth(), js(low()), 201);
        assertThat(count(w.clinic(), "select count(*) from ipd.vitals where admission_id = ?::uuid and due_interval_min = 60",
                w.adm().id())).isOne();
        Instant first = instantOf(w.clinic(), "select recorded_at from ipd.vitals where admission_id = ?::uuid order by recorded_at "
                + "desc limit 1", w.adm().id());
        JsonNode sheet = ok(HttpMethod.GET, nursing, w.nurse().auth(), null, 200);
        assertThat(Duration.between(Instant.parse(sheet.get("vitalsDueAt").asText()), first.plus(Duration.ofMinutes(60))).abs())
                .isLessThan(Duration.ofSeconds(2));

        // a later change of the profile changes the next stamp only, never the stamped one
        chooseProfile(w, "general-ward");
        JsonNode changed = ok(HttpMethod.GET, nursing, w.nurse().auth(), null, 200);
        assertThat(changed.get("vitalsIntervalMinutes").asInt()).isEqualTo(240);
        assertThat(changed.get("vitalsDueAt").asText()).as("the stamp of the saved record decides").isEqualTo(sheet.get("vitalsDueAt").asText());
        ok(HttpMethod.POST, vitalsUrl(w.adm().id()), w.nurse().auth(), js(low()), 201);
        assertThat(count(w.clinic(), "select count(*) from ipd.vitals where admission_id = ?::uuid and due_interval_min = 240",
                w.adm().id())).isOne();
        Instant second = instantOf(w.clinic(), "select recorded_at from ipd.vitals where admission_id = ?::uuid order by recorded_at "
                + "desc limit 1", w.adm().id());
        assertThat(Duration.between(Instant.parse(ok(HttpMethod.GET, nursing, w.nurse().auth(), null, 200).get("vitalsDueAt").asText()),
                second.plus(Duration.ofMinutes(240))).abs()).isLessThan(Duration.ofSeconds(2));
        assertThat(count(w.clinic(), "select count(*) from ipd.vitals where admission_id = ?::uuid and due_interval_min = 60",
                w.adm().id())).as("the earlier record keeps its stamp").isOne();
    }

    @Test
    void anOwnProfileIntervalAtTheEdgesIsStampedAndTheOldRecordsReadAsFourHours() throws Exception {
        Ward w = ward();
        newProfile(w, profile("Every 15", true, 15, null, null));
        newProfile(w, profile("Once a day", true, 1440, null, null));
        chooseProfile(w, "every-15");
        ok(HttpMethod.POST, vitalsUrl(w.adm().id()), w.nurse().auth(), js(low()), 201);
        assertThat(ok(HttpMethod.GET, nursingUrl(w.adm().id()), w.nurse().auth(), null, 200).get("vitalsIntervalMinutes").asInt())
                .isEqualTo(15);
        chooseProfile(w, "once-a-day");
        ok(HttpMethod.POST, vitalsUrl(w.adm().id()), w.nurse().auth(), js(low()), 201);
        List<List<String>> stamps = table(w.clinic(), "select due_interval_min::text from ipd.vitals where admission_id = ?::uuid "
                + "order by recorded_at", w.adm().id());
        assertThat(stamps).extracting(r -> r.get(0)).containsExactly("15", "1440");
        // a record written without a stamp (older than this release) reads as 240 minutes
        Ward old = ward();
        ok(HttpMethod.POST, vitalsUrl(old.adm().id()), old.nurse().auth(), js(low()), 201);
        asOwner(true, new String[] {"ipd.vitals", "ipd.admission"}, c -> {
            DbAccess.execute(c, "alter table ipd.vitals disable trigger trg_vitals_no_update_delete");
            DbAccess.execute(c, "update ipd.vitals set due_interval_min = null where admission_id = ?::uuid", old.adm().id());
            DbAccess.execute(c, "alter table ipd.vitals enable trigger trg_vitals_no_update_delete");
            return null;
        });
        Instant recorded = instantOf(old.clinic(), "select recorded_at from ipd.vitals where admission_id = ?::uuid", old.adm().id());
        JsonNode sheet = ok(HttpMethod.GET, nursingUrl(old.adm().id()), old.nurse().auth(), null, 200);
        assertThat(Duration.between(Instant.parse(sheet.get("vitalsDueAt").asText()), recorded.plus(Duration.ofMinutes(240))).abs())
                .isLessThan(Duration.ofSeconds(2));
    }

    // ---------------- E-06 .. E-09 windows ----------------

    @Test
    void theWardProfileWindowsDecideDueAndLateInTheMarAndTheSafetyCountsAndTheClinicSettingsAreTheFallback() throws Exception {
        Ward w = ward();
        String order = activeScheduled(w, "pcm", "od", "TABLET", "ORAL");
        clockAt(8, 30);
        JsonNode mar = mar(w.nurse().auth(), w.adm().id());
        assertThat(mar.get("windows").get("beforeMinutes").asInt()).isEqualTo(60);
        assertThat(mar.get("windows").get("afterMinutes").asInt()).isEqualTo(60);
        assertThat(mar.get("windows").get("statMinutes").asInt()).isEqualTo(15);
        assertThat(mar.get("windows").get("undoSeconds").asInt()).isEqualTo(10);
        assertThat(slot(mar, order, 0).get("state").asText()).isEqualTo("DUE");
        assertThat(summaryOf(w.nurse().auth()).get("dosesDue").asInt()).isOne();
        assertThat(summaryOf(w.nurse().auth()).get("dosesLate").asInt()).isZero();
        assertThat(mar.get("counters").get("dueNow").asInt()).isOne();

        newProfile(w, profile("Tight", true, 240, 15, 15));
        chooseProfile(w, "tight");
        JsonNode tight = mar(w.nurse().auth(), w.adm().id());
        assertThat(tight.get("windows").get("beforeMinutes").asInt()).isEqualTo(15);
        assertThat(tight.get("windows").get("afterMinutes").asInt()).isEqualTo(15);
        JsonNode late = slot(tight, order, 0);
        assertThat(late.get("state").asText()).isEqualTo("LATE");
        assertThat(late.get("lateMinutes").asInt()).isEqualTo(30);
        assertThat(late.get("canGive").asBoolean()).isTrue();
        assertThat(summaryOf(w.nurse().auth()).get("dosesDue").asInt()).isZero();
        assertThat(summaryOf(w.nurse().auth()).get("dosesLate").asInt()).isOne();
        assertThat(tight.get("counters").get("late").asInt()).isOne();
        assertThat(tight.get("doseCount").asInt()).as("DUE and LATE slots are both counted on the tab").isOne();

        // a profile with no windows falls back to the clinic settings
        newProfile(w, profile("Plain", true, 240, null, null));
        ok(HttpMethod.PUT, SETTINGS, w.clinic().owner(), js(clinicSettings(true, 45, 50, 15)), 200);
        chooseProfile(w, "plain");
        JsonNode plain = mar(w.nurse().auth(), w.adm().id());
        assertThat(plain.get("windows").get("beforeMinutes").asInt()).isEqualTo(45);
        assertThat(plain.get("windows").get("afterMinutes").asInt()).isEqualTo(50);
        clockAt(8, 50);
        assertThat(slot(mar(w.nurse().auth(), w.adm().id()), order, 0).get("state").asText()).as("exactly the window is still due").isEqualTo("DUE");
        clockAt(8, 51);
        assertThat(slot(mar(w.nurse().auth(), w.adm().id()), order, 0).get("state").asText()).isEqualTo("LATE");
        chooseProfile(w, null);
        assertThat(mar(w.nurse().auth(), w.adm().id()).get("windows").get("afterMinutes").asInt()).as("no profile: the clinic").isEqualTo(50);
    }

    @Test
    void theWindowBeforeDecidesLaterAndTheRefusalTextNamesTheMinutesInForce() throws Exception {
        Ward w = ward();
        String order = activeScheduled(w, "pcm", "od", "TABLET", "ORAL");
        String code = codeOf("pcm");
        clockAt(7, 30);
        assertThat(slot(mar(w.nurse().auth(), w.adm().id()), order, 0).get("state").asText()).as("30 minutes ahead is due by the built 60").isEqualTo("DUE");
        newProfile(w, profile("Late birds", true, 240, 15, null));
        chooseProfile(w, "late-birds");
        JsonNode mar = mar(w.nurse().auth(), w.adm().id());
        assertThat(slot(mar, order, 0).get("state").asText()).isEqualTo("LATER");
        assertThat(slot(mar, order, 0).get("canGive").asBoolean()).isFalse();
        assertThat(mar.get("doseCount").asInt()).isZero();
        refused(doseCall(w.nurse().auth(), order, scanGiven(w, ist(8, 0), 1, code)), 409, "dose-not-due",
                "This dose is not due yet. It can be given from 15 min before its time.");
        assertThat(doseRowsOfOrder(w.clinic(), order)).isZero();
        clockAt(7, 45);
        doseOk(w.nurse().auth(), order, scanGiven(w, ist(8, 0), 1, code));
    }

    @Test
    void theStatWindowDecidesWhenAStatDoseTurnsLate() throws Exception {
        Ward w = ward();
        realClock();
        String order = placeOrder(w.doctor().auth(), w.adm().id(), statOrder("panto", "MG", "IV"));
        Instant created = Instant.now();
        clockAt(created.plus(Duration.ofMinutes(10)));
        JsonNode mar = mar(w.nurse().auth(), w.adm().id());
        assertThat(mar.get("windows").get("statMinutes").asInt()).isEqualTo(15);
        assertThat(slot(mar, order, 0).get("state").asText()).as("10 minutes into a 15 minute STAT window").isEqualTo("DUE");
        ok(HttpMethod.PUT, SETTINGS, w.clinic().owner(), js(clinicSettings(true, 60, 60, 5)), 200);
        JsonNode tight = mar(w.nurse().auth(), w.adm().id());
        assertThat(tight.get("windows").get("statMinutes").asInt()).isEqualTo(5);
        JsonNode slot = slot(tight, order, 0);
        assertThat(slot.get("state").asText()).isEqualTo("LATE");
        assertThat(slot.get("lateMinutes").asInt()).as("overdue is counted from the end of the STAT window").isBetween(4, 7);
        assertThat(slot.get("canGive").asBoolean()).isTrue();
        assertThat(summaryOf(w.nurse().auth()).get("dosesLate").asInt()).isOne();
    }

    // ---------------- E-10 .. E-12 slot states of DoseSlots ----------------

    @Test
    void theSlotStatesOfAnOrderFollowTheClockTheOutcomeAndTheVerification() throws Exception {
        Ward w = ward();
        String q8 = activeScheduled(w, "ceft", "q8", "MG", "IV");
        clockAt(14, 10);
        JsonNode mar = mar(w.nurse().auth(), w.adm().id());
        assertThat(states(mar, q8)).containsExactly("LATE", "DUE", "LATER");
        assertThat(slot(mar, q8, 0).get("lateMinutes").asInt()).isEqualTo(490);
        assertThat(slot(mar, q8, 0).get("lateHours").asInt()).isEqualTo(8);
        assertThat(slot(mar, q8, 1).get("lateMinutes").isNull()).isTrue();
        assertThat(slot(mar, q8, 2).get("canGive").asBoolean()).isFalse();

        String code = codeOf("ceft");
        doseOk(w.nurse().auth(), q8, scanGiven(w, ist(6, 0), 1, code));
        doseOk(w.nurse().auth(), q8, notGivenBody(ist(14, 0), 1, "VOMITED", null));
        assertThat(states(mar(w.nurse().auth(), w.adm().id()), q8)).containsExactly("GIVEN", "HELD", "LATER");

        String od = activeScheduled(w, "pcm", "od", "TABLET", "ORAL");
        doseOk(w.nurse().auth(), od, notGivenBody(ist(8, 0), 1, "PATIENT_REFUSED", null));
        assertThat(states(mar(w.nurse().auth(), w.adm().id()), od)).containsExactly("REFUSED");

        String waiting = placeOrder(w.doctor().auth(), w.adm().id(), sched("panto", "od", "MG", "IV"));
        backdate(w.clinic(), waiting, istOn(day.minusDays(1), 6, 0), istOn(day.minusDays(1), 6, 0), istOn(day.minusDays(1), 7, 0));
        assertThat(states(mar(w.nurse().auth(), w.adm().id()), waiting)).containsExactly("WAITING");
        JsonNode waitingSlot = slot(mar(w.nurse().auth(), w.adm().id()), waiting, 0);
        assertThat(waitingSlot.get("canGive").asBoolean()).isFalse();
        refused(doseCall(w.nurse().auth(), waiting, scanGiven(w, ist(8, 0), 1, codeOf("panto"))), 409, "order-not-verified", NOT_VERIFIED);
    }

    @Test
    void aSlotBeforeTheTimeTheOrderWasCheckedIsBeforeStartAndIsRefused() throws Exception {
        Ward w = ward();
        String q8 = placeOrder(w.doctor().auth(), w.adm().id(), sched("ceft", "q8", "MG", "IV"));
        verifyNow(w, q8);
        backdate(w.clinic(), q8, istOn(day.minusDays(1), 6, 0), ist(9, 0), ist(10, 0));
        clockAt(10, 30);
        JsonNode mar = mar(w.nurse().auth(), w.adm().id());
        assertThat(states(mar, q8)).containsExactly("BEFORE_START", "LATER", "LATER");
        assertThat(slot(mar, q8, 0).get("canGive").asBoolean()).isFalse();
        refused(doseCall(w.nurse().auth(), q8, scanGiven(w, ist(6, 0), 1, codeOf("ceft"))), 409, "dose-before-start", BEFORE_START);
        assertThat(doseRowsOfOrder(w.clinic(), q8)).isZero();
        assertThat(mar.get("doseCount").asInt()).isZero();
        assertThat(summaryOf(w.nurse().auth()).get("dosesLate").asInt()).as("a slot before the start is never late").isZero();
    }

    @Test
    void afterDischargeEverySlotThatWasNotRecordedIsNotRecorded() throws Exception {
        Ward w = ward();
        String order = activeScheduled(w, "pcm", "od", "TABLET", "ORAL");
        String other = activeScheduled(w, "panto", "od", "MG", "IV");
        clockAt(8, 30);
        doseOk(w.nurse().auth(), order, scanGiven(w, ist(8, 0), 1, codeOf("pcm")));
        discharge(w);
        JsonNode mar = mar(w.nurse().auth(), w.adm().id());
        assertThat(states(mar, order)).containsExactly("GIVEN");
        assertThat(states(mar, other)).containsExactly("NOT_RECORDED");
        assertThat(mar.get("doseCount").asInt()).isZero();
        assertThat(mar.get("canGive").asBoolean()).isFalse();
        assertThat(slot(mar, other, 0).get("canGive").asBoolean()).isFalse();
    }

    @Test
    void theMarOfAnotherClinicNeverShowsTheWindowsOfThisOne() throws Exception {
        Ward a = ward();
        Ward b = ward();
        newProfile(a, profile("Tight", true, 15, 5, 5));
        chooseProfile(a, "tight");
        ok(HttpMethod.PUT, SETTINGS, a.clinic().owner(), js(clinicSettings(false, 20, 20, 7)), 200);
        JsonNode mine = mar(b.nurse().auth(), b.adm().id());
        assertThat(mine.get("windows").get("beforeMinutes").asInt()).isEqualTo(60);
        assertThat(mine.get("windows").get("statMinutes").asInt()).isEqualTo(15);
        assertThat(ok(HttpMethod.GET, nursingUrl(b.adm().id()), b.nurse().auth(), null, 200).get("vitalsIntervalMinutes").asInt())
                .isEqualTo(240);
        String order = placeOrder(b.doctor().auth(), b.adm().id(), sched("pcm", "od", "TABLET", "ORAL"));
        assertThat(orderStatus(b, order)).as("clinic B still needs the check").isEqualTo("PENDING");
    }
}
