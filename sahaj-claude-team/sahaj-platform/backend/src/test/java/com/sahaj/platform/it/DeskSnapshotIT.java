package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

/**
 * SAH-90 desk snapshot and day-slots against the real sahaj_test database: shape, counters, role and tenant rules,
 * the statement count with many doctors, and the partial indexes. Test plan: docs/test-plans/SAH-90.md (T90-*).
 * This class has its own Spring context (the request pool is wrapped to count statements).
 */
@OnTestDatabase
@Import(DeskSnapshotIT.Counting.class)
class DeskSnapshotIT extends DeskSupport {

    /** Counts the statements the application sends on the request pool (prepareStatement and createStatement). */
    @TestConfiguration
    static class Counting {
        static final AtomicLong STATEMENTS = new AtomicLong();

        @Bean
        static BeanPostProcessor statementCountingDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof DataSource) || Proxy.isProxyClass(bean.getClass())) {
                        return bean;
                    }
                    DataSource target = (DataSource) bean;
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                            (proxy, method, args) -> {
                                Object result = invoke(target, method, args);
                                return "getConnection".equals(method.getName()) ? wrap((Connection) result) : result;
                            });
                }

                private Connection wrap(Connection connection) {
                    return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                            (InvocationHandler) (proxy, method, args) -> {
                                String name = method.getName();
                                if (name.equals("prepareStatement") || name.equals("createStatement") || name.equals("prepareCall")) {
                                    STATEMENTS.incrementAndGet();
                                }
                                return invoke(connection, method, args);
                            });
                }

                private Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException ex) {
                        throw ex.getCause();
                    }
                }
            };
        }
    }

    private long statementsFor(Clinic clinic) throws Exception {
        long before = Counting.STATEMENTS.get();
        mvc.perform(get(DESK + "/snapshot").with(clinic.owner())).andExpect(status().isOk());
        return Counting.STATEMENTS.get() - before;
    }

    // ---------------------------------------------------------------- shape, counters, parity with the write rules

    @Test
    void aClinicWithTwelveOrMoreDoctorsGetsEveryDoctorWithSevenDaysAndFreeCountsThatMatchTheDaySlots() throws Exception {
        Clinic clinic = newClinic();
        List<UUID> doctors = new ArrayList<>(List.of(clinic.doctorMembershipId()));
        doctors.addAll(addDoctorsDirect(clinic, 11));
        setSlots(clinic, doctors.get(0), "09:00", "10:00", 15, 0, null);
        JsonNode snap = snapshot(clinic, clinic.owner());

        assertThat(snap.get("doctors").get("total").asInt()).isGreaterThanOrEqualTo(12);
        assertThat(snap.get("doctors").get("items")).hasSize(snap.get("doctors").get("total").asInt());
        assertThat(snap.get("today").asText()).isEqualTo(istToday().toString());
        int sum = 0;
        for (JsonNode d : snap.get("doctors").get("items")) {
            assertThat(d.get("days")).hasSize(7);
            assertThat(d.get("days").get(0).get("date").asText()).isEqualTo(istToday().toString());
            assertThat(d.get("freeToday").asInt()).isEqualTo(d.get("days").get(0).get("freeCount").asInt());
            sum += d.get("freeToday").asInt();
        }
        assertThat(snap.get("counters").get("freeToday").asInt()).isEqualTo(sum);

        // read and write agree: the free count of tomorrow in the strip equals the day-slots read
        JsonNode first = null;
        for (JsonNode d : snap.get("doctors").get("items")) {
            if (d.get("doctorMembershipId").asText().equals(doctors.get(0).toString())) {
                first = d;
            }
        }
        assertThat(first).isNotNull();
        assertThat(first.get("days").get(1).get("freeCount").asInt()).isEqualTo(4);
        assertThat(first.get("days").get(1).get("freeCount").asInt())
                .isEqualTo(daySlots(clinic.owner(), doctors.get(0), tomorrow()).get("freeCount").asInt());
        // fee rules (C1): the wizard set the follow-up fee to free
        assertThat(snap.get("followUpFee").asText()).isEqualTo("free");
        assertThat(first.get("followUpFeePaise").asLong()).isZero();
        assertThat(first.get("consultFeePaise").asLong()).isEqualTo(30_000L);
        assertThat(first.get("departmentSource").asText()).isIn("EMR_DEPARTMENT", "SPECIALTY");
        assertThat(first.get("department").asText()).isNotBlank();

        // booking one slot lowers tomorrow's count and removes exactly that slot (acceptance check 2, read part)
        book(clinic, newPatient(clinic), doctors.get(0), tomorrow(), "09:15");
        JsonNode after = snapshot(clinic, clinic.owner());
        for (JsonNode d : after.get("doctors").get("items")) {
            if (d.get("doctorMembershipId").asText().equals(doctors.get(0).toString())) {
                assertThat(d.get("days").get(1).get("freeCount").asInt()).isEqualTo(3);
            }
        }
        assertThat(times(daySlots(clinic.owner(), doctors.get(0), tomorrow()))).containsExactly("09:00", "09:30", "09:45");
    }

    @Test
    void aDoctorWhoDoesNotWorkTodayIsOffTodayAndTheSavedRulesAreEchoedForTheFooter() throws Exception {
        Clinic clinic = newClinic();
        UUID doctor = clinic.doctorMembershipId();
        int today = istToday().getDayOfWeek().getValue() % 7;
        StringBuilder days = new StringBuilder("[");
        for (int d = 0; d < 7; d++) {
            if (d != today) {
                days.append(days.length() > 1 ? "," : "").append(d);
            }
        }
        setSlots(clinic, doctor, days.append("]").toString(), "09:00", "10:00", 15, 0, null);
        setRules(clinic, 5, true, 30);
        JsonNode snap = snapshot(clinic, clinic.owner());
        JsonNode row = null;
        for (JsonNode d : snap.get("doctors").get("items")) {
            if (d.get("doctorMembershipId").asText().equals(doctor.toString())) {
                row = d;
            }
        }
        assertThat(row.get("todayStatus").asText()).isEqualTo("OFF_TODAY");
        assertThat(row.get("freeToday").asInt()).isZero();
        assertThat(row.get("nextFree").get("date").asText()).isEqualTo(tomorrow().toString());
        // acceptance check 8: the footer values are the saved rules
        JsonNode rules = snap.get("bookingRules");
        assertThat(rules.get("closeBeforeHours").asInt()).isEqualTo(5);
        assertThat(rules.get("noShowAfterMinutes").asInt()).isEqualTo(30);
        assertThat(rules.get("overbook").asBoolean()).isTrue();
        assertThat(rules.get("followPriority").asBoolean()).isFalse();
        assertThat(rules.get("selfBook").asBoolean()).isTrue();
        assertThat(rules.get("remindBeforeHours").asInt()).isEqualTo(4);
        assertThat(row.get("closeBeforeHours").asInt()).isEqualTo(5);
    }

    @Test
    void theBoardAndCountersFollowTodaysAppointmentsWalkInsAndTheNoShowRule() throws Exception {
        Clinic clinic = newClinic();
        UUID doctor = clinic.doctorMembershipId();
        setRules(clinic, 0, false, 0); // grace 0: any time of today before now is a no-show
        String late = bookForToday(clinic, newPatient(clinic), doctor, "09:00").get("id").asText();
        exec(clinic.tenantId(), "update opd.appointment set appt_time = '00:00' where id = ?::uuid", late);
        String manual = bookForToday(clinic, newPatient(clinic), doctor, "09:15").get("id").asText();
        exec(clinic.tenantId(), "update opd.appointment set appt_time = '23:59' where id = ?::uuid", manual);
        UUID walkInPatient = newPatient(clinic);
        JsonNode walkIn = ok(HttpMethod.POST, VISITS, clinic.owner(), "{\"patientId\":\"" + walkInPatient
                + "\",\"doctorMembershipId\":\"" + doctor + "\"}", 201);

        JsonNode snap = snapshot(clinic, clinic.owner());
        JsonNode lateCard = card(snap, late);
        assertThat(lateCard.get("column").asText()).isEqualTo("BOOKED");
        assertThat(lateCard.get("tags").toString()).contains("NO_SHOW");
        assertThat(lateCard.get("primaryAction").asText()).isEqualTo("REBOOK");
        JsonNode manualCard = card(snap, manual);
        assertThat(manualCard.get("tags").toString()).doesNotContain("NO_SHOW");
        assertThat(manualCard.get("primaryAction").asText()).isEqualTo("CHECK_IN");
        JsonNode walkCard = cardOfVisit(snap, walkIn.get("id").asText());
        assertThat(walkCard.get("kind").asText()).isEqualTo("WALK_IN");
        assertThat(walkCard.get("column").asText()).isEqualTo("WAITING");
        assertThat(walkCard.get("appointmentId").isNull()).isTrue();
        // counters: the late appointment is a no-show and not counted in Booked
        assertThat(snap.get("counters").get("booked").asInt()).isEqualTo(1);
        assertThat(snap.get("counters").get("waiting").asInt()).isEqualTo(1);

        // marking the other one by hand stores NOSHOW and drops Booked to 0 (SAH-92-4)
        ok(HttpMethod.PATCH, APPTS + "/" + manual, clinic.owner(), "{\"status\":\"NOSHOW\"}", 200);
        JsonNode after = snapshot(clinic, clinic.owner());
        assertThat(after.get("counters").get("booked").asInt()).isZero();
        assertThat(card(after, manual).get("tags").toString()).contains("NO_SHOW");
        assertThat(card(after, manual).get("primaryAction").asText()).isEqualTo("REBOOK");
        // a cancelled appointment is not on the board
        String cancelled = bookForToday(clinic, newPatient(clinic), doctor, "10:00").get("id").asText();
        ok(HttpMethod.PATCH, APPTS + "/" + cancelled, clinic.owner(), "{\"status\":\"CANCELLED\"}", 200);
        assertThat(card(snapshot(clinic, clinic.owner()), cancelled)).isNull();
        // each card key is unique: nobody appears twice
        List<String> keys = new ArrayList<>();
        snapshot(clinic, clinic.owner()).get("board").get("cards").forEach(c -> keys.add(c.get("key").asText()));
        assertThat(keys).doesNotHaveDuplicates();
        // the board filter keeps only one doctor's cards
        UUID other = addDoctor(clinic);
        JsonNode filtered = ok(HttpMethod.GET, DESK + "/snapshot?boardDoctorMembershipId=" + other, clinic.owner(), null, 200);
        assertThat(filtered.get("board").get("cards")).isEmpty();
    }

    @Test
    void theFinderSelectionReturnsThatDoctorsDayAndRefusesBadInput() throws Exception {
        Clinic clinic = newClinic();
        UUID doctor = clinic.doctorMembershipId();
        setSlots(clinic, doctor, "09:00", "10:00", 15, 0, null);
        JsonNode snap = ok(HttpMethod.GET, DESK + "/snapshot?doctorMembershipId=" + doctor + "&date=" + istToday().plusDays(2),
                clinic.owner(), null, 200);
        assertThat(snap.get("selected").get("date").asText()).isEqualTo(istToday().plusDays(2).toString());
        assertThat(snap.get("selected").get("freeCount").asInt()).isEqualTo(4);
        assertThat(snapshot(clinic, clinic.owner()).get("selected").isNull()).isTrue();

        // date outside today .. today + 6 with a doctor: 400; the 7th day is the boundary and works
        problem(call(HttpMethod.GET, DESK + "/snapshot?doctorMembershipId=" + doctor + "&date=" + istToday().plusDays(7),
                clinic.owner(), null), 400);
        problem(call(HttpMethod.GET, DESK + "/snapshot?doctorMembershipId=" + doctor + "&date=" + istToday().minusDays(1),
                clinic.owner(), null), 400);
        ok(HttpMethod.GET, DESK + "/snapshot?doctorMembershipId=" + doctor + "&date=" + istToday().plusDays(6), clinic.owner(), null, 200);
        problem(call(HttpMethod.GET, DESK + "/snapshot?doctorMembershipId=not-a-uuid", clinic.owner(), null), 400);
        problem(call(HttpMethod.GET, DESK + "/snapshot?doctorMembershipId=" + UUID.randomUUID(), clinic.owner(), null), 404);
        problem(call(HttpMethod.GET, DESK + "/snapshot?boardDoctorMembershipId=" + UUID.randomUUID(), clinic.owner(), null), 404);
    }

    // ---------------------------------------------------------------- roles, headers, tenant isolation

    @Test
    void everyClinicRoleReadsTheSnapshotWithNoStoreAndAnAnonymousCallerGets401() throws Exception {
        Clinic clinic = newClinic();
        for (String role : List.of("OWNER", "FRONT_DESK", "NURSE", "DOCTOR", "BILLING")) {
            mvc.perform(get(DESK + "/snapshot").with(clinic.as(role)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")));
            mvc.perform(get(DESK + "/doctors/" + clinic.doctorMembershipId() + "/day-slots?date=" + tomorrow()).with(clinic.as(role)))
                    .andExpect(status().isOk());
        }
        mvc.perform(get(DESK + "/snapshot")).andExpect(status().isUnauthorized());
        mvc.perform(get(DESK + "/doctors/" + clinic.doctorMembershipId() + "/day-slots?date=" + tomorrow()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(DESK + "/visits/" + UUID.randomUUID() + "/token-panel")).andExpect(status().isUnauthorized());
    }

    @Test
    void clinicANeverSeesClinicBsDoctorsPatientsAppointmentsOrFollowUpsAndCannotAskForThem() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        UUID bPatient = newPatient(b);
        String bAppt = bookForToday(b, bPatient, b.doctorMembershipId(), "09:00").get("id").asText();
        JsonNode bCheck = checkIn(b, bAppt);
        String bVisit = bCheck.get("visitId").asText();
        moveStage(b, bVisit, "WITH_DOCTOR", "VITALS", 200);
        moveStage(b, bVisit, "BILLING", "WITH_DOCTOR", 200);
        BackdateSupport.setFollowUpDueDate(db(), b.tenantId(), bVisit, istToday());
        book(b, newPatient(b), b.doctorMembershipId(), tomorrow(), "09:30");

        JsonNode snapA = snapshot(a, a.owner());
        for (JsonNode d : snapA.get("doctors").get("items")) {
            assertThat(d.get("doctorMembershipId").asText()).isNotEqualTo(b.doctorMembershipId().toString());
        }
        assertThat(snapA.get("board").get("cards")).isEmpty();
        assertThat(snapA.get("followUpsToBook").get("total").asInt()).isZero();
        assertThat(snapA.get("counters").get("done").asInt()).isZero();
        // B sees its own
        JsonNode snapB = snapshot(b, b.owner());
        assertThat(card(snapB, bAppt)).isNotNull();
        assertThat(snapB.get("followUpsToBook").get("total").asInt()).isEqualTo(1);

        // A asks for B's ids: 404, never data
        problem(call(HttpMethod.GET, DESK + "/snapshot?doctorMembershipId=" + b.doctorMembershipId(), a.owner(), null), 404);
        problem(call(HttpMethod.GET, DESK + "/snapshot?boardDoctorMembershipId=" + b.doctorMembershipId(), a.owner(), null), 404);
        problem(call(HttpMethod.GET, DESK + "/doctors/" + b.doctorMembershipId() + "/day-slots?date=" + tomorrow(), a.owner(), null), 404);
        problem(call(HttpMethod.GET, DESK + "/visits/" + bVisit + "/token-panel", a.owner(), null), 404);
        // B's taken slot is not taken in A's view of its own doctor, and B's slot data is not in A's day-slots
        assertThat(times(daySlots(a.owner(), a.doctorMembershipId(), tomorrow()))).contains("09:30");

        // under row level security: A's connection sees none of B's appointment, visit or follow-up rows
        assertThat(countOf(a.tenantId(), "select count(*) from opd.appointment where id = ?::uuid", bAppt)).isZero();
        assertThat(countOf(a.tenantId(), "select count(*) from opd.visit where id = ?::uuid", bVisit)).isZero();
        assertThat(countOf(a.tenantId(), "select count(*) from opd.follow_up where source_visit_id = ?::uuid", bVisit)).isZero();
        assertThat(db().<Long>asApp(c -> DbAccess.count(c, "select count(*) from opd.appointment"))).as("no tenant bound").isZero();
    }

    // ---------------------------------------------------------------- statement count and speed with many doctors

    @Test
    void theSnapshotUsesTheSameFewStatementsFor50DoctorsAsFor3AndStaysFast() throws Exception {
        Clinic small = newClinic(); // the owner (a doctor) and Dr Asha Rao, plus one more: 3 doctors
        addDoctorsDirect(small, 1);
        book(small, newPatient(small), small.doctorMembershipId(), tomorrow(), "09:00");
        statementsFor(small); // warm-up
        long smallCount = statementsFor(small);

        Clinic big = newClinic();
        addDoctorsDirect(big, 50);
        for (int i = 0; i < 4; i++) {
            book(big, newPatient(big), big.doctorMembershipId(), tomorrow(), String.format("09:%02d", i * 15));
        }
        JsonNode first = snapshot(big, big.owner());
        assertThat(first.get("doctors").get("total").asInt()).isGreaterThanOrEqualTo(50);
        statementsFor(big); // warm-up
        long bigCount = statementsFor(big);

        assertThat(bigCount).as("statements for 50+ doctors (3 doctors: " + smallCount + ")").isLessThanOrEqualTo(smallCount + 2);
        assertThat(bigCount).as("absolute bound").isLessThanOrEqualTo(30);

        List<Long> millis = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long t0 = System.nanoTime();
            mvc.perform(get(DESK + "/snapshot").with(big.owner())).andExpect(status().isOk());
            millis.add((System.nanoTime() - t0) / 1_000_000);
        }
        Collections.sort(millis);
        long p95 = millis.get((int) Math.ceil(millis.size() * 0.95) - 1);
        assertThat(p95).as("p95 of 20 snapshots with 50 doctors, ms: " + millis).isLessThan(2000);
    }

    // ---------------------------------------------------------------- the partial indexes

    @Test
    void theToBookQueryAndTheDateRangeQueryCanUseTheirPartialIndexes() throws Exception {
        Clinic clinic = newClinic();
        String followUpPlan = explain(clinic, "select id from opd.follow_up where returned_visit_id is null and appointment_id is null "
                + "and not_needed_at is null and due_date <= current_date order by due_date");
        assertThat(followUpPlan).contains("ix_follow_up_tenant_due_to_book");
        // On an empty table the planner prefers the tenant key for the appointment range query, so its plan cannot show the
        // partial index; the index itself is checked instead: it exists, is valid and has the active-status predicate.
        String def = scalar(clinic.tenantId(), "select indexdef from pg_indexes where indexname = 'ix_appointment_tenant_date_active'");
        assertThat(def).contains("(tenant_id, appt_date)").containsIgnoringCase("status <> 'CANCELLED'");
        assertThat(scalar(clinic.tenantId(), "select i.indisvalid::text from pg_index i join pg_class c on c.oid = i.indexrelid "
                + "where c.relname = 'ix_appointment_tenant_date_active'")).isEqualTo("true");
    }

    private String explain(Clinic clinic, String sql) throws Exception {
        return db().<String>inTenant(clinic.tenantId(), c -> {
            try (java.sql.Statement s = c.createStatement()) {
                s.execute("set local enable_seqscan = off");
                StringBuilder plan = new StringBuilder();
                try (java.sql.ResultSet rs = s.executeQuery("explain " + sql)) {
                    while (rs.next()) {
                        plan.append(rs.getString(1)).append('\n');
                    }
                }
                return plan.toString();
            }
        });
    }
}
