package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.PharmacyInputs.Sent;
import com.sahaj.pharmacy.application.PharmacyMasterInputs.TemplateSave;
import com.sahaj.pharmacy.application.PrintTemplateRepository.Row;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.PrintDocType;
import com.sahaj.pharmacy.domain.Unit;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicLetterheadPort;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-78-10, print templates (docs/api/pharmacy.yaml): header lines, footer note and paper size of the invoice, the issue slip
 * and the debit note. Settings only: the preview is the browser print view and the server makes no file. The fixed fields
 * cannot be hidden. An invoice cannot be printed until the GSTIN and the drug licence number are set in the unit settings.
 * Until a template is saved its defaults are served (saved false, version 0): no header lines (the print uses the clinic's
 * name, address and phone), no footer, A4.
 */
@Service
public class PrintTemplateService {

    static final String HEADER_MESSAGE = "Keep each header line to 80 characters";
    static final String FOOTER_MESSAGE = "Keep the footer to 200 characters";
    static final String PAPER_MESSAGE = "Choose A4 or A5";
    static final String DEFAULT_PAPER = "A4";
    private static final String ENTITY_TYPE = "PHARMACY_PRINT_TEMPLATE";

    private final PharmacyGate gate;
    private final UnitService units;
    private final UnitRepository unitRows;
    private final PrintTemplateRepository templates;
    private final ClinicLetterheadPort letterhead;
    private final AuditLog auditLog;

    public PrintTemplateService(PharmacyGate gate, UnitService units, UnitRepository unitRows,
                                PrintTemplateRepository templates, ClinicLetterheadPort letterhead, AuditLog auditLog) {
        this.gate = gate;
        this.units = units;
        this.unitRows = unitRows;
        this.templates = templates;
        this.letterhead = letterhead;
        this.auditLog = auditLog;
    }

    /** Three rows in the order INVOICE, ISSUE_SLIP, DEBIT_NOTE. Writes nothing. */
    @Transactional(readOnly = true)
    public PharmacyMasterViews.PrintTemplateList list(UUID tenantId, UUID userId) {
        gate.require(tenantId, userId, AccessLevel.SETUP_VIEW);
        return new PharmacyMasterViews.PrintTemplateList(views(tenantId));
    }

    /** For tests: the body is already read. */
    PharmacyMasterViews.PrintTemplate save(UUID tenantId, UUID userId, String rawDocType, TemplateSave input) {
        return save(tenantId, userId, rawDocType, PharmacyInputs.Body.of(input));
    }

    /** Owner only. An unknown document type is 400 bad-request, read before the body. */
    @Transactional
    public PharmacyMasterViews.PrintTemplate save(UUID tenantId, UUID userId, String rawDocType,
                                                  PharmacyInputs.Body<TemplateSave> body) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        PrintDocType type = docType(rawDocType);
        TemplateSave in = body.read();
        Checked checked = check(in);

