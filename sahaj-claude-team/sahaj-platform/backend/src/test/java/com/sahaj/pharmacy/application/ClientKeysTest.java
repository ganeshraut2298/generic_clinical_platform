package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** L15 / m-3: the loser of two taps of one client key answers as a replay; any other use of the key is 409 client-key-reused. */
class ClientKeysTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final UUID DOC = UUID.randomUUID();

    private final ClientRequestRepository clients = mock(ClientRequestRepository.class);

    private static void assertKeyReused(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("client-key-reused"));
    }

    @Test
    void theRequestThatTakesTheKeyGoesOn() {
        when(clients.take(TENANT, UNIT, ME, KEY, "op", DOC)).thenReturn(true);

        assertThat(ClientKeys.claim(clients, TENANT, UNIT, ME, KEY, "op", DOC, true)).isNull();
    }

    @Test
    void theLoserOfTheSameOperationAnswersAsAReplayOfTheWinner() {
        UUID winnersDoc = UUID.randomUUID();
        when(clients.take(TENANT, UNIT, ME, KEY, "create", DOC)).thenReturn(false);
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME, "create", winnersDoc)));

        // a create: the winner's document is another, new one, and it is the one answered
        assertThat(ClientKeys.claim(clients, TENANT, UNIT, ME, KEY, "create", DOC, false).subjectId()).isEqualTo(winnersDoc);
    }

    @Test
    void anotherOperationOrAnotherDocumentOrNoRowAtAllIs409ClientKeyReused() {
        when(clients.take(TENANT, UNIT, ME, KEY, "submit", DOC)).thenReturn(false);
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME, "create", DOC)));
        assertKeyReused(() -> ClientKeys.claim(clients, TENANT, UNIT, ME, KEY, "submit", DOC, true));

        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME, "submit", UUID.randomUUID())));
        assertKeyReused(() -> ClientKeys.claim(clients, TENANT, UNIT, ME, KEY, "submit", DOC, true));

        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.empty());
        assertKeyReused(() -> ClientKeys.claim(clients, TENANT, UNIT, ME, KEY, "submit", DOC, true));

        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME, "submit", DOC)));
        assertThat(ClientKeys.claim(clients, TENANT, UNIT, ME, KEY, "submit", DOC, true)).isNotNull();
    }

    /** Two parallel taps of one key: exactly one goes on, the other is told to replay, and neither is refused. */
    @Test
    void twoParallelTapsOfOneKeyHaveOneWinnerAndOneReplay() throws Exception {
        AtomicBoolean taken = new AtomicBoolean();
        UUID[] winnersDoc = new UUID[1];
        when(clients.take(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(i -> {
                    if (taken.compareAndSet(false, true)) {
                        winnersDoc[0] = i.getArgument(5);
                        return true;
                    }
                    return false;
                });
        when(clients.own(TENANT, ME, KEY)).thenAnswer(i -> Optional.of(new ClientRequestRepository.Taken(ME, "create", winnersDoc[0])));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ClientRequestRepository.Taken> a = pool.submit(() -> {
                start.await();
                return ClientKeys.claim(clients, TENANT, UNIT, ME, KEY, "create", UUID.randomUUID(), false);
            });
            Future<ClientRequestRepository.Taken> b = pool.submit(() -> {
                start.await();
                return ClientKeys.claim(clients, TENANT, UNIT, ME, KEY, "create", UUID.randomUUID(), false);
            });
            start.countDown();
            ClientRequestRepository.Taken first = a.get(5, TimeUnit.SECONDS);
            ClientRequestRepository.Taken second = b.get(5, TimeUnit.SECONDS);

            assertThat((first == null) != (second == null)).as("exactly one replays").isTrue();
            ClientRequestRepository.Taken replay = first == null ? second : first;
            assertThat(replay.subjectId()).isEqualTo(winnersDoc[0]);
        } finally {
            pool.shutdownNow();
        }
    }
}
