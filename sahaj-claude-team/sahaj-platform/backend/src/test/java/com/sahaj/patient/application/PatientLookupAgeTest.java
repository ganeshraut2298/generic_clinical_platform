package com.sahaj.patient.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sahaj.patient.api.PatientAge.Unit;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.patient.application.PatientRepository.PatientRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SAH-73 (ADR-024 D1, D2, D3): both patient ports give the same effective completed years, worked out from the date of
 * birth as of today in India, and never read the stored number of a dated patient. The clinical port carries the date and
 * the age detail; the lookup port carries neither the date nor the detail.
 */
class PatientLookupAgeTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID DATED = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID UNDATED = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    private static final UUID MERGED_AWAY = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    /** 12:15 AM on 13 Nov 2026 in India, still 12 Nov in UTC: the day is India's. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T18:45:00Z"), ZoneOffset.UTC);
    private static final LocalDate BORN = LocalDate.of(2025, 6, 10);

    private PatientRepository repository;
    private PatientLookup lookup;
    private PatientClinicalLookup clinical;

    @BeforeEach
    void setUp() {
        repository = mock(PatientRepository.class);
        lookup = new PatientLookup(repository, CLOCK);
        clinical = new PatientClinicalLookup(repository, CLOCK);
        PatientRow dated = row(DATED, 30, BORN, null);
        PatientRow undated = row(UNDATED, 34, null, null);
        PatientRow mergedAway = row(MERGED_AWAY, 2, null, DATED);
        when(repository.findById(TENANT, DATED)).thenReturn(Optional.of(dated));
        when(repository.findById(TENANT, UNDATED)).thenReturn(Optional.of(undated));
        when(repository.findById(TENANT, MERGED_AWAY)).thenReturn(Optional.of(mergedAway));
        when(repository.findByIds(eq(TENANT), anyCollection())).thenAnswer(call -> {
            java.util.Collection<UUID> ids = call.getArgument(1);
            return List.of(dated, undated, mergedAway).stream().filter(r -> ids.contains(r.id())).toList();
        });
    }

    private static PatientRow row(UUID id, int storedAge, LocalDate dateOfBirth, UUID mergedInto) {
        return new PatientRow(id, "UH-1", "Test Patient", storedAge, "F", "9000000001", "Area", "mr", null, null,
                false, mergedInto, Instant.parse("2026-11-01T00:00:00Z"), 1, dateOfBirth);
    }

    // ---- the lookup port (queue, bills, IPD lists) ----

    @Test
    void theLookupPortGivesTheEffectiveYearsFromTheDateNotTheStaleStoredAge() {
        // stored 30 (stale); born 10 Jun 2025 is 1 year on 13 Nov 2026 in India
        assertThat(lookup.find(TENANT, DATED).orElseThrow().age()).isEqualTo(1);
        assertThat(lookup.findAll(TENANT, Set.of(DATED)).get(DATED).age()).isEqualTo(1);
        assertThat(lookup.findAllCurrent(TENANT, Set.of(DATED)).get(DATED).age()).isEqualTo(1);
    }

    @Test
    void theLookupPortGivesTheStoredAgeToAnUndatedPatient() {
        assertThat(lookup.find(TENANT, UNDATED).orElseThrow().age()).isEqualTo(34);
        assertThat(lookup.findAllCurrent(TENANT, Set.of(UNDATED)).get(UNDATED).age()).isEqualTo(34);
    }

    @Test
    void aMergedAwayRecordAnswersWithTheEffectiveAgeOfTheRecordItWasMergedInto() {
        PatientSummary current = lookup.findAllCurrent(TENANT, Set.of(MERGED_AWAY)).get(MERGED_AWAY);

        assertThat(current.id()).isEqualTo(DATED);
        assertThat(current.age()).isEqualTo(1);
    }

    @Test
    void theLookupSummaryHasNoDateOfBirthAndNoAgeDetail() {
        for (java.lang.reflect.RecordComponent component : PatientSummary.class.getRecordComponents()) {
            assertThat(component.getName()).isNotIn("dateOfBirth", "ageDetail");
        }
        assertThat(lookup.find(TENANT, DATED).orElseThrow().toString()).doesNotContain("2025");
    }

    // ---- the clinical port (opd EMR, ipd) ----

    @Test
    void theClinicalPortGivesTheEffectiveYearsTheDateAndTheAgeDetailOfADatedPatient() {
        PatientClinical found = clinical.find(TENANT, DATED).orElseThrow();

        assertThat(found.age()).isEqualTo(1);
        assertThat(found.dateOfBirth()).isEqualTo(BORN);
        assertThat(found.ageDetail().unit()).isEqualTo(Unit.MONTHS);
        assertThat(found.ageDetail().value()).isEqualTo(17);
        assertThat(found.ageDetail().totalMonths()).isEqualTo(17);
        assertThat(found.ageDetail().daysAfterMonths()).isEqualTo(3);
        assertThat(found.ageDetail().fromDateOfBirth()).isTrue();
        assertThat(found.ageDetail().asOf()).as("India's day, not UTC's").isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(found.toString()).doesNotContain("2025").doesNotContain("Test Patient");
    }

    @Test
    void theClinicalPortGivesAnUndatedPatientTheStoredAgeInYearsAndNoDate() {
        PatientClinical found = clinical.find(TENANT, UNDATED).orElseThrow();

        assertThat(found.age()).isEqualTo(34);
        assertThat(found.dateOfBirth()).isNull();
        assertThat(found.ageDetail().unit()).isEqualTo(Unit.YEARS);
        assertThat(found.ageDetail().value()).isEqualTo(34);
        assertThat(found.ageDetail().fromDateOfBirth()).isFalse();
        assertThat(found.ageDetail().totalMonths()).isNull();
    }

    @Test
    void theClinicalPortFindAllAnswersTheSameAgeAsFindAndFollowsAMerge() {
        Map<UUID, PatientClinical> all = clinical.findAll(TENANT, Set.of(DATED, UNDATED, MERGED_AWAY));

        assertThat(all.get(DATED).age()).isEqualTo(clinical.find(TENANT, DATED).orElseThrow().age());
        assertThat(all.get(UNDATED).age()).isEqualTo(34);
        assertThat(all.get(MERGED_AWAY).id()).isEqualTo(DATED);
        assertThat(all.get(MERGED_AWAY).age()).isEqualTo(1);
        assertThat(all.get(MERGED_AWAY).dateOfBirth()).isEqualTo(BORN);
    }

    @Test
    void bothPortsAgreeOnTheSameDay() {
        assertThat(lookup.find(TENANT, DATED).orElseThrow().age())
                .isEqualTo(clinical.find(TENANT, DATED).orElseThrow().age());
    }
}
