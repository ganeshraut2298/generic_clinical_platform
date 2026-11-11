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
import com.sahaj.ipd.domain.MedicineRound;
import com.sahaj.ipd.domain.SeedCatalogue;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * SAH-35 code review R35-1: one damaged order (a drug missing from the catalogue, an unknown frequency) is left out of
 * the round with a warning that holds the clinic id and the order id only; the rest of the round is still answered.
 */
class MedicineRoundReaderTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final Instant AS_OF = Instant.parse("2026-11-11T08:00:00Z");
    private static final Instant LONG_AGO = Instant.parse("2026-11-09T05:00:00Z");
    private static final String MISSING_DRUG = "no-such-drug-xyz";

    private MedicineRoundRepository repository;
    private MedicineRoundReader reader;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        repository = mock(MedicineRoundRepository.class);
        reader = new MedicineRoundReader(repository, new MedicineCatalogue(SeedCatalogue.catalogue()),
                new SkippedOrderWarningsTest.MutableClock());
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

    private static ActiveOrder order(String drugKey, String frequencyKey) {
        return new ActiveOrder(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "204", drugKey, frequencyKey,
                LONG_AGO);
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
    void anOrderWithAnUnknownFrequencyIsSkippedAndTheRestOfTheRoundIsStillAnswered() {
        ActiveOrder good = order("hep", "tds");
        ActiveOrder broken = order("hep", "every-fortnight");
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
        active(order(MISSING_DRUG, "tds"), order("hep", "nope"));

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
        ActiveOrder badFrequency = order("hep", "every-fortnight");
        active(broken, badFrequency);

        reader.read(TENANT, AS_OF);

        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(2);
        for (ILoggingEvent event : events) {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("tenantId=" + TENANT)
                    .doesNotContain(MISSING_DRUG).doesNotContain("every-fortnight").doesNotContain("hep");
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
                new MedicineCatalogue(SeedCatalogue.catalogue()), clock);
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
                new MedicineCatalogue(SeedCatalogue.catalogue()), clock);
        active(order(MISSING_DRUG, "tds"), order("hep", "every-fortnight"));

        throttled.read(TENANT, AS_OF);
        throttled.read(TENANT, AS_OF);

        assertThat(appender.list).hasSize(2);
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
