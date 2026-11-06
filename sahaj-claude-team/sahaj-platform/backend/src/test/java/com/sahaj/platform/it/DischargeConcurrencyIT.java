package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-27 things that happen at the same moment, on the real {@code sahaj_test} database: two people (or four taps)
 * discharging the same patient, a sign-off racing the discharge, and a deposit, a charge or a bed move racing the
 * discharge. In every case one side wins, the other gets the 409 the contract names, nothing is doubled, no answer is
 * a 500 and the tables agree with each other. Test plan: docs/test-plans/SAH-27.md.
 */
@OnTestDatabase
class DischargeConcurrencyIT extends DischargeSupport {

    private record Resp(int status, String body) {
    }

    private record Req(HttpMethod method, String url, RequestPostProcessor auth, String body) {
    }

    private Callable<Resp> task(CountDownLatch start, Req r) {
        return () -> {
            start.await();
            var res = mvc.perform(request(r.method(), URI.create(r.url())).with(r.auth())
                    .contentType(MediaType.APPLICATION_JSON).content(r.body())).andReturn().getResponse();
            return new Resp(res.getStatus(), res.getContentAsString());
        };
    }

    /** Starts all requests at the same moment; fails when one does not finish in a minute. */
    private List<Resp> parallel(Req... requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests.length);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Resp>> futures = new ArrayList<>();
            for (Req r : requests) {
                futures.add(pool.submit(task(start, r)));
            }
            start.countDown();
            List<Resp> out = new ArrayList<>();
            for (Future<Resp> f : futures) {
                Resp resp = f.get(60, TimeUnit.SECONDS);
                assertThat(resp.status()).as("no answer is a server error: " + resp.body()).isLessThan(500);
                out.add(resp);
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private String typeOf(Resp r) throws Exception {
        return objectMapper.readTree(r.body()).get("type").asText();
    }

    private Req discharge(RequestPostProcessor auth, String id, long total, String mode) {
        return new Req(HttpMethod.POST, dischargeUrl(id), auth, dischargeBody("normal", "general", total, mode));
    }

    // ---------------- two discharges ----------------

    @Test
    void twoPeopleDischargingTheSamePatientAtOnceGiveOne200AndOne409NotAdmittedAndNothingIsDoubled() throws Exception {
        Clinic clinic = hospital();
        LocalDate day = istToday();
        for (int round = 1; round <= 3; round++) {
            String id = admitGeneral(clinic, 10).get("id").asText();          // 16,000 against 10,000: 6,000 to collect
            signNormal(clinic, id);
            UUID bed = bedOf(clinic, id);
            RequestPostProcessor second = otherPerson(clinic, "OWNER");
            List<Resp> answers = parallel(discharge(clinic.owner(), id, 1_600_000L, "UPI"),
                    discharge(second, id, 1_600_000L, "Cash"));
            assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");

            assertThat(List.of(answers.get(0).status(), answers.get(1).status())).as("round " + round)
                    .containsExactlyInAnyOrder(200, 409);
            Resp loser = answers.get(0).status() == 409 ? answers.get(0) : answers.get(1);
            assertThat(typeOf(loser)).endsWith("not-admitted");
            assertThat(objectMapper.readTree(loser.body()).get("detail").asText()).isEqualTo(NOT_ADMITTED_DISCHARGE);
            assertThat(count(clinic, "select count(*) from ipd.final_bill where admission_id = ?::uuid", id)).isOne();
            assertThat(count(clinic, "select count(*) from ipd.discharge where admission_id = ?::uuid", id)).isOne();
            assertThat(storedLines(clinic, id)).hasSize(3);
            assertThat(count(clinic, "select count(*) from opd.follow_up where source_admission_id = ?::uuid", UUID.fromString(id)))
                    .isOne();
            assertThat(values(clinic, "select balance_paise::text from ipd.final_bill where admission_id = ?::uuid", id))
                    .containsExactly("600000");
            assertThat(bedStatus(clinic, bed)).isEqualTo("clean");
            assertThat(values(clinic, "select status from ipd.admission where id = ?::uuid", id)).containsExactly("DISCHARGED");
        }
        assertThat(audits(clinic, "IPD_DISCHARGED")).isEqualTo(3);
        assertThat(audits(clinic, "FOLLOW_UP_CREATED")).isEqualTo(3);
        assertThat(rows(clinic, "final_bill")).isEqualTo(3);
    }

    @Test
    void fourTapsOfTheSameDischargeGiveOne200AndThree409() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        signNormal(clinic, id);
        List<Resp> answers = parallel(discharge(clinic.owner(), id, DAY, "UPI"), discharge(clinic.owner(), id, DAY, "UPI"),
                discharge(clinic.owner(), id, DAY, "UPI"), discharge(clinic.owner(), id, DAY, "UPI"));
        int ok = 0;
        for (Resp r : answers) {
            if (r.status() == 200) {
                ok++;
            } else {
                assertThat(r.status()).isEqualTo(409);
                assertThat(typeOf(r)).endsWith("not-admitted");
            }
        }
        assertThat(ok).isOne();
        assertThat(rows(clinic, "final_bill")).isOne();
        assertThat(rows(clinic, "discharge")).isOne();
        assertThat(rows(clinic, "final_bill_line")).isEqualTo(3);
        assertThat(audits(clinic, "IPD_DISCHARGED")).isOne();
        assertThat(count(clinic, "select count(*) from opd.follow_up")).isOne();
    }

