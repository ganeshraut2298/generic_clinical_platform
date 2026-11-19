package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The helper of security L13 fails when the user id reaches a writer and passes when only the membership id does. */
class UserIdNeverWrittenTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();

    @Test
    void aCallCarryingTheUserIdIsFoundEvenInsideARecordOrAMapAndOneWithTheMembershipIdIsNot() {
        StockAdjustmentRepository repository = mock(StockAdjustmentRepository.class);
        repository.setStatus(UUID.randomUUID(), UUID.randomUUID(), "POSTED", MEMBERSHIP);
        UserIdNeverWritten.assertNone(USER, repository);

        repository.setStatus(UUID.randomUUID(), UUID.randomUUID(), "POSTED", USER);
        assertThat(UserIdNeverWritten.carries(USER, repository)).isTrue();
        assertThatThrownBy(() -> UserIdNeverWritten.assertNone(USER, repository)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("setStatus");
    }

    @Test
    void theActorIsTheMembershipIdOfTheCaller() {
        PharmacyCaller caller = new PharmacyCaller(UUID.randomUUID(), USER, MEMBERSHIP, "Test Person", "PHARMACIST",
                java.util.List.of());

        assertThat(caller.actorId()).isEqualTo(MEMBERSHIP).isNotEqualTo(caller.userId());
    }
}
