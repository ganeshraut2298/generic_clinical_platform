package com.sahaj.platform.devtools.bootstrap;

import com.sahaj.platform.application.admin.AdminPasswordHasher;
import com.sahaj.platform.application.admin.AdminProvisioning;
import com.sahaj.platform.web.Problems;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The rules of the bootstrap registration (ADR-018 D4 to D6, Revision 3 "count first, judge after").
 *
 * <ul>
 *   <li>unlock is NOT transactional itself ({@code propagation = NEVER}, so calling it inside a transaction fails at
 *       once). It runs up to three short READ COMMITTED transactions through a {@link TransactionTemplate}: the
 *       read-only peek for a bad body, the claim T1 ({@code begin}: counts the try and commits), and the judge T2
 *       ({@code unlock_failed} or {@code unlock_succeeded}). The PIN is compared OUTSIDE any transaction, strictly after
 *       the claim's commit returned. A failure after the claim leaves the try counted: the limit fails closed;
 *   <li>register: {@code ticket_status} before the form, validation in Java, then {@code register}, all in one
 *       transaction. The {@code register} function returning CREATED is its last database step (Revision 5: no pre-auth
 *       token is issued and no sign-in function is called); only the commit follows. A failed commit rolls the admin
 *       back and leaves the ticket unspent. It never calls {@code begin}.
 * </ul>
 *
 * <p>Order of answers on both calls: closed (an admin exists), then locked, then the ticket (register only), then the
 * form (a blank or unreadable body is 400 and is not counted). The database clock is the only clock that decides a lock
 * or a ticket; Java computes only the seconds left of a lock, from the {@code locked_until} the database returned, and
 * the 10-second wait for a busy turn (injected {@link Clock}).
 *
 * <p>Never logs, and no outcome prints a value. The ticket and binding exist only in the returned
 * outcome.
 */
public class BootstrapService {

    static final int WRONG_PINS_BEFORE_LOCK = 5;
    static final long LOCK_SECONDS = Duration.ofMinutes(60).toSeconds();
    /**
     * The ticket lifetime the unlock answer gives the page. It repeats the 5 minutes the database fixes in
     * docs/database/SAH-42-admin-bootstrap.sql (ADR-018 is the source); the database value is not read back on purpose.
     * Change all three together. BootstrapServiceTest fails if this is not 300.
     */
    static final int TICKET_SECONDS =(int) Duration.ofMinutes(5).toSeconds();
    static final Duration BUSY_POLL = Duration.ofMillis(250);
    static final Duration BUSY_LIMIT = Duration.ofSeconds(10);
    private static final int MAX_NAME = 120;
    private static final String NAME_TEXT = "Enter the full name.";
    private static final String EMAIL_TEXT = "Enter a valid email.";
    private static final String PASSWORD_TEXT = "The password must be 12 to 64 characters and must not contain the "
            + "first part of your email.";

    /** Waits between two claim attempts; replaced in tests so no test sleeps. */
    @FunctionalInterface
    interface Pause {
        void pause(Duration duration) throws InterruptedException;
    }

    private final BootstrapRepository repository;
    private final BootstrapPinVerifier verifier;
    private final BootstrapTicketHasher ticketHasher;
    private final AdminProvisioning provisioning;
    private final SecureRandom random;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnlyTransaction;
    private final Pause pause;

    BootstrapService(BootstrapRepository repository, BootstrapPinVerifier verifier, BootstrapTicketHasher ticketHasher,
                     AdminPasswordHasher passwordHasher, SecureRandom random, Clock clock,
                     PlatformTransactionManager transactionManager, Pause pause) {
        this.repository = repository;
        this.verifier = verifier;
        this.ticketHasher = ticketHasher;
        this.provisioning = new AdminProvisioning(passwordHasher);
        this.random = random;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
        this.pause = pause;
    }

    // ------------------------------------------------------------------ outcomes

    /**
     * {@code LOCKED} is a lock already in force (answered 423, never logged); {@code LOCKED_NOW} is the 5th wrong PIN
     * that set the lock (answered 423 too, and the only outcome that is logged, once per lock); {@code BAD_REQUEST} is an
     * unreadable body ({@code 400 bad-request}); {@code INVALID_BODY} is a readable body with a missing, null or blank
     * PIN ({@code 400 validation}). Neither 400 is counted.
     */
    public enum UnlockKind { UNLOCKED, WRONG_PIN, LOCKED, LOCKED_NOW, INVALID_BODY, BAD_REQUEST, CLOSED }