    // ---------------- a sign-off racing the discharge ----------------

    @Test
    void aSignoffForAnotherChoiceRacingTheDischargeNeverLetsBothWinAndTheTablesAgree() throws Exception {
        Clinic clinic = hospital();
        for (int round = 1; round <= 5; round++) {
            String id = quickAdmit(clinic).get("id").asText();
            signNormal(clinic, id);
            List<Resp> answers = parallel(
                    new Req(HttpMethod.PUT, signoffUrl(id), clinic.as("DOCTOR"), signBody("lama", "general")),
                    discharge(clinic.owner(), id, DAY, "UPI"));
            Resp signed = answers.get(0);
            Resp discharged = answers.get(1);
            boolean dischargeWon = discharged.status() == 200;
            if (dischargeWon) {
                assertThat(signed.status()).as("round " + round).isEqualTo(409);
                assertThat(typeOf(signed)).endsWith("not-admitted");
                assertThat(values(clinic, "select discharge_type from ipd.discharge where admission_id = ?::uuid", id))
                        .containsExactly("normal");
                assertThat(count(clinic, "select count(*) from ipd.discharge_signoff where admission_id = ?::uuid", id)).isOne();
                assertThat(values(clinic, "select status from ipd.admission where id = ?::uuid", id)).containsExactly("DISCHARGED");
            } else {
                assertThat(signed.status()).as("round " + round).isEqualTo(200);
                assertThat(discharged.status()).isEqualTo(409);
                assertThat(typeOf(discharged)).endsWith("summary-not-signed");
                assertThat(count(clinic, "select count(*) from ipd.discharge where admission_id = ?::uuid", id)).isZero();
                assertThat(count(clinic, "select count(*) from ipd.discharge_signoff where admission_id = ?::uuid", id)).isEqualTo(2);
                assertThat(values(clinic, "select status from ipd.admission where id = ?::uuid", id)).containsExactly("ADMITTED");
                assertThat(bedStatus(clinic, bedOf(clinic, id))).isEqualTo("occ");
            }
        }
    }

    // ---------------- money racing the discharge ----------------

