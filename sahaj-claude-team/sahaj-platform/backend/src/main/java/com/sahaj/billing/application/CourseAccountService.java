package com.sahaj.billing.application;

import com.sahaj.billing.application.CourseAccountMath.Account;
import com.sahaj.billing.application.CourseAccountMath.SittingInput;
import com.sahaj.billing.application.CourseAccountViews.Collected;
import com.sahaj.billing.application.CourseAccountViews.CourseAccount;
import com.sahaj.billing.application.CourseAccountViews.CoursePaymentInput;
import com.sahaj.billing.application.CourseAccountViews.CourseReceipt;
import com.sahaj.billing.application.CourseAccountViews.LedgerLine;
import com.sahaj.billing.application.CourseAccountViews.NextPlannedAdmission;
import com.sahaj.billing.application.CourseAccountViews.PaymentResult;
import com.sahaj.billing.application.CourseAccountViews.Suggestions;
import com.sahaj.billing.application.CoursePaymentRepository.NewPayment;
import com.sahaj.billing.application.CoursePaymentRepository.PaymentRow;
import com.sahaj.billing.application.CoursePaymentRepository.TransferRow;
import com.sahaj.opd.api.TreatmentCoursePort;
import com.sahaj.opd.api.TreatmentCoursePort.CourseFacts;
import com.sahaj.opd.api.TreatmentCoursePort.SittingFacts;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicLetterheadPort;
import com.sahaj.platform.api.ClinicLetterheadPort.Letterhead;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The running account of a treatment course (SAH-58; ADR-022 D12; rules B-1, tagged "value from the EMR mockup, accepted
 * by the human in DEC-039, not clinically reviewed"): estimate, paid, credit, due and balance worked out on read from the
 * course plan ({@link TreatmentCoursePort}) and billing's own append-only payments and credit transfers; collecting a
 * payment with a receipt number; and the printable receipt. Money is whole paise and the arithmetic is
 * {@link CourseAccountMath}.
 *
 * <p>Who (DEC-042 H2): OWNER and FRONT_DESK collect, as at the counter; OWNER, DOCTOR, VISITING_DOCTOR, FRONT_DESK and
 * BILLING read the account; OWNER, FRONT_DESK and BILLING read a receipt. A payment is recorded once: the same
 * Idempotency-Key and body return the first one, another body is a 409. Payments of one course are serialised on an
 * advisory lock, so two payments cannot together pass the balance. The audit holds ids, the receipt number, type, mode
 * and amount, never a name or the sitting items. This class never logs.
 */
@Service
public class CourseAccountService {

    static final Set<String> READ_ROLES = Set.of("OWNER", "DOCTOR", "VISITING_DOCTOR", "FRONT_DESK", "BILLING");
    static final Set<String> COLLECT_ROLES = BillingService.COLLECT_ROLES;
    static final Set<String> RECEIPT_ROLES = Set.of("OWNER", "FRONT_DESK", "BILLING");
    static final Set<String> TYPES = Set.of("VISIT_PAYMENT", "FULL_BALANCE", "PART_PAYMENT", "ADVANCE", "IPD_DEPOSIT");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final String ENTITY = "TREATMENT_COURSE";

    private final TreatmentCoursePort courses;
    private final CoursePaymentRepository payments;
    private final PatientLookupPort patients;
    private final ActorDirectoryPort actors;
    private final ClinicLetterheadPort letterhead;
    private final AuditLog auditLog;

    public CourseAccountService(TreatmentCoursePort courses, CoursePaymentRepository payments,
                                PatientLookupPort patients, ActorDirectoryPort actors,
                                ClinicLetterheadPort letterhead, AuditLog auditLog) {
        this.courses = courses;
        this.payments = payments;
        this.patients = patients;
        this.actors = actors;
        this.letterhead = letterhead;
        this.auditLog = auditLog;
    }

    // ---- account ----

    @Transactional(readOnly = true)
    public CourseAccount account(ClinicUser user, UUID courseId) {
        if (!READ_ROLES.contains(user.role())) {
            throw new AccessDeniedException("You cannot see the account of a treatment course.");
        }
        CourseFacts course = courses.course(user.tenantId(), courseId).orElseThrow(CourseProblemException::courseNotFound);
        return build(user.tenantId(), course, COLLECT_ROLES.contains(user.role()));
    }

    /** The account for a caller that has already proven its right (collect, credit carry). */
    CourseAccount build(UUID tenantId, CourseFacts course, boolean canCollect) {
        List<PaymentRow> paid = payments.findByCourse(tenantId, course.courseId());
        List<TransferRow> transfers = payments.transfersOf(tenantId, course.courseId());
        return build(course, paid, transfers, canCollect);
    }

    CourseAccount build(CourseFacts course, List<PaymentRow> paid, List<TransferRow> transfers, boolean canCollect) {
        Account math = CourseAccountMath.compute(sittings(course), paymentInputs(paid), transferInputs(transfers));
        Map<UUID, SittingFacts> byId = new LinkedHashMap<>();
        for (SittingFacts s : course.sittings()) {
            byId.put(s.sittingId(), s);
        }
        int done = (int) course.sittings().stream().filter(s -> "DONE".equals(s.status())).count();
        SittingFacts next = course.sittings().stream().filter(s -> isPlannedAdmission(s))
                .min(Comparator.comparingInt(SittingFacts::sittingNo)).orElse(null);
        NextPlannedAdmission planned = next == null ? null
                : new NextPlannedAdmission(next.sittingId(), next.sittingNo(), next.displayNo(), next.chargePaise(),
                        next.note());
        Suggestions suggestions = new Suggestions(
                math.balancePaise() == 0 ? null : CourseAccountMath.suggestedPart(math.dueTodayPaise(),
                        math.balancePaise()),
                next == null ? null : CourseAccountMath.suggestedIpdDeposit(next.chargePaise()));
        return new CourseAccount(course.courseId(), course.courseNo(), course.unit(), math.estimatePaise(),
                math.paidPaise(), math.creditPaise(), math.dueFromEarlierPaise(), math.todayChargePaise(),
                math.dueTodayPaise(), math.balancePaise(), course.sittings().size(), done, paid.size(), planned,
                suggestions, canCollect, ledger(course, byId, paid, transfers, math));
    }

    private static boolean isPlannedAdmission(SittingFacts s) {
        return ("DAY_CARE".equals(s.encounterKind()) || "IPD".equals(s.encounterKind()))
                && "PLANNED".equals(s.status()) && s.admissionId() == null;
    }

    private static List<SittingInput> sittings(CourseFacts course) {
        List<SittingInput> inputs = new ArrayList<>();
        for (SittingFacts s : course.sittings()) {
            inputs.add(new SittingInput(s.sittingNo(), s.chargePaise(), s.status(), s.settledInIpd(), s.doneAt()));
        }
        return inputs;
    }

    private static List<CourseAccountMath.PaymentInput> paymentInputs(List<PaymentRow> paid) {
        List<CourseAccountMath.PaymentInput> inputs = new ArrayList<>();
        for (PaymentRow p : paid) {
            inputs.add(new CourseAccountMath.PaymentInput(p.receiptNo(), p.amountPaise(), p.recordedAt()));
        }
        return inputs;
    }

    private static List<CourseAccountMath.TransferInput> transferInputs(List<TransferRow> transfers) {
        List<CourseAccountMath.TransferInput> inputs = new ArrayList<>();
        for (TransferRow t : transfers) {
            inputs.add(new CourseAccountMath.TransferInput(t.amountPaise(), t.transferredAt()));
        }
        return inputs;
    }

    /** Payments (+), credit carried to IPD (-) and the derived "advance used" lines, in time order; they add up to the credit. */
    private static List<LedgerLine> ledger(CourseFacts course, Map<UUID, SittingFacts> byId, List<PaymentRow> paid,
                                           List<TransferRow> transfers, Account math) {
        record Timed(Instant at, LedgerLine line) {
        }
        List<Timed> timed = new ArrayList<>();
        for (PaymentRow p : paid) {
            timed.add(new Timed(p.recordedAt(), new LedgerLine("PAYMENT",
                    p.recordedAt().atZone(BillingService.INDIA).toLocalDate(), p.type(), p.mode(),
                    p.againstLabel(), p.amountPaise(), receiptNo(p.receiptNo()))));
        }
        for (TransferRow t : transfers) {
            SittingFacts sitting = byId.get(t.sittingId());
            String against = sitting != null && sitting.ipNumber() != null ? "Admission " + sitting.ipNumber()
                    : "Admission";
            timed.add(new Timed(t.transferredAt(), new LedgerLine("CARRIED_TO_IPD",
                    t.transferredAt().atZone(BillingService.INDIA).toLocalDate(), null, null, against,
                    -t.amountPaise(), null)));
        }
        for (CourseAccountMath.LedgerLine line : math.ledger()) {
            if ("ADVANCE_USED".equals(line.kind())) {
                Instant at = line.at();
                timed.add(new Timed(at, new LedgerLine("CREDIT_USED",
                        at == null ? null : at.atZone(BillingService.INDIA).toLocalDate(), null, null,
                        unitWord(course.unit()) + " " + displayNoOf(course, line.sittingNo()), line.amountPaise(),
                        null)));
            }
        }
        timed.sort(Comparator.comparing(Timed::at, Comparator.nullsLast(Comparator.naturalOrder())));
        return timed.stream().map(Timed::line).toList();
    }

    /** The number shown for a sitting (ADR-026 D4): its display number, the sitting number when it is not listed. */
    private static int displayNoOf(CourseFacts course, int sittingNo) {
        for (SittingFacts s : course.sittings()) {
            if (s.sittingNo() == sittingNo) {
                return s.displayNo();
            }
        }
        return sittingNo;
    }

    private static String unitWord(String unit) {
        return unit == null || unit.isEmpty() ? "" : unit.charAt(0) + unit.substring(1).toLowerCase(Locale.ROOT);
    }

    static String receiptNo(long number) {
        return "RC-" + number;
    }

    // ---- collect a payment ----

    @Transactional
    public Collected collect(ClinicUser user, UUID courseId, String idempotencyKey, CoursePaymentInput input) {
        if (!COLLECT_ROLES.contains(user.role())) {
            throw new AccessDeniedException("Only the front desk or the owner can take payments. You can view this account.");
        }
        UUID tenantId = user.tenantId();
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new InvalidInputException("Idempotency-Key",
                    "Send an Idempotency-Key of 8 to 64 letters, digits, dashes or underscores.");
        }
        validate(input);
        String hash = hash(courseId.toString(), input.type(), String.valueOf(input.amountPaise()), input.mode(),
                String.valueOf(input.useCredit()), String.valueOf(input.againstSittingId()));
        payments.lock(tenantId, "key:" + idempotencyKey);
        payments.lock(tenantId, "course:" + courseId);
        CourseFacts course = courses.course(tenantId, courseId).orElseThrow(CourseProblemException::courseNotFound);
        Optional<PaymentRow> earlier = payments.findByIdempotencyKey(tenantId, idempotencyKey);
        if (earlier.isPresent()) {
            PaymentRow first = earlier.get();
            if (!courseId.equals(first.courseId()) || !hash.equals(first.requestHash())) {
                throw new ConflictException("idempotency-conflict", "Request already used",
                        "This request key was used for a different payment.");
            }
            return new Collected(false, new PaymentResult(receiptNo(first.receiptNo()),
                    build(tenantId, course, true)));
        }
        List<PaymentRow> paid = payments.findByCourse(tenantId, courseId);
        List<TransferRow> transfers = payments.transfersOf(tenantId, courseId);
        CourseAccount before = build(course, paid, transfers, true);
        if (CourseAccountMath.overBalance(input.type(), input.amountPaise(), before.balancePaise())) {
            throw CourseProblemException.amountOverBalance();
        }
        String against = againstLabel(course, input);
        long receipt = payments.nextReceiptNo(tenantId);
        payments.insert(tenantId, new NewPayment(courseId, course.patientId(), receipt, input.type(), input.mode(),
                input.amountPaise(), input.againstSittingId(), against, input.useCredit(), idempotencyKey, hash),
                user.userId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("receiptNo", receiptNo(receipt));
        after.put("type", input.type());
        after.put("mode", input.mode());
        after.put("amountPaise", input.amountPaise());
        auditLog.record("COURSE_PAYMENT_RECORDED", ENTITY, courseId, null, after);
        return new Collected(true, new PaymentResult(receiptNo(receipt), build(tenantId, course, true)));
    }

    private static void validate(CoursePaymentInput input) {
        if (input == null || input.type() == null || !TYPES.contains(input.type())) {
            throw new InvalidInputException("type", "Choose the kind of payment.");
        }
        if (input.mode() == null || !BillingService.MODES.contains(input.mode())) {
            throw new InvalidInputException("mode", "Choose Cash, UPI or Card");
        }
        if (input.amountPaise() == null || !CourseAccountMath.validAmount(input.amountPaise())) {
            throw new InvalidInputException("amountPaise", "Enter a whole-rupee amount above zero, up to ₹10,00,00,000.");
        }
        if (input.useCredit() == null) {
            throw new InvalidInputException("useCredit", "Choose whether to use the advance balance first.");
        }
    }

    /** The "Against" text of the ledger; also checks the sitting the payment is for. */
    private static String againstLabel(CourseFacts course, CoursePaymentInput input) {
        SittingFacts sitting = null;
        if (input.againstSittingId() != null) {
            sitting = course.sittings().stream().filter(s -> s.sittingId().equals(input.againstSittingId()))
                    .findFirst().orElseThrow(() -> new InvalidInputException("againstSittingId",
                            "Choose an entry of this course."));
        }
        String word = unitWord(course.unit());
        switch (input.type()) {
            case "VISIT_PAYMENT":
                return sitting == null ? "Today's " + word.toLowerCase(Locale.ROOT) : word + " " + sitting.displayNo();
            case "IPD_DEPOSIT":
                if (sitting == null || !isPlannedAdmission(sitting)) {
                    throw new InvalidInputException("againstSittingId",
                            "Choose a planned day-care or IPD admission of this course.");
                }
                return "Admission deposit · " + word + " " + sitting.displayNo();
            default:
                if (sitting != null) {
                    throw new InvalidInputException("againstSittingId", "Leave this out for this kind of payment.");
                }
                return switch (input.type()) {
                    case "FULL_BALANCE" -> "Whole course";
                    case "PART_PAYMENT" -> "Course balance";
                    default -> "Course advance";
                };
        }
    }

    // ---- receipt ----

    @Transactional(readOnly = true)
    public CourseReceipt receipt(ClinicUser user, UUID courseId, long receiptNo) {
        if (!RECEIPT_ROLES.contains(user.role())) {
            throw new AccessDeniedException("You cannot see course receipts.");
        }
        UUID tenantId = user.tenantId();
        CourseFacts course = courses.course(tenantId, courseId).orElseThrow(CourseProblemException::courseNotFound);
        PaymentRow payment = payments.findByReceipt(tenantId, courseId, receiptNo)
                .orElseThrow(CourseProblemException::receiptNotFound);
        List<PaymentRow> all = payments.findByCourse(tenantId, courseId);
        List<TransferRow> transfers = payments.transfersOf(tenantId, courseId);
        Account now = CourseAccountMath.compute(sittings(course), paymentInputs(all), transferInputs(transfers));
        long received = all.stream().filter(p -> p.receiptNo() <= receiptNo).mapToLong(PaymentRow::amountPaise).sum();
        long carried = transfers.stream().filter(t -> !t.transferredAt().isAfter(payment.recordedAt()))
                .mapToLong(TransferRow::amountPaise).sum();
        long balanceAfter = Math.max(0, now.estimatePaise() - Math.max(0, received - carried));
        Optional<PatientSummary> patient = patients.find(tenantId, course.patientId());
        Optional<Letterhead> head = letterhead.find(tenantId);
        return new CourseReceipt(receiptNo(receiptNo), head.map(Letterhead::clinicName).orElse(""),
                head.map(Letterhead::address).orElse(null), head.map(Letterhead::phone).orElse(null),
                payment.recordedAt(), patient.map(PatientSummary::name).orElse(""),
                patient.map(PatientSummary::uhid).orElse(null), course.courseNo(), course.name(), payment.type(),
                payment.mode(), payment.againstLabel(), payment.amountPaise(),
                actors.displayName(tenantId, payment.recordedBy()).orElse("Someone"), balanceAfter);
    }

    private static String hash(String... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(String.join("\u001e", parts).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