    /**
     * What the controller needs to answer. {@code lockedUntil} is set only for {@code LOCKED_NOW}. {@code toString}
     * prints the kind only.
     */
    public record UnlockOutcome(UnlockKind kind, int triesLeft, long retryAfterSeconds, String ticket, String binding,
                                Instant lockedUntil) {

        UnlockOutcome(UnlockKind kind, int triesLeft, long retryAfterSeconds, String ticket, String binding) {
            this(kind, triesLeft, retryAfterSeconds, ticket, binding, null);
        }

        @Override
        public String toString() {
            return "UnlockOutcome[kind=" + kind + "]";
        }
    }

    /** {@code BAD_REQUEST}: a valid ticket and an unreadable body ({@code 400 bad-request}); the ticket is not spent. */
    public enum RegisterKind { CREATED, CLOSED, LOCKED, TICKET_INVALID, BAD_REQUEST, INVALID_FORM }

    public record RegisterOutcome(RegisterKind kind, long retryAfterSeconds, List<Problems.FieldError> fieldErrors,
                           UUID adminId) {
        @Override
        public String toString() {
            return "RegisterOutcome[kind=" + kind + "]";
        }
    }

    // ------------------------------------------------------------------ unlock

    @Transactional(propagation = Propagation.NEVER)
    public UnlockOutcome unlock(BootstrapBodies.UnlockBody body, String remoteAddress, String requestId) {
        if (!body.wellFormed() || body.pin() == null || body.pin().isBlank()) {
            return peek(body);
        }
        UUID tryId = UUID.randomUUID();
        UnlockOutcome refused = claim(tryId);
        if (refused != null) {
            return refused;
        }
        // T1 has committed: the try is counted whatever happens from here. The compare runs outside any transaction.
        boolean correct = compare(body.pin());
        return correct ? judgeCorrect(tryId) : judgeWrong(tryId, remoteAddress, requestId);
    }

    /**
     * A bad body is answered from a read-only peek and is never counted (BR7f): {@code begin} is not called. The order
     * of answers stays closed, locked, then the body.
     */
    private UnlockOutcome peek(BootstrapBodies.UnlockBody body) {
        BootstrapRepository.TicketState state = readOnlyTransaction.execute(
                status -> repository.ticketStatus(null, null));
        switch (state.status()) {
            case CLOSED:
                return unlockOutcome(UnlockKind.CLOSED);
            case LOCKED:
                return locked(state.lockedUntil());
            default:
                return unlockOutcome(body.wellFormed() ? UnlockKind.INVALID_BODY : UnlockKind.BAD_REQUEST);
        }
    }

    /**
     * Transaction T1, repeated while another try holds the lease: each attempt is its own transaction. Returns null when
     * this try was counted (and committed), otherwise the answer that ends the call. After 10 seconds of BUSY the call
     * ends as {@link BootstrapExceptions.Busy} (429, not counted); so does a lock wait over 10 seconds inside
     * {@code begin} (thrown by the repository).
     */
    private UnlockOutcome claim(UUID tryId) {
        Instant giveUpAt = clock.instant().plus(BUSY_LIMIT);
        while (true) {
            BootstrapRepository.Claim claim = transaction.execute(status -> repository.begin(tryId));
            switch (claim.outcome()) {
                case COUNTED:
                    return null;
                case CLOSED:
                    return unlockOutcome(UnlockKind.CLOSED);
                case LOCKED:
                    return locked(claim.lockedUntil());
                case BUSY:
                    if (!clock.instant().isBefore(giveUpAt)) {
                        throw new BootstrapExceptions.Busy();
                    }
                    waitBeforeRetry();
                    break;
                default:
                    throw new BootstrapExceptions.Failed(null);
            }
        }
    }

    private void waitBeforeRetry() {
        try {
            pause.pause(BUSY_POLL);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BootstrapExceptions.Failed(null);
        }
    }

    private boolean compare(String pin) {
        char[] typed = pin.toCharArray();
        try {
            return verifier.matches(typed);
        } finally {
            Arrays.fill(typed, '\0');
        }
    }

