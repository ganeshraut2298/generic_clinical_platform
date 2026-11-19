package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.pharmacy.application.PharmacyInputs.Sent;
import com.sahaj.pharmacy.application.PharmacyMasterInputs.TemplateSave;
import com.sahaj.pharmacy.domain.IntegrationMode;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-78-10, print templates: settings only, fixed fields, and the invoice that waits for the unit's GSTIN and licence. */
class PrintTemplateServiceTest {

    private static final UUID TENANT = PharmacyFixture.TENANT;

    private final MasterFixture m = new MasterFixture();
    private final PharmacyFixture f = m.f;

    private PharmacyMasterViews.PrintTemplate row(String type) {
        return m.templateService.list(TENANT, f.owner.userId()).items().stream().filter(t -> t.docType().equals(type))
                .findFirst().orElseThrow();
    }

    private PharmacyMasterViews.PrintTemplate save(String type, TemplateSave in) {
        return m.templateService.save(TENANT, f.owner.userId(), type, in);
    }

    private static TemplateSave template(long version, List<String> lines, String footer, String paper) {
        return new TemplateSave(version, lines, Sent.of(footer), paper);
    }

    private List<FieldMessage> errorsOf(Runnable call) {
        try {
            call.run();
        } catch (InvalidFieldsException e) {
            return e.errors();
        }
        throw new AssertionError("expected a validation error");
    }

    @Test
    void threeTemplatesWithTheDefaultsAndTheFixedFieldsAreServedBeforeAnythingIsSaved() {
        PharmacyMasterViews.PrintTemplateList list = m.templateService.list(TENANT, f.pharmacist.userId());

        assertThat(list.items()).extracting(PharmacyMasterViews.PrintTemplate::docType)
                .containsExactly("INVOICE", "ISSUE_SLIP", "DEBIT_NOTE");
        assertThat(list.items()).allSatisfy(t -> {
            assertThat(t.saved()).isFalse();
            assertThat(t.version()).isZero();
            assertThat(t.headerLines()).isEmpty();
            assertThat(t.footerNote()).isNull();
            assertThat(t.paperSize()).isEqualTo("A4");
            assertThat(t.fixedFields()).contains("UNIT_NAME", "DOCUMENT_NO", "DATE");
        });
        assertThat(row("INVOICE").fixedFields()).containsExactly("UNIT_NAME", "DOCUMENT_NO", "DATE", "GSTIN",
                "DRUG_LICENCE_NO", "HSN", "BATCH", "EXPIRY", "MRP", "GST_AMOUNTS");
        assertThat(row("ISSUE_SLIP").fixedFields()).containsExactly("UNIT_NAME", "DOCUMENT_NO", "DATE");
        assertThat(row("INVOICE").defaultHeaderLines()).containsExactly("Test Clinic Baner");
        assertThat(m.writes).isEmpty();
    }

    @Test
    void theInvoiceIsNotPrintableUntilTheUnitSettingsHoldTheGstinAndTheLicenceNumber() {
        PharmacyMasterViews.PrintTemplate before = row("INVOICE");
        assertThat(before.printable()).isFalse();
        assertThat(before.missing()).containsExactly("GSTIN", "DRUG_LICENCE_NO");
        assertThat(row("DEBIT_NOTE").printable()).isTrue();
        assertThat(row("DEBIT_NOTE").missing()).isEmpty();

        f.unitService.save(TENANT, f.owner.userId(), PharmacyInputs.UnitSettings.of(0L, "27AAAAA0000A1Z5", null, null,
                IntegrationMode.INTEGRATED));
        assertThat(row("INVOICE").missing()).containsExactly("DRUG_LICENCE_NO");

        f.unitService.save(TENANT, f.owner.userId(), PharmacyInputs.UnitSettings.of(1L, "27AAAAA0000A1Z5", "TEST-DL-0001",
                null, IntegrationMode.INTEGRATED));
        PharmacyMasterViews.PrintTemplate ready = row("INVOICE");
        assertThat(ready.printable()).isTrue();
        assertThat(ready.missing()).isEmpty();
    }

