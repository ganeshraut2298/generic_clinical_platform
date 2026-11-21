package com.sahaj.pharmacy.application;

import com.sahaj.patient.api.PatientAccessPort;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.pharmacy.domain.IntegrationMode;
import com.sahaj.pharmacy.domain.PrintDocType;
import com.sahaj.pharmacy.domain.SaleRules;
import com.sahaj.pharmacy.domain.Unit;
import com.sahaj.platform.api.ClinicLetterheadPort;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * What the sale services share (SAH-80): the Integrated or Standalone mode, the patient reads through {@code patient.api} (a
 * failing port is 503 {@code patients-unreachable}, ADR-030 S6), the credit line of a customer and the printed header. Names,
 * mobile numbers and allergy text pass through here for the screen only; nothing is logged, audited or put in the ledger
 * (rule 29). The patient ports run in the caller's transaction.
 */
@Component
public class SaleSupport {

    private static final Logger log = LoggerFactory.getLogger(SaleSupport.class);
    static final String NOT_FOUND = "This record was not found.";

    private final UnitRepository units;
    private final PatientLookupPort patients;
    private final PatientClinicalPort clinical;
    private final PatientAccessPort access;
    private final TeamMemberPort team;
    private final PrintTemplateRepository templates;
    private final ClinicLetterheadPort letterhead;
    private final PharmacyTime time;

    public SaleSupport(UnitRepository units, PatientLookupPort patients, PatientClinicalPort clinical,
                       PatientAccessPort access, TeamMemberPort team, PrintTemplateRepository templates,
                       ClinicLetterheadPort letterhead, PharmacyTime time) {
        this.units = units;
        this.patients = patients;
        this.clinical = clinical;
        this.access = access;
        this.team = team;
        this.templates = templates;
        this.letterhead = letterhead;
        this.time = time;
    }

    /** One patient as the sale screen needs it. {@code mobile} is filled only when the call asked for it. */
    public record PatientCard(UUID id, String name, int age, String sex, String uhid, String mobile) {

        @Override
        public String toString() {
            return "PatientCard[id=" + id + "]";
        }
    }

    // ---- mode ----

    /** Integrated mode; a clinic that has no unit row yet is Standalone (the default). */
    public boolean integrated(UUID tenantId) {
        return units.find(tenantId).map(Unit::integrationMode).orElse(IntegrationMode.STANDALONE)
                == IntegrationMode.INTEGRATED;
    }

    /** 409 integration-off for an Integrated-only operation on a Standalone unit. */
    public void requireIntegrated(UUID tenantId) {
        if (!integrated(tenantId)) {
            throw new IntegrationOffException();
        }
    }

    // ---- patients (TP-1, TP-3) ----

    /** The patient the record was merged into when merged away. 404 when unknown or of another clinic; 503 when the port fails. */
    public PatientCard patient(UUID tenantId, UUID patientId, boolean withMobile) {
        Map<UUID, PatientCard> found = patientCards(tenantId, List.of(patientId), withMobile);
        PatientCard card = found.get(patientId);
        if (card == null) {
            throw new NotFoundException(NOT_FOUND);
        }
        return card;
    }

    /** Many patients in one read, keyed by the id asked for; unknown ids are absent; a failing port is 503. */
    public Map<UUID, PatientCard> patientCards(UUID tenantId, Collection<UUID> patientIds, boolean withMobile) {
        Map<UUID, PatientCard> out = new HashMap<>();
        if (patientIds.isEmpty()) {
            return out;
        }
        try {
            Map<UUID, PatientLookupPort.PatientSummary> summaries = patients.findAllCurrent(tenantId, patientIds);
            Map<UUID, String> mobiles = withMobile ? patients.mobilesOf(tenantId, patientIds) : Map.of();
            summaries.forEach((id, s) -> out.put(id, new PatientCard(s.id(), s.name(), s.age(), s.sex(), s.uhid(),
                    mobiles.get(id))));
        } catch (DataAccessException | IllegalStateException failed) {
            log.warn("Patient read failed class={}", failed.getClass().getSimpleName());
            throw PharmacyProblemException.patientsUnreachable();
        }
        return out;
    }

