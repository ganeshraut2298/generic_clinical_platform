package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sahaj.ipd.application.MedicineRoundRepository.ActiveOrder;
import com.sahaj.ipd.domain.EffectiveNursingSettings;
import com.sahaj.ipd.domain.MedicineRound;
import com.sahaj.ipd.domain.SeedCatalogue;
import com.sahaj.ipd.domain.order.MedicineOrderVersion;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Kind;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Verification;
import com.sahaj.ipd.domain.order.OrderPriority;
import com.sahaj.ipd.domain.order.OrderStatus;
import com.sahaj.ipd.domain.order.OrderTimeline;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * SAH-35 code review R35-1: one damaged order (a drug missing from the catalogue, no timeline) is left out of the round
 * with a warning that holds the clinic id and the order id only; the rest of the round is still answered. SAH-76: the
 * slots come from the order timelines and the settings in force, read once for every patient.
 */
class MedicineRoundReaderTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final Instant AS_OF = Instant.parse("2026-11-11T08:00:00Z");
    private static final Instant LONG_AGO = Instant.parse("2026-11-09T05:00:00Z");
    private static final String MISSING_DRUG = "no-such-drug-xyz";

    private MedicineRoundRepository repository;
    private final Map<UUID, OrderTimeline> timelineOf = new HashMap<>();
    private OrderTimelineRepository timelines;
    private NursingSettingsReader settingsReader;
    private MedicineRoundReader reader;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        repository = mock(MedicineRoundRepository.class);
        timelines = (tenantId, admissionIds) -> timelineOf;
        settingsReader = mock(NursingSettingsReader.class);
        when(settingsReader.effectiveForAll(eq(TENANT), anyCollection())).thenAnswer(call -> {
            Map<UUID, EffectiveNursingSettings> all = new HashMap<>();
            for (UUID id : call.<Collection<UUID>>getArgument(1)) {
                all.put(id, EffectiveNursingSettings.defaults());
            }
            return all;
        });
        reader = new MedicineRoundReader(repository, new MedicineCatalogue(SeedCatalogue.catalogue()),
                new SkippedOrderWarningsTest.MutableClock(), timelines, settingsReader);
        when(repository.findDosesBetween(eq(TENANT), anyCollection(), any(), any())).thenReturn(List.of());
        logger = (Logger) LoggerFactory.getLogger(MedicineRoundReader.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    /** An ACTIVE order with a verified version 1 of the frequency (an unknown or null key gives no slot). */
    private ActiveOrder order(String drugKey, String frequencyKey) {
        ActiveOrder order = new ActiveOrder(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "204", drugKey,
                "ACTIVE");
        MedicineOrderVersion v1 = new MedicineOrderVersion(order.orderId(), 1, Kind.SCHEDULED, frequencyKey, null, null,
                "ORAL", LONG_AGO, null, null, OrderPriority.ROUTINE, null, null, null, UUID.randomUUID(), LONG_AGO,
                null, Verification.REQUIRED, UUID.randomUUID(), LONG_AGO);
        timelineOf.put(order.orderId(), new OrderTimeline(order.orderId(), order.admissionId(), OrderStatus.ACTIVE,
                List.of(v1), List.of(), null));
        return order;
    }

    /** An ACTIVE order whose timeline cannot be found: a damaged row. */
    private static ActiveOrder orderWithoutTimeline() {
        return new ActiveOrder(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "204", "hep", "ACTIVE");
    }

    private void active(ActiveOrder... orders) {
        when(repository.findActiveOfAdmitted(TENANT)).thenReturn(List.of(orders));
    }

    @Test
    void anOrderWhoseDrugIsNotInTheCatalogueIsSkippedAndTheRestOfTheRoundIsStillAnswered() {
        ActiveOrder good = order("hep", "tds");
        ActiveOrder broken = order(MISSING_DRUG, "tds");
        active(broken, good);

        MedicineRound.Result result = reader.read(TENANT, AS_OF);

        assertThat(result.rows()).isNotEmpty();
        assertThat(result.rows()).allMatch(entry -> entry.orderId().equals(good.orderId()));
        assertThat(result.rows()).noneMatch(entry -> entry.orderId().equals(broken.orderId()));
        assertThat(result.tokenLines()).isNotEmpty();
    }

    @Test
    void anOrderWhoseTimelineIsMissingIsSkippedAndTheRestOfTheRoundIsStillAnswered() {
        ActiveOrder good = order("hep", "tds");
        ActiveOrder broken = orderWithoutTimeline();
        active(good, broken);

        MedicineRound.Result result = reader.read(TENANT, AS_OF);

        assertThat(result.rows()).isNotEmpty();
        assertThat(result.rows()).allMatch(entry -> entry.orderId().equals(good.orderId()));
    }

    @Test
    void aNullDrugOrFrequencyKeyIsSkippedToo() {
        active(order(null, "tds"), order("hep", null));

        assertThat(reader.read(TENANT, AS_OF).rows()).isEmpty();
    }

    @Test
    void whenEveryOrderIsDamagedTheRoundIsEmptyAndNoDoseQueryIsMade() {
        active(order(MISSING_DRUG, "tds"), orderWithoutTimeline());

        MedicineRound.Result result = reader.read(TENANT, AS_OF);

        assertThat(result).isSameAs(MedicineRound.empty());
        verify(repository, never()).findDosesBetween(any(), anyCollection(), any(), any());
    }

    @Test
    void theDoseQueryNamesOnlyTheAdmissionsOfTheOrdersThatWereKept() {
        ActiveOrder good = order("hep", "tds");
        ActiveOrder broken = order(MISSING_DRUG, "tds");
        active(good, broken);

        reader.read(TENANT, AS_OF);

        verify(repository).findDosesBetween(eq(TENANT), org.mockito.ArgumentMatchers.argThat(ids ->
                ids.size() == 1 && ids.contains(good.admissionId())), any(), any());
    }

    @Test
    void theWarningHoldsTheClinicIdAndTheOrderIdOnly() {
        ActiveOrder broken = order(MISSING_DRUG, "tds");
        ActiveOrder badFrequency = orderWithoutTimeline();
        active(broken, badFrequency);

        reader.read(TENANT, AS_OF);

        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(2);
        for (ILoggingEvent event : events) {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("tenantId=" + TENANT)
                    .doesNotContain(MISSING_DRUG).doesNotContain("hep");
        }
        assertThat(events.get(0).getFormattedMessage()).contains("orderId=" + broken.orderId());
        assertThat(events.get(1).getFormattedMessage()).contains("orderId=" + badFrequency.orderId());
    }

    @Test
    void aRoundWithNothingDamagedWritesNoWarning() {
        active(order("hep", "tds"));

        reader.read(TENANT, AS_OF);

        assertThat(appender.list).isEmpty();
    }

    // ---- release audit G-14 ----

    @Test
    void theSameDamagedOrderIsWarnedAboutOncePerTenMinutesNotOnEveryPoll() {
        SkippedOrderWarningsTest.MutableClock clock = new SkippedOrderWarningsTest.MutableClock();
        MedicineRoundReader throttled = new MedicineRoundReader(repository,
                new MedicineCatalogue(SeedCatalogue.catalogue()), clock, timelines, settingsReader);
        ActiveOrder broken = order(MISSING_DRUG, "tds");
        active(broken);

        throttled.read(TENANT, AS_OF);
        clock.advance(java.time.Duration.ofSeconds(60));
        throttled.read(TENANT, AS_OF);
        clock.advance(java.time.Duration.ofSeconds(60));
        throttled.read(TENANT, AS_OF);

        assertThat(appender.list).as("three polls, one warning").hasSize(1);

        clock.advance(java.time.Duration.ofMinutes(10));
        throttled.read(TENANT, AS_OF);

        assertThat(appender.list).as("a new warning after the quiet period").hasSize(2);
        assertThat(appender.list.get(1).getFormattedMessage()).contains("orderId=" + broken.orderId());
    }

    @Test
    void twoDamagedOrdersEachGetTheirOwnSingleWarning() {
        SkippedOrderWarningsTest.MutableClock clock = new SkippedOrderWarningsTest.MutableClock();
        MedicineRoundReader throttled = new MedicineRoundReader(repository,
                new MedicineCatalogue(SeedCatalogue.catalogue()), clock, timelines, settingsReader);
        active(order(MISSING_DRUG, "tds"), orderWithoutTimeline());

        throttled.read(TENANT, AS_OF);
        throttled.read(TENANT, AS_OF);

        assertThat(appender.list).hasSize(2);
    }

    // ---- SAH-76 ----

    @Test
    void anOrderWithAnUnknownFrequencyGivesNoSlotAndTheRestOfTheRoundIsStillAnswered() {
        ActiveOrder good = order("hep", "tds");
        active(good, order("hep", "every-fortnight"));

        assertThat(reader.read(TENANT, AS_OF).rows()).isNotEmpty().allMatch(e -> e.orderId().equals(good.orderId()));
    }

    @Test
    void theTimelinesAndTheSettingsAreReadOnceForEveryPatient() {
        active(order("hep", "tds"), order("hep", "bd"), order("hep", "od"));
        int[] reads = {0, 0};
        timelines = (tenantId, admissionIds) -> {
            reads[0]++;
            return timelineOf;
        };
        reader = new MedicineRoundReader(repository, new MedicineCatalogue(SeedCatalogue.catalogue()),
                new SkippedOrderWarningsTest.MutableClock(), timelines, settingsReader);

        reader.read(TENANT, AS_OF);

        assertThat(reads[0]).isEqualTo(1);
        verify(settingsReader).effectiveForAll(eq(TENANT), anyCollection());
    }

    @Test
    void theWindowOfTheWardProfileDecidesWhatIsDue() {
        ActiveOrder o = order("hep", "bd");
        active(o);
        // 8 PM is 7 hours ahead of AS_OF (1:30 PM IST is 08:00Z): a 30-minute window never makes it due.
        when(settingsReader.effectiveForAll(eq(TENANT), anyCollection())).thenAnswer(call -> Map.of(o.admissionId(),
                new EffectiveNursingSettings(true, 30, 30, 15, 30, 5, 10, 240)));

        MedicineRound.Result result = reader.read(TENANT, AS_OF);

        assertThat(result.rows()).extracting(e -> e.slotAt().toString()).containsExactly("2026-11-11T02:30:00Z");
        assertThat(result.rows().get(0).state().name()).isEqualTo("LATE");
    }

    @Test
    void aPendingOrderIsInTheRoundOnlyForItsStatSlotBeforeTheCheck() {
        ActiveOrder waiting = new ActiveOrder(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "204", "hep",
                "PENDING");
        MedicineOrderVersion unverified = new MedicineOrderVersion(waiting.orderId(), 1, Kind.SCHEDULED, "bd", null,
                null, "ORAL", LONG_AGO, null, null, OrderPriority.ROUTINE, null, null, null, UUID.randomUUID(),
                LONG_AGO, null, Verification.REQUIRED, null, null);
        timelineOf.put(waiting.orderId(), new OrderTimeline(waiting.orderId(), waiting.admissionId(),
                OrderStatus.PENDING, List.of(unverified), List.of(), null));
        Instant created = Instant.parse("2026-11-11T07:45:20Z");
        ActiveOrder stat = new ActiveOrder(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "205", "hep",
                "PENDING");
        MedicineOrderVersion statVersion = new MedicineOrderVersion(stat.orderId(), 1, Kind.ONCE, null, null, null, "IV",
                created, null, null, OrderPriority.STAT, null, null, null, UUID.randomUUID(), created, null,
                Verification.REQUIRED, null, null);
        timelineOf.put(stat.orderId(), new OrderTimeline(stat.orderId(), stat.admissionId(), OrderStatus.PENDING,
                List.of(statVersion), List.of(), null));
        active(waiting, stat);

        MedicineRound.Result result = reader.read(TENANT, AS_OF);

        assertThat(result.rows()).extracting(e -> e.orderId()).containsExactly(stat.orderId());
    }

    // ---- SAH-34 patient scope ----

    @Test
    void aScopedRoundHoldsOnlyTheOrdersOfTheVisibleAdmissionsAndTheCountsFollow() {
        ActiveOrder mine = order("hep", "tds");
        ActiveOrder other = order("hep", "tds");
        active(mine, other);

        MedicineRound.Result scoped = reader.read(TENANT, AS_OF, java.util.Set.of(mine.admissionId()));
        MedicineRound.Result everyone = reader.read(TENANT, AS_OF);

        assertThat(scoped.rows()).isNotEmpty().allMatch(entry -> entry.admissionId().equals(mine.admissionId()));
        assertThat(everyone.rows()).anyMatch(entry -> entry.admissionId().equals(other.admissionId()));
        assertThat(scoped.due() + scoped.late()).isLessThan(everyone.due() + everyone.late());
        assertThat(scoped.tokenLines()).hasSizeLessThan(everyone.tokenLines().size());
    }

    @Test
    void aScopeThatReachesNobodyIsTheEmptyRoundAndNoDoseQueryIsMade() {
        active(order("hep", "tds"));

        MedicineRound.Result result = reader.read(TENANT, AS_OF, java.util.Set.of());

        assertThat(result).isSameAs(MedicineRound.empty());
        verify(repository, never()).findDosesBetween(any(), anyCollection(), any(), any());
    }
}