        Unit unit = units.ensureUnit(tenantId, userId);
        Row row = templates.find(tenantId, type).orElse(null);
        long currentVersion = row == null ? 0L : row.version();
        if (checked.version() != currentVersion) {
            throw PharmacyProblemException.settingsChanged(view(tenantId, type, row, unit));
        }
        List<String> wasLines = row == null ? List.of() : row.headerLines();
        String wasFooter = row == null ? null : row.footerNote();
        String wasPaper = row == null ? DEFAULT_PAPER : row.paperSize();
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        if (!wasLines.equals(checked.headerLines())) {
            before.put("headerLines", wasLines.toString());
            after.put("headerLines", checked.headerLines().toString());
        }
        if (!java.util.Objects.equals(wasFooter, checked.footerNote())) {
            before.put("footerNote", wasFooter);
            after.put("footerNote", checked.footerNote());
        }
        if (!wasPaper.equals(checked.paperSize())) {
            before.put("paperSize", wasPaper);
            after.put("paperSize", checked.paperSize());
        }
        if (row != null && before.isEmpty()) {
            return view(tenantId, type, row, unit);
        }
        UUID id;
        if (row == null) {
            id = templates.insert(tenantId, unit.id(), type, checked.headerLines(), checked.footerNote(),
                    checked.paperSize(), userId);
        } else {
            id = row.id();
            if (!templates.update(tenantId, id, row.version(), checked.headerLines(), checked.footerNote(),
                    checked.paperSize(), userId)) {
                throw PharmacyProblemException.settingsChanged(view(tenantId, type,
                        templates.find(tenantId, type).orElse(row), unit));
            }
        }
        before.put("docType", type.name());
        after.put("docType", type.name());
        auditLog.record("PHARMACY_PRINT_TEMPLATE_CHANGED", ENTITY_TYPE, id, before, after);
        return view(tenantId, type, templates.find(tenantId, type).orElseThrow(), unit);
    }

    // ---- views ----

    private List<PharmacyMasterViews.PrintTemplate> views(UUID tenantId) {
        Unit unit = unitRows.find(tenantId).orElse(null);
        Map<PrintDocType, Row> byType = new LinkedHashMap<>();
        templates.list(tenantId).forEach(row -> byType.put(row.docType(), row));
        List<PharmacyMasterViews.PrintTemplate> items = new ArrayList<>();
        for (PrintDocType type : PrintDocType.values()) {
            items.add(view(tenantId, type, byType.get(type), unit));
        }
        return items;
    }

    private PharmacyMasterViews.PrintTemplate view(UUID tenantId, PrintDocType type, Row row, Unit unit) {
        List<String> missing = new ArrayList<>();
        if (type == PrintDocType.INVOICE) {
            if (unit == null || blank(unit.gstin())) {
                missing.add("GSTIN");
            }
            if (unit == null || blank(unit.drugLicenceNo())) {
                missing.add("DRUG_LICENCE_NO");
            }
        }
        return new PharmacyMasterViews.PrintTemplate(type.name(), row == null ? List.of() : row.headerLines(),
                defaultHeaderLines(tenantId), row == null ? null : row.footerNote(),
                row == null ? DEFAULT_PAPER : row.paperSize(), type.fixedFields(), missing.isEmpty(), missing,
                row != null, row == null ? 0L : row.version());
    }

    /** Clinic name, address and phone from the clinic profile, the ones that are set (at most three). */
    private List<String> defaultHeaderLines(UUID tenantId) {
        List<String> lines = new ArrayList<>();
        letterhead.find(tenantId).ifPresent(l -> {
            for (String line : new String[] {l.clinicName(), l.address(), l.phone()}) {
                if (!blank(line)) {
                    lines.add(line.trim());
                }
            }
        });
        return lines;
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    // ---- checks ----

    private record Checked(long version, List<String> headerLines, String footerNote, String paperSize) {
    }

    private static PrintDocType docType(String raw) {
        if (raw != null) {
            for (PrintDocType type : PrintDocType.values()) {
                if (type.name().equals(raw)) {
                    return type;
                }
            }
        }
        throw PharmacyProblemException.badRequest();
    }

    /** All field errors together (400 validation); nothing typed is echoed back. */
    private static Checked check(TemplateSave in) {
        List<FieldMessage> errors = new ArrayList<>();
        if (in.version() == null || in.version() < 0) {
            errors.add(new FieldMessage("version", UnitService.RELOAD));
        }
        List<String> lines = new ArrayList<>();
        if (in.headerLines() == null) {
            errors.add(new FieldMessage("headerLines", UnitService.REQUIRED));
        } else if (in.headerLines().size() > PrintDocType.HEADER_LINES_MAX) {
            errors.add(new FieldMessage("headerLines", "Use up to 3 header lines"));
        } else {
            for (int i = 0; i < in.headerLines().size(); i++) {
                String line = in.headerLines().get(i) == null ? "" : in.headerLines().get(i).trim();
                if (line.isEmpty() || line.length() > PrintDocType.HEADER_LINE_MAX) {
                    errors.add(new FieldMessage("headerLines[" + i + "]", HEADER_MESSAGE));
                } else {
                    lines.add(line);
                }
            }
        }
        String footer = null;
        if (Sent.missing(in.footerNote())) {
            errors.add(new FieldMessage("footerNote", UnitService.REQUIRED));
        } else {
            String raw = Sent.valueOf(in.footerNote());
            footer = raw == null || raw.isBlank() ? null : raw.trim();
            if (footer != null && footer.length() > PrintDocType.FOOTER_MAX) {
                errors.add(new FieldMessage("footerNote", FOOTER_MESSAGE));
            }
        }
        if (!"A4".equals(in.paperSize()) && !"A5".equals(in.paperSize())) {
            errors.add(new FieldMessage("paperSize", PAPER_MESSAGE));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        return new Checked(in.version(), lines, footer, in.paperSize());
    }
}
