package com.sahaj.platform.application;

import com.sahaj.platform.api.ColleaguePinCheck;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.PinCheck;
import com.sahaj.platform.api.StaffPinException;
import com.sahaj.platform.api.StaffPinPort;
import com.sahaj.platform.application.StaffPinOps.ColleagueResult;
import com.sahaj.platform.application.StaffPinOps.Own;
import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.PinRules;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.PermissionDeniedDataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The personal staff PIN (SAH-31, ADR-010): status, one-time code to the person's own mobile, set or change, and the
 * check that other modules call through {@link StaffPinPort}.
 *
 * <p>Not itself transactional, on purpose. The order of every operation is fixed: rate limit, then the pepper
 * (fail closed with 503 {@code pin-unavailable}), then the body, and only then the one-time code, which is verified
 * <b>outside</b> any write transaction so that its failed-try count commits even when this request fails. Each
 * database step is one short transaction of {@link StaffPinOps}. A wrong or expired code is a 400 field error on
 * {@code code} and never a 401, which the client would take for a lost session. The PIN, the code and the mobile
 * number are never logged, audited, stored in clear or returned.
 */
@Service
public class StaffPinService implements StaffPinPort {

    static final String MSG_CODE_FORMAT = "Enter the 6-digit code.";
    static final String MSG_PIN_FORMAT = "Enter a 4-digit PIN.";
    static final String MSG_PIN_WEAK = "This PIN is too easy to guess. Choose another one.";
    static final String MSG_PIN_MISMATCH = "The two PINs do not match.";
    static final String MSG_NO_CODE = "Send a code first.";

    private static final Logger log = LoggerFactory.getLogger(StaffPinService.class);

    private final StaffPinOps ops;
    private final StaffPinHasher hasher;
    private final StaffPinLimits limits;
    private final OtpService otpService;
    private final AuthenticationScope scope;
    /** Striped monitors that serialise one caller's colleague checks (64 stripes; a clash only makes two callers wait). */
    private final Object[] callerLocks = new Object[64];

    public StaffPinService(StaffPinOps ops, StaffPinHasher hasher, StaffPinLimits limits, OtpService otpService,
                           AuthenticationScope scope) {
        for (int i = 0; i < callerLocks.length; i++) {
            callerLocks[i] = new Object();
        }
        this.ops = ops;
        this.hasher = hasher;
        this.limits = limits;
        this.otpService = otpService;
        this.scope = scope;
    }

    /** SAH-66: which of these memberships have a PIN set (chemotherapy checker options). */
    @Override
    public java.util.Set<UUID> membershipsWithPin(UUID tenantId, java.util.Collection<UUID> membershipIds) {
        return ops.membershipsWithPin(tenantId, membershipIds);
    }

    /** GET /me/pin. Writes nothing. */
    public PinStatusView status(UUID tenantId, UUID userId) {
        Own own = ops.readOwn(tenantId, userId).orElseThrow(StaffPinService::notAllowed);
        return ops.statusOf(own, hasher.isAvailable());
    }

    /** POST /me/pin/code: a one-time code to the signed-in person's own mobile, read on the server. */
    public PinCodeSentView sendCode(UUID tenantId, UUID userId) {
        requireAvailable();
        Own own = ops.readOwn(tenantId, userId).orElseThrow(StaffPinService::notAllowed);
        long wait = limits.codeRequestWait(tenantId, userId);
        if (wait > 0) {
            throw StaffPinException.rateLimited(wait);
        }
        int resendAfterSeconds = otpService.request(own.mobile());
        return new PinCodeSentView(resendAfterSeconds, MobileMask.mask(own.mobile()));
    }

