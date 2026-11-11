package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.application.PatientScope.Kind;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-34 AC 1, 3, 5: the pure rules of who a scope reaches. */
class PatientScopeTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID WARD_A = UUID.randomUUID();
    private static final UUID WARD_B = UUID.randomUUID();
    private static final UUID WARD_C = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final Instant ENDS = Instant.parse("2026-11-10T06:30:00Z");

    private static PatientScope wards(Set<UUID> wardIds, Map<UUID, Instant> grants) {
        return new PatientScope(wardIds.isEmpty() ? Kind.NONE : Kind.WARDS, USER, "NURSE", "NURSE", ME, wardIds,
                grants);
    }

    @Test
    void allReachesEveryone() {
        PatientScope all = PatientScope.all();
        assertThat(all.isAll()).isTrue();
        assertThat(all.allows(WARD_A, DOCTOR, ADMISSION)).isTrue();
        assertThat(all.allows(null, null, ADMISSION)).isTrue();
        assertThat(all.grantOnlyExpiry(WARD_A, DOCTOR, ADMISSION)).isNull();
    }

    @Test
    void wardsReachesOnlyTheNursesLiveWardsAndEveryWardCounts() {
        PatientScope scope = wards(Set.of(WARD_A, WARD_B), Map.of());
        assertThat(scope.allows(WARD_A, DOCTOR, ADMISSION)).isTrue();
        assertThat(scope.allows(WARD_B, DOCTOR, ADMISSION)).isTrue();
        assertThat(scope.allows(WARD_C, DOCTOR, ADMISSION)).isFalse();
        assertThat(scope.allows(null, DOCTOR, ADMISSION)).isFalse();
    }

    @Test
    void aNurseWithNoWardReachesNobodyBeyondHerGrants() {
        PatientScope none = wards(Set.of(), Map.of());
        assertThat(none.kind()).isEqualTo(Kind.NONE);
        assertThat(none.hasWard()).isFalse();
        assertThat(none.allows(WARD_A, DOCTOR, ADMISSION)).isFalse();
        assertThat(none.allows(null, null, ADMISSION)).isFalse();
    }

    @Test
    void aLiveGrantReachesThatAdmissionOnlyAndFlagsTheRow() {
        PatientScope scope = wards(Set.of(WARD_A), Map.of(ADMISSION, ENDS));
        assertThat(scope.allows(WARD_C, DOCTOR, ADMISSION)).isTrue();
        assertThat(scope.allows(WARD_C, DOCTOR, UUID.randomUUID())).isFalse();
        assertThat(scope.grantOnlyExpiry(WARD_C, DOCTOR, ADMISSION)).isEqualTo(ENDS);
    }

    @Test
    void aPatientInHerWardIsNotFlaggedAsEmergencyAccessEvenWithAGrant() {
        PatientScope scope = wards(Set.of(WARD_A), Map.of(ADMISSION, ENDS));
        assertThat(scope.allows(WARD_A, DOCTOR, ADMISSION)).isTrue();
        assertThat(scope.grantOnlyExpiry(WARD_A, DOCTOR, ADMISSION)).isNull();
    }

    @Test
    void ownReachesThePatientsOfTheAdmittingDoctorOnly() {
        PatientScope own = new PatientScope(Kind.OWN, USER, "VISITING_DOCTOR", "VISITING_DOCTOR", ME, Set.of(),
                Map.of());
        assertThat(own.allows(WARD_A, ME, ADMISSION)).isTrue();
        assertThat(own.allows(WARD_A, DOCTOR, ADMISSION)).isFalse();
    }

    @Test
    void ownWithoutAMembershipReachesNobody() {
        PatientScope own = new PatientScope(Kind.OWN, USER, "VISITING_DOCTOR", "VISITING_DOCTOR", null, Set.of(),
                Map.of());
        assertThat(own.allows(WARD_A, null, ADMISSION)).isFalse();
    }

    @Test
    void anUnscopedNurseHasHerWardsForMineAndHasWard() {
        PatientScope off = PatientScope.all(USER, "NURSE", "NURSE", ME, Set.of(WARD_A));
        assertThat(off.isAll()).isTrue();
        assertThat(off.isNurse()).isTrue();
        assertThat(off.hasWard()).isTrue();
        assertThat(off.wardIds()).containsExactly(WARD_A);
    }
}