    @Test
    void aDepositRacingTheDischargeIsEitherCountedInTheFrozenBillOrRefusedNeverLost() throws Exception {
        Clinic clinic = hospital();
        for (int round = 1; round <= 4; round++) {
            String id = quickAdmit(clinic).get("id").asText();
            signNormal(clinic, id);
            List<Resp> answers = parallel(
                    new Req(HttpMethod.POST, ADMISSIONS + "/" + id + "/deposits", clinic.owner(),
                            "{\"amountPaise\":100000,\"mode\":\"Cash\"}"),
                    discharge(clinic.owner(), id, DAY, "UPI"));
            Resp dep = answers.get(0);
            Resp discharged = answers.get(1);
            long depositRows = Long.parseLong(values(clinic, "select coalesce(sum(amount_paise), 0)::text from ipd.deposit "
                    + "where admission_id = ?::uuid", id).get(0));
            // The request carries the deposits the user saw (DEPOSIT). The discharge is either first (200, the deposit
            // is refused as not-admitted) or second (409 bill-changed: the deposit moved the balance). Never a settlement
            // with a different balance than total minus the deposits recorded.
            assertThat(discharged.status()).as("round " + round).isIn(200, 409);
            if (discharged.status() == 200) {
                long stored = Long.parseLong(values(clinic, "select deposits_paise::text from ipd.final_bill "
                        + "where admission_id = ?::uuid", id).get(0));
                assertThat(stored).as("round " + round + ": frozen deposits = the deposits recorded").isEqualTo(depositRows);
                if (dep.status() == 200) {
                    assertThat(stored).isEqualTo(DEPOSIT + 100_000L);
                } else {
                    assertThat(dep.status()).isEqualTo(409);
                    assertThat(typeOf(dep)).endsWith("not-admitted");
                    assertThat(stored).isEqualTo(DEPOSIT);
                }
                assertThat(values(clinic, "select (balance_paise = total_paise - deposits_paise)::text from ipd.final_bill "
                        + "where admission_id = ?::uuid", id)).containsExactly("true");
                assertThat(values(clinic, "select balance_paise::text from ipd.final_bill where admission_id = ?::uuid", id))
                        .containsExactly(String.valueOf(DAY - stored));
            } else {
                assertThat(typeOf(discharged)).endsWith("bill-changed");
                assertThat(dep.status()).as("the deposit that made the bill change was recorded").isEqualTo(200);
                assertThat(depositRows).isEqualTo(DEPOSIT + 100_000L);
                assertThat(count(clinic, "select count(*) from ipd.final_bill where admission_id = ?::uuid", id)).isZero();
                assertThat(count(clinic, "select count(*) from ipd.discharge where admission_id = ?::uuid", id)).isZero();
                assertThat(values(clinic, "select status from ipd.admission where id = ?::uuid", id)).containsExactly("ADMITTED");
                // the reloaded sheet shows the new balance and the retry with it goes through
                JsonNode fresh = sheet(clinic, id);
                assertThat(depositsOf(fresh)).isEqualTo(depositRows);
                JsonNode d = ok(HttpMethod.POST, dischargeUrl(id), clinic.owner(),
                        dischargeBody("normal", "general", totalOf(fresh), depositsOf(fresh), "UPI"), 200);
                assertThat(d.get("finalBill").get("balancePaise").asLong()).isEqualTo(totalOf(fresh) - depositRows);
            }
        }
    }