    /**
     * Transaction T2, wrong PIN. It never changes the count; the answer comes from the {@code failures} it returns (the
     * count already holds this try). Any pair other than 1 to 4 without a lock, or 5 with a lock, is a 500.
     */
    private UnlockOutcome judgeWrong(UUID tryId, String remoteAddress, String requestId) {
        BootstrapRepository.Failure failure = judge(() -> repository.unlockFailed(tryId, remoteAddress, requestId));
        boolean lockSet = failure.lockedUntil() != null;
        if (lockSet && failure.failures() == WRONG_PINS_BEFORE_LOCK) {
            return new UnlockOutcome(UnlockKind.LOCKED_NOW, 0, LOCK_SECONDS, null, null, failure.lockedUntil());
        }
        if (!lockSet && failure.failures() >= 1 && failure.failures() < WRONG_PINS_BEFORE_LOCK) {
            return new UnlockOutcome(UnlockKind.WRONG_PIN, WRONG_PINS_BEFORE_LOCK - failure.failures(), 0, null, null);
        }
        throw new BootstrapExceptions.Failed(null);
    }

    /** Transaction T2, right PIN: resets the count and stores the keyed hashes of a new ticket and binding. */
    private UnlockOutcome judgeCorrect(UUID tryId) {
        String ticket = BootstrapTicketHasher.newValue(random);
        String binding = BootstrapTicketHasher.newValue(random);
        judge(() -> {
            repository.unlockSucceeded(tryId, ticketHasher.hash(ticket), ticketHasher.hash(binding));
            return Boolean.TRUE;
        });
        return new UnlockOutcome(UnlockKind.UNLOCKED, 0, 0, ticket, binding);
    }

    /**
     * Runs the judge in its own transaction. Any exception rolls back that transaction only (T1's count stays) and ends
     * the call: {@code SB001} stays {@link BootstrapExceptions.Closed} (empty 404); a lock wait over 10 seconds is a
     * {@link BootstrapExceptions.Failed} (the try is already used, so not a 429); everything else propagates and the
     * controller answers the bodyless 500. Never a 200 and never a 401 after a failed judge.
     */
    private <T> T judge(Supplier<T> call) {
        try {
            return transaction.execute(status -> call.get());
        } catch (BootstrapExceptions.Busy busy) {
            throw new BootstrapExceptions.Failed(JdbcBootstrapRepository.LOCK_NOT_AVAILABLE);
        }
    }

    private static UnlockOutcome unlockOutcome(UnlockKind kind) {
        return new UnlockOutcome(kind, 0, 0, null, null);
    }

    private UnlockOutcome locked(Instant lockedUntil) {
        return new UnlockOutcome(UnlockKind.LOCKED, 0, secondsLeft(requireLockTime(lockedUntil)), null, null);
    }

    /** A LOCKED answer without an end time cannot be turned into a number: fail closed with the bodyless 500. */
    private static Instant requireLockTime(Instant lockedUntil) {
        if (lockedUntil == null) {
            throw new BootstrapExceptions.Failed(null);
        }
        return lockedUntil;
    }

    // ------------------------------------------------------------------ register

    @Transactional
    public RegisterOutcome register(BootstrapBodies.RegisterBody body, String bindingCookie, String remoteAddress,
                             String requestId) {
        boolean shaped = BootstrapTicketHasher.wellShaped(body.ticket())
                && BootstrapTicketHasher.wellShaped(bindingCookie);
        String ticketHash = shaped ? ticketHasher.hash(body.ticket()) : null;
        String bindingHash = shaped ? ticketHasher.hash(bindingCookie) : null;

        BootstrapRepository.TicketState state = repository.ticketStatus(ticketHash, bindingHash);
        switch (state.status()) {
            case CLOSED:
                return registerOutcome(RegisterKind.CLOSED);
            case LOCKED:
                return lockedRegister(state.lockedUntil());
            case INVALID:
                return registerOutcome(RegisterKind.TICKET_INVALID);
            case VALID:
                return registerValid(body, ticketHash, bindingHash, remoteAddress, requestId);
            default:
                throw new BootstrapExceptions.Failed(null);
        }
    }

    private RegisterOutcome registerValid(BootstrapBodies.RegisterBody body, String ticketHash, String bindingHash,
                                          String remoteAddress, String requestId) {
        if (!body.wellFormed()) {
            return registerOutcome(RegisterKind.BAD_REQUEST);
        }
        List<Problems.FieldError> errors = new ArrayList<>();
        AdminProvisioning.PreparedAdmin prepared = validate(body, errors);
        if (prepared == null) {
            return new RegisterOutcome(RegisterKind.INVALID_FORM, 0, errors, null);
        }

        UUID adminId = UUID.randomUUID();
        BootstrapRepository.RegisterReply reply = repository.register(ticketHash, bindingHash, adminId,
                prepared.email(), prepared.name(), prepared.passwordHash(), remoteAddress, requestId);
        switch (reply.result()) {
            case CLOSED:
                return registerOutcome(RegisterKind.CLOSED);
            case LOCKED:
                return lockedRegister(reply.lockedUntil());
            case INVALID:
                return registerOutcome(RegisterKind.TICKET_INVALID);
            case CREATED:
                // The last database step. Nothing is called after it: only the commit follows (ADR-018 Revision 5).
                return new RegisterOutcome(RegisterKind.CREATED, 0, List.of(), adminId);
            default:
                throw new BootstrapExceptions.Failed(null);
        }
    }

