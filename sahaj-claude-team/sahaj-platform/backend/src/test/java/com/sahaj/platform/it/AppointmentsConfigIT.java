package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-11 against the real database: per-doctor slot settings, booking rules, booking, double-booking,
 * cancel and rebook, no-show, "Create token" and tenant isolation.
 */
@OnTestDatabase
class AppointmentsConfigIT extends OpdJourneySupport {

    private static final String SLOTS = "/api/v1/opd/doctor-slots";
    private static final String RULES = "/api/v1/opd/booking-rules";
    private static final String APPTS = "/api/v1/opd/appointments";
    private static final LocalDate DAY = LocalDate.now().plusDays(3);

    private static String rulesBody(boolean overbook) {
        return "{\"closeBeforeHours\":3,\"remindBeforeHours\":4,\"noShowAfterMinutes\":20,"
                + "\"followPriority\":false,\"selfBook\":true,\"overbook\":" + overbook + "}";
    }

    private static String slotBody(int minutes, String sessionsJson) {
        return "{\"slotMinutes\":" + minutes + ",\"workingDays\":[1,2,3,4,5],\"walkInEveryNth\":4,"
                + "\"maxOnlinePerDay\":10,\"sessions\":" + sessionsJson + "}";
    }

    private static String bookBody(UUID patientId, UUID doctorId, String time) {
        return "{\"patientId\":\"" + patientId + "\",\"doctorMembershipId\":\"" + doctorId + "\",\"apptDate\":\""
                + DAY + "\",\"apptTime\":\"" + time + "\",\"visitType\":\"NEW\",\"source\":\"FRONT_DESK\","
                + "\"note\":\"Fever\"}";
    }

    private ResultActions book(RequestPostProcessor auth, UUID patientId, UUID doctorId, String time) throws Exception {
        return mvc.perform(post(APPTS).with(auth).contentType(MediaType.APPLICATION_JSON)
                .content(bookBody(patientId, doctorId, time)));
    }

    private UUID bookOk(Clinic clinic, UUID patientId, String time) throws Exception {
        MvcResult r = book(clinic.as("FRONT_DESK"), patientId, clinic.doctorMembershipId(), time)
                .andExpect(status().isCreated()).andReturn();
        return readId(r);
    }

