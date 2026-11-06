package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * SAH-11 security review M3: two bookings of the same doctor slot fired at the same moment against the real
 * database. The slot lock makes the second request see the first booking, so with overbook off exactly one
 * succeeds and the other gets the slot-taken 409; with overbook on both succeed.
 */
@OnTestDatabase
class AppointmentDoubleBookingIT extends OpdJourneySupport {

    private static final String APPTS = "/api/v1/opd/appointments";
    private static final String RULES = "/api/v1/opd/booking-rules";
    private static final LocalDate DAY = LocalDate.now().plusDays(4);
    private static final int ROUNDS = 5;

    private void setOverbook(Clinic clinic, boolean overbook) throws Exception {
        mvc.perform(put(RULES).with(clinic.owner()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"closeBeforeHours\":3,\"remindBeforeHours\":4,\"noShowAfterMinutes\":20,"
                                + "\"followPriority\":false,\"selfBook\":true,\"overbook\":" + overbook + "}"))
                .andExpect(status().isOk());
    }

    private Callable<Integer> booking(Clinic clinic, UUID patient, String time, CountDownLatch start) {
        String body = "{\"patientId\":\"" + patient + "\",\"doctorMembershipId\":\"" + clinic.doctorMembershipId()
                + "\",\"apptDate\":\"" + DAY + "\",\"apptTime\":\"" + time + "\",\"visitType\":\"NEW\","
                + "\"source\":\"FRONT_DESK\",\"note\":\"Fever\"}";
        return () -> {
            start.await();
            return mvc.perform(post(APPTS).with(clinic.as("FRONT_DESK")).contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andReturn().getResponse().getStatus();
        };
    }

    private List<Integer> raceTwo(Clinic clinic, String time) throws Exception {
        UUID first = newPatient(clinic);
        UUID second = newPatient(clinic);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            futures.add(pool.submit(booking(clinic, first, time, start)));
            futures.add(pool.submit(booking(clinic, second, time, start)));
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoParallelBookingsOfOneSlotWithOverbookOffGiveExactlyOneSuccessAndOneSlotTaken409() throws Exception {
        Clinic clinic = newClinic();
        setOverbook(clinic, false);
        for (int i = 0; i < ROUNDS; i++) {
            String time = String.format("%02d:00", 9 + i);
            List<Integer> statuses = raceTwo(clinic, time);
            assertThat(statuses).as("round " + i).containsExactlyInAnyOrder(201, 409);
        }
        JsonNode day = getJson(APPTS + "?date=" + DAY, clinic.owner());
        assertThat(day).hasSize(ROUNDS);
    }

    @Test
    void twoParallelBookingsOfOneSlotWithOverbookOnBothSucceed() throws Exception {
        Clinic clinic = newClinic();
        setOverbook(clinic, true);
        for (int i = 0; i < ROUNDS; i++) {
            String time = String.format("%02d:30", 9 + i);
            assertThat(raceTwo(clinic, time)).as("round " + i).containsExactly(201, 201);
        }
        assertThat(getJson(APPTS + "?date=" + DAY, clinic.owner())).hasSize(ROUNDS * 2);
    }
}