    // ------------------------------------------------------------------ the form (ADR-018 D6 step 2)

    /**
     * The name, then the first refused of email or password. Returns the checked values, or null with the field errors
     * added (at most two: the name, plus the first wrong of email and password).
     */
    private AdminProvisioning.PreparedAdmin validate(BootstrapBodies.RegisterBody body,
                                                     List<Problems.FieldError> errors) {
        String rawName = body.fullName() == null ? "" : body.fullName();
        String rawEmail = body.email() == null ? "" : body.email();
        boolean nameOk = checkName(rawName, errors);
        if (hasControlCharacters(rawEmail)) {
            errors.add(new Problems.FieldError("email", EMAIL_TEXT));
            return null;
        }
        AdminProvisioning.PreparedAdmin prepared = checkEmailAndPassword(rawEmail, nameOk ? rawName.trim() : "",
                body.password() == null ? "" : body.password(), errors);
        return nameOk ? prepared : null;
    }

    /** Name 1 to 120 characters after trimming, no control character in the raw value (checked before trimming). */
    private static boolean checkName(String rawName, List<Problems.FieldError> errors) {
        String name = rawName.trim();
        boolean ok = !hasControlCharacters(rawName) && !name.isEmpty() && name.length() <= MAX_NAME
                && !name.contains("$sq$");
        if (!ok) {
            errors.add(new Problems.FieldError("fullName", NAME_TEXT));
        }
        return ok;
    }

    /**
     * Email and password through the one shared rule set ({@link AdminProvisioning}), which stops at its first refusal:
     * email before password. A refused name is passed as empty text, so this judges only the email and the password.
     */
    private AdminProvisioning.PreparedAdmin checkEmailAndPassword(String rawEmail, String name, String password,
                                                                  List<Problems.FieldError> errors) {
        try {
            return provisioning.prepare(rawEmail, name, password);
        } catch (AdminProvisioning.RefusedException refused) {
            switch (refused.reason()) {
                case EMAIL_INVALID:
                    errors.add(new Problems.FieldError("email", EMAIL_TEXT));
                    break;
                case NAME_INVALID:
                    errors.add(new Problems.FieldError("fullName", NAME_TEXT));
                    break;
                default:
                    errors.add(new Problems.FieldError("password", PASSWORD_TEXT));
                    break;
            }
            return null;
        }
    }

    /**
     * True for a Unicode control character (category Cc: U+0000 to U+001F and U+007F to U+009F, so tab, line feed and
     * carriage return too) and for an unpaired UTF-16 surrogate. PostgreSQL text cannot hold U+0000 and the driver turns
     * a lone surrogate into "?". Applied to the raw value, because {@code String.trim()} removes control characters at
     * the ends and would let a leading one through.
     */
    static boolean hasControlCharacters(String raw) {
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < raw.length() && Character.isLowSurrogate(raw.charAt(i + 1))) {
                    i++;
                } else {
                    return true;
                }
            } else if (Character.isLowSurrogate(c) || Character.getType(c) == Character.CONTROL) {
                return true;
            }
        }
        return false;
    }

    /**
     * LOCKED from {@code ticket_status} or {@code register} carries its own {@code locked_until} (Revision 3): the answer
     * is computed from it and {@code begin} is never called by register (it would count a try).
     */
    private RegisterOutcome lockedRegister(Instant lockedUntil) {
        return new RegisterOutcome(RegisterKind.LOCKED, secondsLeft(requireLockTime(lockedUntil)), List.of(), null);
    }

    private static RegisterOutcome registerOutcome(RegisterKind kind) {
        return new RegisterOutcome(kind, 0, List.of(), null);
    }

    /** Whole seconds left of a lock, rounded up, at least 1 (the page shows whole minutes, rounded up). */
    long secondsLeft(Instant lockedUntil) {
        Duration left = Duration.between(clock.instant(), lockedUntil);
        long seconds = left.toSeconds() + (left.toNanosPart() > 0 ? 1 : 0);
        return Math.max(1, Math.min(seconds, LOCK_SECONDS));
    }

    static int ticketSeconds() {
        return TICKET_SECONDS;
    }
}