    /**
     * The allergy facts of one patient for a quote or a bill; empty when the clinical read fails or the patient is unknown (the
     * card then says "Allergy not checked"). The text is never logged.
     */
    public Optional<PatientClinicalPort.PatientClinical> clinical(UUID tenantId, UUID patientId) {
        try {
            return clinical.find(tenantId, patientId);
        } catch (DataAccessException | IllegalStateException failed) {
            log.warn("Allergy read failed class={}", failed.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** A record-access entry for a registered patient shown on screen (ids only). A failure is 503 patients-unreachable. */
    public void recordOpened(UUID tenantId, UUID patientId, UUID userId) {
        try {
            access.recordOpened(tenantId, patientId, userId);
        } catch (DataAccessException | IllegalStateException failed) {
            log.warn("Record access failed class={}", failed.getClass().getSimpleName());
            throw PharmacyProblemException.patientsUnreachable();
        }
    }

    /**
     * The allergy card of a registered patient: RECORDED with the text as recorded, NONE_RECORDED when nothing (or a none-word) is
     * recorded, NOT_CHECKED when the clinical read failed (empty). Never logged or audited (rule 29).
     */
    public static SaleViews.AllergyInfo allergyInfo(Optional<PatientClinicalPort.PatientClinical> clinical) {
        if (clinical.isEmpty()) {
            return SaleViews.AllergyInfo.of("NOT_CHECKED", null);
        }
        PatientClinicalPort.PatientClinical facts = clinical.get();
        switch (facts.allergyStatus()) {
            case RECORDED:
                return SaleViews.AllergyInfo.of("RECORDED", facts.allergyText().trim());
            default:
                return SaleViews.AllergyInfo.of("NONE_RECORDED", null);
        }
    }

    // ---- customers ----

    /** The credit line of a customer; the over-60 figure is for the owner and a pharmacist only. */
    public SaleViews.CreditLine creditLine(PharmacyCaller caller, CustomerRepository.Row row, long creditBillsLast60Days) {
        Long over60 = caller.isOwner() || caller.isPharmacist()
                ? SaleRules.over60(row.duePaise(), creditBillsLast60Days) : null;
        return new SaleViews.CreditLine(row.id(), row.creditLimitPaise(), row.duePaise(), over60,
                row.creditNoteBalancePaise());
    }

    /** The start of the 60-day window: 00:00 IST sixty days ago. */
    public Instant sixtyDaysAgo() {
        return time.today().minusDays(60).atStartOfDay(PharmacyTime.IST).toInstant();
    }

    // ---- small helpers ----

    /** A whole number of paise from 0 to {@code max}; null when it is not one. */
    public static Long wholePaise(BigDecimal value, long max) {
        if (value == null || value.stripTrailingZeros().scale() > 0) {
            return null;
        }
        if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(max)) > 0) {
            return null;
        }
        return value.longValueExact();
    }

    /** Makes {@code %}, {@code _} and the escape character literal in a LIKE pattern. */
    public static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public static String blank(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    /** The team members of these membership ids (an unknown id is absent). */
    public Map<UUID, TeamMember> members(UUID tenantId, Collection<UUID> membershipIds) {
        return membershipIds.isEmpty() ? Map.of() : team.findAll(tenantId, membershipIds);
    }

    /** A person of the contract, "Unknown member" when the membership is gone. */
    public PharmacyViews.Person person(UUID membershipId, Map<UUID, TeamMember> members) {
        return StoreUseSupport.person(membershipId, members);
    }

    public PurchaseViews.PrintHeader invoiceHeader(UUID tenantId) {
        PrintTemplateRepository.Row template = templates.find(tenantId, PrintDocType.INVOICE).orElse(null);
        Unit unit = units.find(tenantId).orElse(null);
        return new PurchaseViews.PrintHeader("INVOICE", template == null ? List.of() : template.headerLines(),
                template == null ? null : template.footerNote(), template == null ? "A4" : template.paperSize(),
                unit == null ? null : unit.gstin(), unit == null ? null : unit.drugLicenceNo());
    }

    public String unitName(UUID tenantId) {
        return letterhead.find(tenantId).map(ClinicLetterheadPort.Letterhead::clinicName).orElse("");
    }
}
