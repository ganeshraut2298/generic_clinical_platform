package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.ipd.application.MedicineViews.EmrSheet;
import com.sahaj.ipd.application.MedicineViews.PharmacistActionResult;
import com.sahaj.ipd.application.MedicineViews.QueryOrderInput;
import com.sahaj.ipd.application.MedicineViews.QueryReplyInput;
import com.sahaj.ipd.application.OrderQueryRepository.QueryRow;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SAH-76-2 (ADR-027 D6): the pharmacist's query and the doctor's reply. The order moves PENDING to QUERIED and back; a
 * query is never asked by the orderer; both are version-checked; the question and the reply are clinical text and never
 * reach an event or an audit row; a repeat by the same user writes nothing.
 */
class PharmacistQueryServiceTest extends MedicineTestBase {

    private static final String KEY = "3f8e2a8c-5b0e-4f7e-9d43-0c6a0b6f2a11";
    private static final String QUESTION = "Is the dose right for a 40 kg patient?";
    private static final String REPLY = "Yes, it is a paediatric dose.";

    private PharmacistQueryService service;
    private OrderRow pending;

    @BeforeEach
    void setUp() {
        service = newQueryService();
        pending = fake.seed(ADMISSION, "pcm", "PENDING", DOCTOR);
    }

    private PharmacistActionResult query(int version, String question) {
        return service.query(TENANT, PHARMACIST, "PHARMACIST", pending.id(), new QueryOrderInput(KEY, version, question));
    }

    private EmrSheet reply(String role, java.util.UUID who, int version, String reply) {
        return service.reply(TENANT, who, role, pending.id(), new QueryReplyInput(KEY, version, reply));
    }