    @Test
    void aSaveStoresTheLinesFooterAndPaperAndAnotherTypeIsUntouched() {
        PharmacyMasterViews.PrintTemplate saved = save("INVOICE", template(0, List.of(" Test Clinic Baner Pharmacy ", "Test Road",
                "Pune 411045"), " Goods once sold are returned only as per the return policy. ", "A5"));

        assertThat(saved.saved()).isTrue();
        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.headerLines()).containsExactly("Test Clinic Baner Pharmacy", "Test Road", "Pune 411045");
        assertThat(saved.footerNote()).isEqualTo("Goods once sold are returned only as per the return policy.");
        assertThat(saved.paperSize()).isEqualTo("A5");
        assertThat(row("ISSUE_SLIP").saved()).isFalse();
        assertThat(f.auditActions()).contains("PHARMACY_PRINT_TEMPLATE_CHANGED");

        PharmacyMasterViews.PrintTemplate cleared = save("INVOICE", template(1, List.of(), "  ", "A4"));
        assertThat(cleared.version()).isEqualTo(2);
        assertThat(cleared.headerLines()).isEmpty();
        assertThat(cleared.footerNote()).isNull();
    }

    @Test
    void aSaveWithNoChangeWritesNothing() {
        save("DEBIT_NOTE", template(0, List.of("Test Clinic"), null, "A4"));
        m.writes.clear();
        f.audit.clear();

        PharmacyMasterViews.PrintTemplate same = save("DEBIT_NOTE", template(1, List.of("Test Clinic"), null, "A4"));

        assertThat(same.version()).isEqualTo(1);
        assertThat(m.writes).isEmpty();
        assertThat(f.audit).isEmpty();
    }

    @Test
    void theContractChecksAreReportedTogether() {
        assertThat(errorsOf(() -> save("INVOICE", template(0, List.of("a", "b", "c", "d"), "x".repeat(201), "A3"))))
                .contains(new FieldMessage("headerLines", "Use up to 3 header lines"),
                        new FieldMessage("footerNote", "Keep the footer to 200 characters"),
                        new FieldMessage("paperSize", "Choose A4 or A5"));
        assertThat(errorsOf(() -> save("INVOICE", template(0, List.of("ok", "y".repeat(81), " "), null, "A4"))))
                .containsExactly(new FieldMessage("headerLines[1]", "Keep each header line to 80 characters"),
                        new FieldMessage("headerLines[2]", "Keep each header line to 80 characters"));
        assertThat(errorsOf(() -> save("INVOICE", new TemplateSave(null, null, Sent.absent(), null))))
                .extracting(FieldMessage::field).contains("version", "headerLines", "footerNote", "paperSize");
        assertThat(save("INVOICE", template(0, Arrays.asList("y".repeat(80)), "z".repeat(200), "A4")).saved()).isTrue();
    }

    @Test
    void aStaleVersionIsSettingsChangedWithTheCurrentTemplate() {
        save("ISSUE_SLIP", template(0, List.of("One"), null, "A4"));

        assertThatThrownBy(() -> save("ISSUE_SLIP", template(0, List.of("Two"), null, "A4")))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("settings-changed");
                    assertThat(((PharmacyMasterViews.PrintTemplate) e.extensions().get("current")).headerLines())
                            .containsExactly("One");
                });
    }

    @Test
    void anUnknownDocumentTypeIs400BeforeTheBodyIsRead() {
        PharmacyInputs.Body<TemplateSave> unreadable = () -> {
            throw new IllegalStateException("the body must not be read before the path");
        };

        assertThatThrownBy(() -> m.templateService.save(TENANT, f.owner.userId(), "RECEIPT", unreadable))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("bad-request"));
    }

    @Test
    void aPharmacistReadsAndNobodyButTheOwnerSaves() {
        PharmacyInputs.Body<TemplateSave> unreadable = () -> {
            throw new IllegalStateException("the body must not be read before the gate");
        };

        assertThat(m.templateService.list(TENANT, f.pharmacist.userId()).items()).hasSize(3);
        assertThatThrownBy(() -> m.templateService.save(TENANT, f.pharmacist.userId(), "INVOICE", unreadable))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> m.templateService.list(TENANT, f.frontDesk.userId())).isInstanceOf(ForbiddenException.class);
    }
}
