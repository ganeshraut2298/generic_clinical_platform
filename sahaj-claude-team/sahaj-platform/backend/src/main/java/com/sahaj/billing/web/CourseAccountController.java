package com.sahaj.billing.web;

import com.sahaj.billing.application.CourseAccountService;
import com.sahaj.billing.application.CourseAccountViews.Collected;
import com.sahaj.billing.application.CourseAccountViews.CourseAccount;
import com.sahaj.billing.application.CourseAccountViews.CoursePaymentInput;
import com.sahaj.billing.application.CourseAccountViews.CourseReceipt;
import com.sahaj.billing.application.CourseAccountViews.PaymentResult;
import com.sahaj.platform.api.ClinicUser;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAH-58 (ADR-022 D12): the running account of a treatment course, collecting a payment against it, and a receipt for
 * printing. OWNER, DOCTOR, VISITING_DOCTOR, FRONT_DESK and BILLING read the account; OWNER and FRONT_DESK collect (the
 * same rule as the counter, DEC-042 H2); OWNER, FRONT_DESK and BILLING read a receipt. The service checks the role again.
 * The clinic and the actor always come from the signed-in user. The course plan itself is
 * {@code GET /api/v1/opd/emr/courses/{courseId}}.
 */
@RestController
@RequestMapping("/api/v1/billing/courses")
public class CourseAccountController {

    private static final String ACCOUNT_READERS = "hasAnyRole('OWNER','DOCTOR','VISITING_DOCTOR','FRONT_DESK','BILLING')";
    private static final String RECEIPT_READERS = "hasAnyRole('OWNER','FRONT_DESK','BILLING')";

    private final CourseAccountService service;

    public CourseAccountController(CourseAccountService service) {
        this.service = service;
    }

    @GetMapping("/{courseId}/account")
    @PreAuthorize(ACCOUNT_READERS)
    public CourseAccount account(@PathVariable UUID courseId) {
        return service.account(ClinicUser.current(), courseId);
    }

    @PostMapping("/{courseId}/payments")
    @PreAuthorize(BillingRoles.COLLECT)
    public ResponseEntity<PaymentResult> collect(@PathVariable UUID courseId,
                                                 @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                 @RequestBody CoursePaymentInput input) {
        Collected result = service.collect(ClinicUser.current(), courseId, key, input);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.result());
    }

    @GetMapping("/{courseId}/receipts/{receiptNo}")
    @PreAuthorize(RECEIPT_READERS)
    public CourseReceipt receipt(@PathVariable UUID courseId, @PathVariable long receiptNo) {
        return service.receipt(ClinicUser.current(), courseId, receiptNo);
    }
}