    @Test
    void aChargeRacingTheDischargeIsEitherRefusedOrMakesTheBillChangeNeverHalfInTheFrozenBill() throws Exception {
        Clinic clinic = hospital();
        for (int round = 1; round <= 4; round++) {
            String id = quickAdmit(clinic).get("id").asText();
            signNormal(clinic, id);
            List<Resp> answers = parallel(
                    new Req(HttpMethod.POST, ADMISSIONS + "/" + id + "/charges", clinic.owner(), "{\"serviceKey\":\"cbc\"}"),
                    discharge(clinic.owner(), id, DAY, "UPI"));
            Resp charge = answers.get(0);
            Resp discharged = answers.get(1);
            long charges = count(clinic, "select count(*) from ipd.admission_charge where admission_id = ?::uuid", id);
            if (discharged.status() == 200) {
                assertThat(charge.status()).as("round " + round).isEqualTo(409);
                assertThat(typeOf(charge)).endsWith("not-admitted");
                assertThat(charges).isZero();
                assertThat(storedLines(clinic, id)).hasSize(3);
                assertThat(values(clinic, "select total_paise::text from ipd.final_bill where admission_id = ?::uuid", id))
                        .containsExactly(String.valueOf(DAY));
            } else {
                assertThat(charge.status()).isEqualTo(200);
                assertThat(discharged.status()).isEqualTo(409);
                assertThat(typeOf(discharged)).endsWith("bill-changed");
                assertThat(charges).isOne();
                assertThat(count(clinic, "select count(*) from ipd.final_bill where admission_id = ?::uuid", id)).isZero();
                assertThat(values(clinic, "select status from ipd.admission where id = ?::uuid", id)).containsExactly("ADMITTED");
            }
            if (discharged.status() == 200) {
                assertThat(values(clinic, "select (sum(l.amount_paise) = b.total_paise)::text from ipd.final_bill b "
                        + "join ipd.final_bill_line l on l.final_bill_id = b.id where b.admission_id = ?::uuid group by b.total_paise",
                        id)).containsExactly("true");
            }
        }
    }

    @Test
    void aBedMoveRacingTheDischargeLeavesNoOccupiedBedForADischargedPatient() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, false, "same");
        for (int round = 1; round <= 4; round++) {
            String id = quickAdmit(clinic).get("id").asText();
            signNormal(clinic, id);
            UUID original = bedOf(clinic, id);
            UUID spare = newBed(clinic, "gen");
            long occBefore = count(clinic, "select count(*) from ipd.bed where status = 'occ'");
            List<Resp> answers = parallel(new Req(HttpMethod.POST, transferUrl(id), clinic.owner(), moveBody(spare, null)),
                    discharge(clinic.owner(), id, DAY, "UPI"));
            Resp move = answers.get(0);
            Resp discharged = answers.get(1);
            long occAfter = count(clinic, "select count(*) from ipd.bed where status = 'occ'");
            if (discharged.status() == 200) {
                assertThat(occAfter).as("round " + round + ": the discharged patient holds no bed").isEqualTo(occBefore - 1);
                assertThat(values(clinic, "select status from ipd.admission where id = ?::uuid", id)).containsExactly("DISCHARGED");
                if (move.status() == 200) {
                    assertThat(bedOf(clinic, id)).isEqualTo(spare);
                    assertThat(bedStatus(clinic, spare)).as("the bed the patient was in when discharged").isEqualTo("clean");
                    assertThat(bedStatus(clinic, original)).isEqualTo("clean");
                } else {
                    assertThat(move.status()).isEqualTo(409);
                    assertThat(typeOf(move)).endsWith("not-admitted");
                    assertThat(bedOf(clinic, id)).isEqualTo(original);
                    assertThat(bedStatus(clinic, original)).isEqualTo("clean");
                    assertThat(bedStatus(clinic, spare)).isEqualTo("free");
                }
            } else {
                // the move came first and changed the bill (the transfer lines), so the old total no longer matches
                assertThat(move.status()).isEqualTo(200);
                assertThat(discharged.status()).isEqualTo(409);
                assertThat(typeOf(discharged)).endsWith("bill-changed");
                assertThat(occAfter).isEqualTo(occBefore);
                assertThat(bedOf(clinic, id)).isEqualTo(spare);
                assertThat(count(clinic, "select count(*) from ipd.discharge where admission_id = ?::uuid", id)).isZero();
            }
            JsonNode d = detail(clinic, id);
            assertThat(d.get("status").asText()).isEqualTo(discharged.status() == 200 ? "DISCHARGED" : "ADMITTED");
        }
    }
}
