package com.sahaj.opd.application.emr.specialty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code opd.chemo_order} (ADR-023 D6, docs/database/SAH-49-71.md). A mutable holder: the service reads the
 * row under its lock, changes what the step changes and writes it back as one update with {@code revision + 1}. The
 * json columns hold the doctor's inputs and the server-computed snapshot (doses, labs, checks): clinical data, never
 * logged or audited by value; {@link #toString()} shows the id and status only.
 */
public final class ChemoOrderRow {

    public UUID id;
    public UUID recordId;
    public Instant recordCreatedAt;
    public UUID patientId;
    public UUID courseId;
    public Integer cycleNo;
    public String status = "DRAFT";
    public String protocolKey;
    public int levelPct = 100;
    public String reductionReasonKey;
    public String reductionReasonText;
    public String inputs = "{}";
    public String snapshot = "{}";
    public String fingerprint;
    public BigDecimal doxorubicinMgM2 = BigDecimal.ZERO;
    public BigDecimal priorOutsideMgM2 = BigDecimal.ZERO;
    public UUID prescriberMembershipId;
    public UUID lastEditedByMembershipId;
    public UUID checkerMembershipId;
    public String checkerRole;
    public Instant checkerNamedAt;
    public UUID checkerNamedBy;
    public UUID confirmedByMembershipId;
    public UUID confirmedByUserId;
    public String confirmedVia;
    public Instant confirmedAt;
    public String confirmedFingerprint;
    public Instant confirmationClearedAt;
    public Instant sentAt;
    public UUID sentBy;
    public String holdKind;
    public String holdReason;
    public Instant heldAt;
    public long revision;
    public Instant createdAt;

    public boolean confirmed() {
        return confirmedAt != null;
    }

    public boolean draft() {
        return "DRAFT".equals(status);
    }

    @Override
    public String toString() {
        return "ChemoOrderRow[id=" + id + ", status=" + status + "]";
    }
}