    /** PUT /me/pin: set, change or replace the person's own PIN with a fresh one-time code. */
    public PinStatusView set(UUID tenantId, UUID userId, PinSetInput input) {
        long wait = limits.pinSetWait(tenantId, userId);
        if (wait > 0) {
            throw StaffPinException.rateLimited(wait);
        }
        requireAvailable();
        Own own = ops.readOwn(tenantId, userId).orElseThrow(StaffPinService::notAllowed);

        List<FieldMessage> errors = new ArrayList<>();
        if (!PinRules.isSixDigitCode(input.code())) {
            errors.add(new FieldMessage("code", MSG_CODE_FORMAT));
        }
        if (!PinRules.isFourDigits(input.newPin())) {
            errors.add(new FieldMessage("newPin", MSG_PIN_FORMAT));
        } else if (PinRules.isWeak(input.newPin(), own.mobile())) {
            errors.add(new FieldMessage("newPin", MSG_PIN_WEAK));
        }
        if (!sameText(input.newPin(), input.confirmPin())) {
            errors.add(new FieldMessage("confirmPin", MSG_PIN_MISMATCH));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }

        verifyCode(own.mobile(), input.code());
        try {
            return ops.commitSet(tenantId, userId, input.newPin());
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("concurrent-change", "Your PIN just changed",
                    "Your PIN was saved at the same moment from another screen. Try again.");
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Order (ADR-010 D6, D13): the transaction guard, the limit on failed checks (looked at before comparing; a check
     * that does not answer OK - wrong, locked or no PIN - counts toward it, a right PIN never does, review R1), the pepper,
     * then the membership, the row
     * lock, the clock decision and the comparison inside one short platform transaction of its own.
     */
    @Override
    public PinCheck check(UUID tenantId, UUID userId, String pin) {
        if (tenantId == null || userId == null) {
            throw new IllegalArgumentException("tenantId and userId are required");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "StaffPinPort.check must be called outside any transaction (ADR-010 D9)");
        }
        long wait = limits.pinCheckWait(tenantId, userId);
        if (wait > 0) {
            return new PinCheck(PinCheck.Outcome.RATE_LIMITED, 0, null, wait);
        }
        if (!hasher.isAvailable()) {
            return new PinCheck(PinCheck.Outcome.UNAVAILABLE, 0, null, 0);
        }
        PinCheck result = scope.runAsTenant(userId, tenantId, () -> ops.check(tenantId, userId, pin));
        if (result.outcome() != PinCheck.Outcome.OK) {
            limits.pinCheckFailed(tenantId, userId);
        }
        return result;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Order (ADR-014 D3, amending ADR-011 D7): the transaction guard; the caller's own in-memory limit on failed
     * colleague checks (5 per 15 minutes, 10 per 24 hours; looked at before any hash); the pepper (fail closed); then one
     * short platform transaction of {@link StaffPinOps#checkColleague}: who may be named (same answer for every ineligible
     * case), the colleague's row lock, no PIN, locked, the durable pair cap, the comparison. The colleague's own
     * failed-check limit is no longer read or filled (D7). Only a WRONG PIN (including the wrong try that locks the PIN)
     * counts toward the caller's limit; a right PIN, a locked PIN, a missing PIN, a pair limit and a refusal never do. A
     * caller's colleague checks run one after another (a striped lock, in memory), so a burst of parallel tries cannot
     * pass the limit before the first failure is counted.
     *
     * <p>Fail closed (D5): a timeout or other database failure in that transaction rolls everything back and answers
     * CHECK_FAILED, the same whether the PIN was right or wrong, and nothing is counted. A constraint, permission or SQL
     * refusal is a code fault and is thrown (a plain 500), never turned into an answer about the PIN.
     */
    @Override
    public ColleaguePinCheck checkColleague(UUID tenantId, UUID callerUserId, UUID colleagueMembershipId,
                                            String requiredRole, String pin) {
        if (tenantId == null || callerUserId == null) {
            throw new IllegalArgumentException("tenantId and callerUserId are required");
        }
        MembershipRole role = parseRole(requiredRole);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "StaffPinPort.checkColleague must be called outside any transaction (ADR-011 D7)");
        }
        synchronized (callerLock(tenantId, callerUserId)) {
            long wait = limits.colleagueCheckWait(tenantId, callerUserId);
            if (wait > 0) {
                return ColleaguePinCheck.failed(new PinCheck(PinCheck.Outcome.RATE_LIMITED, 0, null, wait));
            }
            if (!hasher.isAvailable()) {
                return ColleaguePinCheck.failed(new PinCheck(PinCheck.Outcome.UNAVAILABLE, 0, null, 0));
            }
            ColleagueResult result;
            try {
                result = scope.runAsTenant(callerUserId, tenantId,
                        () -> ops.checkColleague(tenantId, callerUserId, colleagueMembershipId, role, pin));
            } catch (DataAccessException | TransactionException ex) {
                if (isCodeFault(ex)) {
                    throw ex;
                }
                log.warn("staff-pin-colleague-check tenantId={} callerUserId={} membershipId={} outcome=CHECK_FAILED cause={}",
                        tenantId, callerUserId, colleagueMembershipId, ex.getClass().getSimpleName());
                return ColleaguePinCheck.failed(new PinCheck(PinCheck.Outcome.CHECK_FAILED, 0, null, 0));
            }
            if (result.wrongTry()) {
                limits.colleagueCheckFailed(tenantId, callerUserId);
            }
            if (result.check().outcome() == PinCheck.Outcome.NOT_ELIGIBLE) {
                return ColleaguePinCheck.notEligible();
            }
            return new ColleaguePinCheck(result.check(),
                    result.check().isOk() ? result.colleagueUserId() : null);
        }
    }

    /**
     * A refusal by the database of a statement the code wrote (constraint, permission, SQL): a bug, not a slow or
     * unreachable store (ADR-014 D5, docs/database/SAH-38.md error classes). Only timeouts and the like fail closed.
     */
    private static boolean isCodeFault(RuntimeException ex) {
        return ex instanceof DataIntegrityViolationException || ex instanceof PermissionDeniedDataAccessException
                || ex instanceof BadSqlGrammarException || ex instanceof InvalidDataAccessApiUsageException;
    }

    private Object callerLock(UUID tenantId, UUID callerUserId) {
        int hash = 31 * tenantId.hashCode() + callerUserId.hashCode();
        return callerLocks[Math.floorMod(hash, callerLocks.length)];
    }

    private static MembershipRole parseRole(String requiredRole) {
        if (requiredRole == null || requiredRole.isBlank()) {
            throw new IllegalArgumentException("requiredRole is required");
        }
        try {
            return MembershipRole.valueOf(requiredRole);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("requiredRole is not a known role");
        }
    }

    /** The one-time code step. Failures become 400 field errors on {@code code}; a locked number stays 423. */
    private void verifyCode(String mobile, String code) {
        try {
            otpService.verify(mobile, code);
        } catch (OtpInvalidException ex) {
            String message = ex.getMessage() != null && ex.getMessage().startsWith("No code")
                    ? MSG_NO_CODE : ex.getMessage();
            throw new InvalidFieldsException(List.of(new FieldMessage("code", message)));
        }
    }

    private void requireAvailable() {
        if (!hasher.isAvailable()) {
            throw StaffPinException.unavailable();
        }
    }

    /** Compares two typed PINs without an early return; null is equal only to null. */
    private static boolean sameText(String first, String second) {
        if (first == null || second == null) {
            return first == second;
        }
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
    }

    private static ForbiddenException notAllowed() {
        return new ForbiddenException("forbidden", "Not allowed", "You do not have access to this.");
    }
}
