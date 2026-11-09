package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.ipd.domain.DoseSlots;
import com.sahaj.ipd.domain.MedicineRound;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-35 ADR-012 D1 invariant: at the same instant, {@code dosesDue + dosesLate} of the Medicine round equals the sum of
 * the MAR tab counts ({@code Mar.doseCount}) over the admitted admissions. Run for every half minute of an IST day over
 * a mix of frequencies, a mid-day check, a Pending, a Stopped and a Rejected order, recorded doses and a discharged
 * patient. The round reads through the in-memory fakes of the SAH-32 tests, so the two sides share nothing but
 * {@link DoseSlots}. No database.
 */
class MedicineRoundInvariantTest extends DoseTestBase {

    private static final Instant IST_MIDNIGHT = Instant.parse("2026-11-10T18:30:00Z");
    private static final Instant MID_DAY_CHECK = Instant.parse("2026-11-11T04:30:00Z");
    private static final UUID SECOND_ADMISSION = UUID.randomUUID();
    private static final UUID DISCHARGED_ADMISSION = UUID.randomUUID();

    private MedicineRoundReader reader;

    @org.junit.jupiter.api.BeforeEach
    void roundSetUp() {
        Set<UUID> admitted = Set.of(ADMISSION, SECOND_ADMISSION);
        MedicineRoundRepository repository = new MedicineRoundRepository() {
            @Override
            public List<ActiveOrder> findActiveOfAdmitted(UUID tenantId) {
                List<ActiveOrder> found = new ArrayList<>();
                for (OrderRow o : fake.rows.values()) {
                    if (admitted.contains(o.admissionId()) && MedicineOrderRepository.ACTIVE.equals(o.status())) {
                        found.add(new ActiveOrder(o.id(), o.admissionId(), UUID.randomUUID(), "B-" + o.admissionId(),
                                o.drugKey(), o.frequencyKey(), o.verifiedAt()));
                    }
                }
                return found;
            }

            @Override
            public List<TodayDose> findDosesBetween(UUID tenantId, Collection<UUID> admissionIds, Instant from,
                                                    Instant to) {
                List<TodayDose> found = new ArrayList<>();
                for (DoseRepository.DoseRow d : doses.rows) {
                    if (admissionIds.contains(d.admissionId()) && !d.slotAt().isBefore(from) && d.slotAt().isBefore(to)) {
                        found.add(new TodayDose(d.orderId(), d.slotAt(), d.outcome()));
                    }
                }
                return found;
            }
        };
        reader = new MedicineRoundReader(repository, catalogue, new SkippedOrderWarningsTest.MutableClock());
    }

    private void seedWard() {
        for (UUID admission : new UUID[] {ADMISSION, SECOND_ADMISSION, DISCHARGED_ADMISSION}) {
            fake.seedAt(admission, "pcm", "od", MedicineOrderRepository.ACTIVE, DOCTOR, CHECKED_LONG_AGO);
            fake.seedAt(admission, "ns", "q8", MedicineOrderRepository.ACTIVE, DOCTOR, CHECKED_LONG_AGO);
        }
        OrderRow tds = fake.seedAt(ADMISSION, "panto", "tds", MedicineOrderRepository.ACTIVE, DOCTOR, CHECKED_LONG_AGO);
        OrderRow bd = fake.seedAt(SECOND_ADMISSION, "hep", "bd", MedicineOrderRepository.ACTIVE, DOCTOR,
                CHECKED_LONG_AGO);
        fake.seedAt(ADMISSION, "ondan", "tds", MedicineOrderRepository.ACTIVE, DOCTOR, MID_DAY_CHECK);
        fake.seedAt(SECOND_ADMISSION, "met", "bd", MedicineOrderRepository.ACTIVE, DOCTOR, MID_DAY_CHECK);
        fake.seedAt(ADMISSION, "asp", "tds", MedicineOrderRepository.PENDING, DOCTOR, null);
        fake.seedAt(ADMISSION, "ibu", "tds", MedicineOrderRepository.STOPPED, DOCTOR, CHECKED_LONG_AGO);
        fake.seedAt(SECOND_ADMISSION, "ceft", "tds", MedicineOrderRepository.REJECTED, DOCTOR, null);
        doses.seed(ADMISSION, tds.id(), EIGHT_AM, "GIVEN", null, NURSE, null, false);
        doses.seed(SECOND_ADMISSION, bd.id(), EIGHT_PM, "HELD", "NIL_BY_MOUTH", NURSE, null, false);
        doses.seed(ADMISSION, tds.id(), TWO_PM, "REFUSED", "PATIENT_REFUSED", NURSE, null, false);
    }

    private int marSum(Instant asOf) {
        fake.now = asOf;
        int sum = marService.mar(TENANT, NURSE, "NURSE", ADMISSION).doseCount();
        sum += marAssembler.doseCount(TENANT, SECOND_ADMISSION, true, asOf);
        sum += marAssembler.doseCount(TENANT, DISCHARGED_ADMISSION, false, asOf);
        return sum;
    }

    @Test
    void dosesDuePlusDosesLateEqualsTheSumOfTheMarCountsAtEveryHalfMinuteOfAnIstDay() {
        seedWard();
        int nonZero = 0;
        for (Instant asOf = IST_MIDNIGHT; asOf.isBefore(IST_MIDNIGHT.plus(Duration.ofHours(24)));
             asOf = asOf.plusSeconds(30)) {
            MedicineRound.Result round = reader.read(TENANT, asOf);

            assertThat(round.due() + round.late()).as(asOf.toString()).isEqualTo(marSum(asOf));
            assertThat(round.tokenLines()).hasSize(round.due() + round.late());
            nonZero += round.due() + round.late() > 0 ? 1 : 0;
        }
        // the loop really exercised the rule: doses are due or late for a large part of the day
        assertThat(nonZero).isGreaterThan(1000);
    }

    @Test
    void theInvariantHoldsAtTheEdgesOfEverySlotOfEveryFrequency() {
        seedWard();
        for (Instant slot : new Instant[] {Instant.parse("2026-11-11T00:30:00Z"), EIGHT_AM, TWO_PM, EIGHT_PM,
            Instant.parse("2026-11-11T16:30:00Z")}) {
            for (long seconds : new long[] {-3661, -3660, -3659, -3601, -3600, -3599, -1, 0, 1, 3599, 3600, 3601, 3659,
                3660, 3661}) {
                Instant asOf = slot.plusSeconds(seconds);
                MedicineRound.Result round = reader.read(TENANT, asOf);

                assertThat(round.due() + round.late()).as(asOf.toString()).isEqualTo(marSum(asOf));
            }
        }
    }

    @Test
    void aDischargedPatientPendingStoppedAndRejectedOrdersAreNeverInTheRound() {
        seedWard();
        MedicineRound.Result round = reader.read(TENANT, Instant.parse("2026-11-11T16:00:00Z"));

        // 9:30 PM IST: the 8 PM slots are late and the 10 PM q8 slots are due; nothing of the discharged patient
        assertThat(round.rows()).extracting(MedicineRound.Entry::admissionId)
                .doesNotContain(DISCHARGED_ADMISSION);
        assertThat(round.rows()).extracting(MedicineRound.Entry::drugName)
                .doesNotContain("Tab. Aspirin 75 mg", "Tab. Ibuprofen 400 mg", "Inj. Ceftriaxone 1 g");
    }
}