    @SuppressWarnings("unchecked")
    private Map<String, Object> auditAfter(String action) {
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq(action), anyString(), any(), any(), after.capture());
        return after.getValue();
    }

    // ---- query ----

    @Test
    void aQueryOpensTheQuestionAndMovesTheOrderToQueried() {
        PharmacistActionResult result = query(1, QUESTION);

        assertThat(result.result().outcome()).isEqualTo("QUERIED");
        assertThat(result.result().replayed()).isFalse();
        assertThat(result.result().drugName()).isEqualTo("Tab. Paracetamol 650 mg");
        assertThat(fake.rows.get(pending.id()).status()).isEqualTo("QUERIED");
        QueryRow open = fake.findOpen(TENANT, pending.id()).orElseThrow();
        assertThat(open.question()).isEqualTo(QUESTION);
        assertThat(open.askedBy()).isEqualTo(PHARMACIST);
        assertThat(open.orderVersion()).isEqualTo(1);
        assertThat(result.checks().queried()).isEqualTo(1);
        assertThat(result.checks().waiting()).isZero();
        assertThat(result.checks().items().get(0).status()).isEqualTo("QUERIED");
        assertThat(result.checks().items().get(0).canVerify()).isFalse();
        assertThat(result.checks().items().get(0).canQuery()).isFalse();
        assertThat(result.checks().items().get(0).query().question()).isEqualTo(QUESTION);
    }

    @Test
    void theEventAndTheAuditRowHoldNoQuestionAndNothingIsNoticedToTheNurses() {
        query(1, QUESTION);

        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_VERIFY, "Queried Tab. Paracetamol 650 mg",
                PHARMACIST);
        Map<String, Object> after = auditAfter("IPD_MED_QUERIED");
        assertThat(after).containsEntry("orderVersion", 1).containsEntry("orderId", pending.id()).containsKey("device");
        assertThat(after.toString()).doesNotContain("40 kg");
        assertThat(notices.recorded).isEmpty();
    }

    @Test
    void theOrdererCannotQueryHerOwnOrderAndNothingIsWritten() {
        memberRoles.put(DOCTOR, "OWNER");

        assertThatThrownBy(() -> service.query(TENANT, DOCTOR, "OWNER", pending.id(),
                new QueryOrderInput(KEY, 1, QUESTION))).isInstanceOf(ForbiddenException.class)
                .hasMessage("The doctor who ordered this cannot check it. Ask the pharmacist.")
                .satisfies(e -> assertThat(((ForbiddenException) e).problemType()).isEqualTo("self-verification"));

        assertThat(fake.writes).isZero();
    }

    @Test
    void theDoctorWhoChangedTheOrderCannotQueryItAndNothingIsWritten() {
        memberRoles.put(OTHER_DOCTOR, "OWNER");
        appendVersionBy(pending, OTHER_DOCTOR);

        assertThatThrownBy(() -> service.query(TENANT, OTHER_DOCTOR, "OWNER", pending.id(),
                new QueryOrderInput(KEY, 1, QUESTION))).isInstanceOf(ForbiddenException.class)
                .hasMessage("The doctor who changed this order cannot check it. Ask the pharmacist.")
                .satisfies(e -> {
                    assertThat(((ForbiddenException) e).problemType()).isEqualTo("self-verification");
                    assertThat(((ForbiddenException) e).title()).isEqualTo("You cannot check your own order");
                });

        assertThat(fake.writes).isZero();
        verify(auditLog, org.mockito.Mockito.never()).record(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void theOrdererIsStillToldTheOrdererTextWhenShePlacedAndChangedIt() {
        memberRoles.put(DOCTOR, "OWNER");
        appendVersionBy(pending, DOCTOR);

        assertThatThrownBy(() -> service.query(TENANT, DOCTOR, "OWNER", pending.id(),
                new QueryOrderInput(KEY, 1, QUESTION))).isInstanceOf(ForbiddenException.class)
                .hasMessage("The doctor who ordered this cannot check it. Ask the pharmacist.");
    }

    @Test
    void anotherPharmacistCanQueryAnOrderThatADoctorChanged() {
        appendVersionBy(pending, OTHER_DOCTOR);

        assertThat(service.query(TENANT, PHARMACIST, "PHARMACIST", pending.id(), new QueryOrderInput(KEY, 1, QUESTION))
                .result().outcome()).isEqualTo("QUERIED");
    }

    @Test
    void anOrderThatIsNotPendingCannotBeQueried() {
        OrderRow active = fake.seed(ADMISSION, "met", "ACTIVE", DOCTOR);

        assertThatThrownBy(() -> service.query(TENANT, PHARMACIST, "PHARMACIST", active.id(),
                new QueryOrderInput(KEY, 1, QUESTION))).isInstanceOf(ConflictException.class)
                .hasMessage("This order is not waiting for the pharmacist.")
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("order-state"));
    }

    @Test
    void aQueryOfAnOtherVersionIs409OrderChanged() {
        assertThatThrownBy(() -> query(2, QUESTION)).isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("order-changed"));

        assertThat(fake.rows.get(pending.id()).status()).isEqualTo("PENDING");
    }

    @Test
    void aRepeatByTheSamePharmacistWithTheSameQuestionWritesNothing() {
        query(1, QUESTION);
        fake.writes = 0;

        PharmacistActionResult again = query(1, QUESTION);

        assertThat(again.result().replayed()).isTrue();
        assertThat(fake.writes).isZero();
        assertThat(fake.queryRows.get(pending.id())).hasSize(1);
    }

    @Test
    void aQueryIsAllowedInReadOnlyIpdAndOnlyForPharmacistAndOwner() {
        ipdState("readonly");
        assertThat(query(1, QUESTION).result().outcome()).isEqualTo("QUERIED");

        assertThatThrownBy(() -> service.query(TENANT, NURSE, "NURSE", pending.id(),
                new QueryOrderInput(KEY, 1, QUESTION))).isInstanceOf(ForbiddenException.class)
                .hasMessage("Only the pharmacist or the clinic owner can check orders.");
    }

    @Test
    void aBadQuestionIsA400AndNothingIsLocked() {
        assertThatThrownBy(() -> query(1, " ")).isInstanceOf(InvalidFieldsException.class);

        verifyNoInteractions(admissions);
    }

    // ---- reply ----

    @Test
    void aReplyClosesTheQueryAndTheOrderIsPendingAgain() {
        query(1, QUESTION);

        EmrSheet sheet = reply("DOCTOR", DOCTOR, 1, REPLY);

        assertThat(fake.rows.get(pending.id()).status()).isEqualTo("PENDING");
        QueryRow closed = fake.findLatest(TENANT, List.of(pending.id())).get(pending.id());
        assertThat(closed.closedBy()).isEqualTo("REPLY");
        assertThat(closed.reply()).isEqualTo(REPLY);
        assertThat(closed.repliedBy()).isEqualTo(DOCTOR);
        assertThat(sheet.orders().get(0).query().reply()).isEqualTo(REPLY);
        assertThat(sheet.orders().get(0).query().repliedByName()).isEqualTo("Dr Meera Joshi");
        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_VERIFY,
                "Replied to query on Tab. Paracetamol 650 mg", DOCTOR);
        assertThat(auditAfter("IPD_MED_QUERY_REPLIED").toString()).doesNotContain("paediatric");
    }

    @Test
    void anyDoctorMayReplyNotOnlyTheOrderer() {
        query(1, QUESTION);

        reply("DOCTOR", OTHER_DOCTOR, 1, REPLY);

        assertThat(fake.findLatest(TENANT, List.of(pending.id())).get(pending.id()).repliedBy()).isEqualTo(OTHER_DOCTOR);
    }

    @Test
    void aReplyToAnOrderWithNoOpenQueryIs409() {
        assertThatThrownBy(() -> reply("DOCTOR", DOCTOR, 1, REPLY)).isInstanceOf(ConflictException.class)
                .hasMessage("This order has no open query.");
    }

    @Test
    void aReplyOfAnOtherVersionIs409OrderChanged() {
        query(1, QUESTION);

        assertThatThrownBy(() -> reply("DOCTOR", DOCTOR, 2, REPLY)).isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("order-changed"));
    }

    @Test
    void aRepeatedReplyBySameDoctorWritesNothing() {
        query(1, QUESTION);
        reply("DOCTOR", DOCTOR, 1, REPLY);
        fake.writes = 0;

        EmrSheet again = reply("DOCTOR", DOCTOR, 1, REPLY);

        assertThat(again.orders().get(0).status()).isEqualTo("PENDING");
        assertThat(fake.writes).isZero();
    }

    @Test
    void aReplyIsRefusedInReadOnlyIpdAndForANurse() {
        query(1, QUESTION);
        assertThatThrownBy(() -> reply("NURSE", NURSE, 1, REPLY)).isInstanceOf(ForbiddenException.class)
                .hasMessage("Only a doctor can do this.");
        ipdState("readonly");

        assertThatThrownBy(() -> reply("DOCTOR", DOCTOR, 1, REPLY)).isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("ipd-readonly"));
        assertThat(fake.rows.get(pending.id()).status()).isEqualTo("QUERIED");
    }

    @Test
    void aNurseReadingTheSheetNeverSeesTheQuestion() {
        query(1, QUESTION);
        EmrSheet forNurse = assembler.sheet(TENANT, "NURSE", "NURSE", entitlements.forTenant(TENANT), admission,
                patients.find(TENANT, PATIENT).orElseThrow(), fake.findByAdmission(TENANT, ADMISSION), fake.now);
        EmrSheet forDoctor = assembler.sheet(TENANT, "DOCTOR", "DOCTOR", entitlements.forTenant(TENANT), admission,
                patients.find(TENANT, PATIENT).orElseThrow(), fake.findByAdmission(TENANT, ADMISSION), fake.now);

        assertThat(forNurse.orders().get(0).query()).isNull();
        assertThat(forDoctor.orders().get(0).query().question()).isEqualTo(QUESTION);
        assertThat(forDoctor.orders().get(0).canReply()).isTrue();
        assertThat(forNurse.orders().get(0).canReply()).isFalse();
    }

    @Test
    void replyOutsideScopeWritesNothing() {
        query(1, QUESTION);
        org.mockito.Mockito.clearInvocations(patientAccess, events, auditLog);
        doThrow(new OutsideScopeException()).when(guard).requireAdmissionLocked(eq(TENANT), eq(DOCTOR), eq("DOCTOR"),
                any(), eq(ADMISSION), anyString());

        assertThatThrownBy(() -> reply("DOCTOR", DOCTOR, 1, REPLY))
                .isInstanceOf(OutsideScopeException.class);

        assertThat(fake.rows.get(pending.id()).status()).isEqualTo("QUERIED");
        verifyNoInteractions(patientAccess, events, auditLog);
    }
}