    private ResultActions setStatus(RequestPostProcessor auth, UUID id, String status) throws Exception {
        return mvc.perform(patch(APPTS + "/" + id).with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\"}"));
    }

    private JsonNode doctorSlots(Clinic clinic) throws Exception {
        for (JsonNode n : getJson(SLOTS, clinic.owner())) {
            if (n.get("doctorMembershipId").asText().equals(clinic.doctorMembershipId().toString())) {
                return n;
            }
        }
        throw new AssertionError("doctor not listed");
    }

    // ---- doctor slot settings ----

    @Test
    void doctorSlotsRoundTripLazyDefaultsThenSavedValuesWithTwoSessions() throws Exception {
        Clinic clinic = newClinic();
        JsonNode defaults = doctorSlots(clinic);
        assertThat(defaults.get("slotMinutes").asInt()).isEqualTo(15);
        assertThat(defaults.get("walkInEveryNth").asInt()).isZero();
        assertThat(defaults.get("sessions")).hasSize(1);
        assertThat(defaults.get("sessions").get(0).get("startTime").asText()).isEqualTo("09:00");
        assertThat(defaults.get("sessions").get(0).get("endTime").asText()).isEqualTo("13:00");
        assertThat(defaults.get("workingDays")).hasSize(7);

        mvc.perform(put(SLOTS + "/" + clinic.doctorMembershipId()).with(clinic.as("FRONT_DESK"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotBody(20, "[{\"startTime\":\"09:30\",\"endTime\":\"12:30\"},"
                                + "{\"startTime\":\"16:00\",\"endTime\":\"19:00\"}]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotMinutes").value(20));

        JsonNode saved = doctorSlots(clinic);
        assertThat(saved.get("slotMinutes").asInt()).isEqualTo(20);
        assertThat(saved.get("walkInEveryNth").asInt()).isEqualTo(4);
        assertThat(saved.get("maxOnlinePerDay").asInt()).isEqualTo(10);
        assertThat(saved.get("workingDays")).hasSize(5);
        assertThat(saved.get("sessions")).hasSize(2);
        assertThat(saved.get("sessions").get(0).get("startTime").asText()).isEqualTo("09:30");
        assertThat(saved.get("sessions").get(1).get("startTime").asText()).isEqualTo("16:00");
        assertThat(saved.get("sessions").get(1).get("endTime").asText()).isEqualTo("19:00");

        // Saving again with one session replaces the sessions wholesale (delete + insert on a real table).
        mvc.perform(put(SLOTS + "/" + clinic.doctorMembershipId()).with(clinic.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotBody(10, "[{\"startTime\":\"10:00\",\"endTime\":\"11:00\"}]")))
                .andExpect(status().isOk());
        JsonNode again = doctorSlots(clinic);
        assertThat(again.get("slotMinutes").asInt()).isEqualTo(10);
        assertThat(again.get("sessions")).hasSize(1);
    }

    @Test
    void doctorSlotsRejectBadInputWith400AndUnknownDoctorWith404() throws Exception {
        Clinic clinic = newClinic();
        String ok = "[{\"startTime\":\"09:00\",\"endTime\":\"12:00\"}]";
        String[] bad = {
                slotBody(2, ok),
                slotBody(90, ok),
                slotBody(15, "[{\"startTime\":\"12:00\",\"endTime\":\"09:00\"}]"),
                slotBody(15, "[]"),
                slotBody(15, "[{\"startTime\":\"09:00\",\"endTime\":\"10:00\"},{\"startTime\":\"11:00\",\"endTime\":\"12:00\"},"
                        + "{\"startTime\":\"14:00\",\"endTime\":\"15:00\"}]"),
                slotBody(15, "[{\"startTime\":\"nine\",\"endTime\":\"12:00\"}]"),
                "{\"slotMinutes\":15,\"workingDays\":[9],\"walkInEveryNth\":0,\"maxOnlinePerDay\":0,\"sessions\":" + ok + "}"
        };
        for (String body : bad) {
            mvc.perform(put(SLOTS + "/" + clinic.doctorMembershipId()).with(clinic.owner())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(400));
        }
        mvc.perform(put(SLOTS + "/" + UUID.randomUUID()).with(clinic.owner())
                        .contentType(MediaType.APPLICATION_JSON).content(slotBody(15, ok)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    @Test
    void doctorSlotsWriteIsForOwnerAndFrontDeskOnly() throws Exception {
        Clinic clinic = newClinic();
        String body = slotBody(15, "[{\"startTime\":\"09:00\",\"endTime\":\"12:00\"}]");
        for (String role : new String[] {"NURSE", "DOCTOR", "BILLING"}) {
            mvc.perform(put(SLOTS + "/" + clinic.doctorMembershipId()).with(clinic.as(role))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
            mvc.perform(get(SLOTS).with(clinic.as(role))).andExpect(status().isOk());
        }
    }

    // ---- booking rules ----

    @Test
    void bookingRulesLazyDefaultThenOwnerSavesAndFrontDeskGets403() throws Exception {
        Clinic clinic = newClinic();
        JsonNode defaults = getJson(RULES, clinic.as("FRONT_DESK"));
        assertThat(defaults.get("closeBeforeHours").asInt()).isEqualTo(1);
        assertThat(defaults.get("remindBeforeHours").asInt()).isEqualTo(2);
        assertThat(defaults.get("noShowAfterMinutes").asInt()).isEqualTo(15);
        assertThat(defaults.get("followPriority").asBoolean()).isTrue();
        assertThat(defaults.get("selfBook").asBoolean()).isFalse();
        assertThat(defaults.get("overbook").asBoolean()).isFalse();

        mvc.perform(put(RULES).with(clinic.as("FRONT_DESK")).contentType(MediaType.APPLICATION_JSON)
                        .content(rulesBody(true)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
        assertThat(getJson(RULES, clinic.owner()).get("overbook").asBoolean()).isFalse();

        mvc.perform(put(RULES).with(clinic.owner()).contentType(MediaType.APPLICATION_JSON).content(rulesBody(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closeBeforeHours").value(3));
        JsonNode saved = getJson(RULES, clinic.as("NURSE"));
        assertThat(saved.get("closeBeforeHours").asInt()).isEqualTo(3);
        assertThat(saved.get("noShowAfterMinutes").asInt()).isEqualTo(20);
        assertThat(saved.get("followPriority").asBoolean()).isFalse();
        assertThat(saved.get("selfBook").asBoolean()).isTrue();
        assertThat(saved.get("overbook").asBoolean()).isTrue();

        // A second save updates the one row (upsert on a real table).
        mvc.perform(put(RULES).with(clinic.owner()).contentType(MediaType.APPLICATION_JSON).content(rulesBody(false)))
                .andExpect(status().isOk());
        assertThat(getJson(RULES, clinic.owner()).get("overbook").asBoolean()).isFalse();
    }

    @Test
    void bookingRulesRejectOutOfRangeValuesWith400() throws Exception {
        Clinic clinic = newClinic();
        String[] bad = {
                "{\"closeBeforeHours\":99,\"remindBeforeHours\":2,\"noShowAfterMinutes\":15,\"followPriority\":true,\"selfBook\":false,\"overbook\":false}",
                "{\"closeBeforeHours\":1,\"remindBeforeHours\":2,\"noShowAfterMinutes\":500,\"followPriority\":true,\"selfBook\":false,\"overbook\":false}",
                "{\"closeBeforeHours\":1,\"remindBeforeHours\":2,\"noShowAfterMinutes\":15,\"selfBook\":false,\"overbook\":false}",
                "{\"closeBeforeHours\":-1,\"remindBeforeHours\":2,\"noShowAfterMinutes\":15,\"followPriority\":true,\"selfBook\":false,\"overbook\":false}"
        };
        for (String body : bad) {
            mvc.perform(put(RULES).with(clinic.owner()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
        }
    }

    // ---- appointments ----

    @Test
    void bookDoubleBookCancelRebookNoShowAndCreateTokenAgainstTheRealDatabase() throws Exception {
        Clinic clinic = newClinic();
        UUID p1 = newPatient(clinic);
        UUID p2 = newPatient(clinic);
        UUID p3 = newPatient(clinic);

        // Book.
        MvcResult booked = book(clinic.as("FRONT_DESK"), p1, clinic.doctorMembershipId(), "10:00")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("BOOKED"))
                .andExpect(jsonPath("$.apptTime").value("10:00"))
                .andReturn();
        UUID first = readId(booked);
        JsonNode visitField = json(booked).path("visitId");
        assertThat(visitField.isNull() || visitField.isMissingNode()).isTrue();

        // It appears on the day's board, optionally filtered by doctor.
        assertThat(getJson(APPTS + "?date=" + DAY, clinic.as("NURSE"))).hasSize(1);
        assertThat(getJson(APPTS + "?date=" + DAY + "&doctorMembershipId=" + clinic.doctorMembershipId(),
                clinic.owner())).hasSize(1);
        assertThat(getJson(APPTS + "?date=" + DAY + "&doctorMembershipId=" + UUID.randomUUID(), clinic.owner()))
                .isEmpty();

        // Double-book the same slot with overbook off (default): refused.
        book(clinic.as("FRONT_DESK"), p2, clinic.doctorMembershipId(), "10:00")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "appointment-slot-taken"));

        // Cancel it and the freed slot can be rebooked (partial unique index).
        setStatus(clinic.as("FRONT_DESK"), first, "CANCELLED").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        UUID rebooked = bookOk(clinic, p2, "10:00");
        assertThat(rebooked).isNotEqualTo(first);
        // A cancelled appointment cannot be changed again.
        setStatus(clinic.owner(), first, "NOSHOW").andExpect(status().isBadRequest());

        // No-show.
        UUID second = bookOk(clinic, p3, "10:15");
        setStatus(clinic.owner(), second, "NOSHOW").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOSHOW"));
        // A no-show cannot take a token.
        mvc.perform(post(APPTS + "/" + second + "/token").with(clinic.owner()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));

        // Create token on the rebooked appointment: real visit, linked, ARRIVED, token number.
        MvcResult tokened = mvc.perform(post(APPTS + "/" + rebooked + "/token").with(clinic.as("FRONT_DESK")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"))
                .andReturn();
        JsonNode t = json(tokened);
        assertThat(t.get("tokenNo").asInt()).isPositive();
        UUID visitId = UUID.fromString(t.get("visitId").asText());
        JsonNode today = getJson("/api/v1/opd/visits/today", clinic.owner());
        boolean found = false;
        for (JsonNode v : today) {
            if (v.get("id").asText().equals(visitId.toString())) {
                found = true;
                assertThat(v.get("tokenNo").asInt()).isEqualTo(t.get("tokenNo").asInt());
                assertThat(v.get("patientId").asText()).isEqualTo(p2.toString());
            }
        }
        assertThat(found).as("the linked visit is on today's queue").isTrue();

        // A second token for the same appointment is refused; an arrived one cannot be cancelled.
        mvc.perform(post(APPTS + "/" + rebooked + "/token").with(clinic.owner())).andExpect(status().isConflict());
        setStatus(clinic.owner(), rebooked, "CANCELLED").andExpect(status().isBadRequest());
    }

    /** A slot whose earlier appointment was marked no-show is free to book again. */
    @Test
    void aNoShowSlotCanBeBookedAgain() throws Exception {
        Clinic clinic = newClinic();
        UUID p1 = newPatient(clinic);
        UUID p2 = newPatient(clinic);
        UUID first = bookOk(clinic, p1, "12:00");
        setStatus(clinic.owner(), first, "NOSHOW").andExpect(status().isOk());
        book(clinic.owner(), p2, clinic.doctorMembershipId(), "12:00").andExpect(status().isCreated());
    }

    /** Overbook ON must let OWNER / FRONT_DESK double-book (SAH-11 AC2). */
    @Test
    void overbookOnLetsOwnerAndFrontDeskDoubleBookASlot() throws Exception {
        Clinic clinic = newClinic();
        UUID p1 = newPatient(clinic);
        UUID p2 = newPatient(clinic);
        mvc.perform(put(RULES).with(clinic.owner()).contentType(MediaType.APPLICATION_JSON).content(rulesBody(true)))
                .andExpect(status().isOk());
        bookOk(clinic, p1, "11:00");
        book(clinic.as("FRONT_DESK"), p2, clinic.doctorMembershipId(), "11:00").andExpect(status().isCreated());
        assertThat(getJson(APPTS + "?date=" + DAY, clinic.owner())).hasSize(2);
    }

    @Test
    void bookingValidationAndRoles() throws Exception {
        Clinic clinic = newClinic();
        UUID patient = newPatient(clinic);
        UUID doc = clinic.doctorMembershipId();

        // Roles: only OWNER / FRONT_DESK write; any clinic role reads.
        for (String role : new String[] {"NURSE", "DOCTOR", "BILLING"}) {
            book(clinic.as(role), patient, doc, "09:00").andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
            setStatus(clinic.as(role), UUID.randomUUID(), "CANCELLED").andExpect(status().isForbidden());
            mvc.perform(post(APPTS + "/" + UUID.randomUUID() + "/token").with(clinic.as(role)))
                    .andExpect(status().isForbidden());
            mvc.perform(get(APPTS).param("date", DAY.toString()).with(clinic.as(role))).andExpect(status().isOk());
        }
        mvc.perform(get(APPTS).param("date", DAY.toString())).andExpect(status().isUnauthorized());

        // 400s with problem details.
        String[] bad = {
                bookBody(patient, doc, "25:99"),
                bookBody(patient, doc, "").replace("\"apptTime\":\"\"", "\"apptTime\":null"),
                bookBody(patient, doc, "09:00").replace("NEW", "WALKIN"),
                bookBody(patient, doc, "09:00").replace("FRONT_DESK", "CARRIER_PIGEON"),
                bookBody(patient, doc, "09:00").replace("\"apptDate\":\"" + DAY + "\",", ""),
        };
        for (String body : bad) {
            mvc.perform(post(APPTS).with(clinic.owner()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(400));
        }
        mvc.perform(get(APPTS).with(clinic.owner())).andExpect(status().isBadRequest());

        // 404s: unknown patient, unknown doctor, a non-doctor membership is not a doctor.
        book(clinic.owner(), UUID.randomUUID(), doc, "09:00").andExpect(status().isNotFound());
        book(clinic.owner(), patient, UUID.randomUUID(), "09:00").andExpect(status().isNotFound());
        setStatus(clinic.owner(), UUID.randomUUID(), "CANCELLED").andExpect(status().isNotFound());
        setStatus(clinic.owner(), UUID.randomUUID(), "ARRIVED").andExpect(status().isBadRequest());
    }

    @Test
    void anotherTenantCannotReadPatchOrTokenAnAppointment() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        UUID patient = newPatient(a);
        UUID appt = bookOk(a, patient, "10:00");

        assertThat(getJson(APPTS + "?date=" + DAY, b.owner())).isEmpty();
        setStatus(b.owner(), appt, "CANCELLED").andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
        mvc.perform(post(APPTS + "/" + appt + "/token").with(b.owner())).andExpect(status().isNotFound());

        // B cannot book A's patient or A's doctor either.
        UUID bPatient = newPatient(b);
        book(b.owner(), patient, b.doctorMembershipId(), "10:00").andExpect(status().isNotFound());
        book(b.owner(), bPatient, a.doctorMembershipId(), "10:00").andExpect(status().isNotFound());

        // B cannot read A's slot settings or rules.
        for (JsonNode n : getJson(SLOTS, b.owner())) {
            assertThat(n.get("doctorMembershipId").asText()).isNotEqualTo(a.doctorMembershipId().toString());
        }
        mvc.perform(put(SLOTS + "/" + a.doctorMembershipId()).with(b.owner()).contentType(MediaType.APPLICATION_JSON)
                        .content(slotBody(15, "[{\"startTime\":\"09:00\",\"endTime\":\"12:00\"}]")))
                .andExpect(status().isNotFound());
        mvc.perform(put(RULES).with(a.owner()).contentType(MediaType.APPLICATION_JSON).content(rulesBody(true)))
                .andExpect(status().isOk());
        assertThat(getJson(RULES, b.owner()).get("overbook").asBoolean()).isFalse();

        // A's appointment is untouched.
        assertThat(getJson(APPTS + "?date=" + DAY, a.owner()).get(0).get("status").asText()).isEqualTo("BOOKED");

        // Row level security at the database level.
        long seen = db().inTenant(b.tenantId(), c -> DbAccess.count(c,
                "select count(*) from opd.appointment where id = ?", appt));
        assertThat(seen).isZero();
    }
}
