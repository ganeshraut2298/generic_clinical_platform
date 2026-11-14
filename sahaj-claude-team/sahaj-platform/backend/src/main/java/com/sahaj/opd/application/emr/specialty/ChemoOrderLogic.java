package com.sahaj.opd.application.emr.specialty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.EmrRecordDocument;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.specialty.ChemoOrderCompute.Computed;
import com.sahaj.opd.application.emr.specialty.ChemoOrderCompute.DrugResult;
import com.sahaj.opd.application.emr.specialty.ChemoOrderCompute.OrderInputs;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.ChemoInfo;
import com.sahaj.opd.domain.emr.specialty.ChemoRules;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.Check;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.Premed;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.Protocol;
import com.sahaj.opd.domain.emr.specialty.OncologyRules;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What the chemotherapy order services share (ADR-023 D6): the fresh recomputation of an order from the record as it
 * stands now, the stored inputs, and the answers (the order sheet and the checker's summary) built from it. The doses,
 * the lifetime dose, the ten checks and the fingerprint are always worked out here on the server; nothing the client
 * computed is trusted. Clinical data: never logged.
 */
@Component
public class ChemoOrderLogic {

    static final Map<String, String> REDUCTION_LABELS = Map.of(
            "LOW_NEUTROPHILS_LAST_CYCLE", "Low neutrophils last cycle",
            "FEVER_LOW_COUNTS", "Fever with low counts",
            "GRADE_3_SIDE_EFFECT", "Grade 3 side effect",
            "NEUROPATHY", "Neuropathy",
            "LIVER_FUNCTION", "Liver function",
            "FRAILTY", "Frailty");

    private final SpecialtyReferenceRepository reference;
    private final SpecialtyRecordSupport support;
    private final ObjectMapper mapper;
    private final TeamMemberPort team;

    public ChemoOrderLogic(SpecialtyReferenceRepository reference, SpecialtyRecordSupport support, ObjectMapper mapper,
                           TeamMemberPort team) {
        this.reference = reference;
        this.support = support;
        this.mapper = mapper;
        this.team = team;
    }

    /** The order worked out from the record as it stands now. */
    public record Fresh(Computed computed, ChemoInfo info, String decision) {

        public String fingerprint() {
            return computed.fingerprint();
        }
    }

    public SpecialtyReferenceData reference() {
        return reference.load();
    }

    /** The default protocol of a sheet that has no order yet: the last one sent for the patient, else the first. */
    public Protocol defaultProtocol(ChemoInfo info) {
        SpecialtyReferenceData data = reference.load();
        for (Protocol protocol : data.protocols()) {
            if (info.sentProtocols().contains(protocol.key())) {
                return protocol;
            }
        }
        return data.protocols().isEmpty() ? null : data.protocols().get(0);
    }

    public Fresh fresh(UUID tenantId, RecordRow row, EmrRecordDocument document, OrderInputs inputs,
                       UUID checkerMembershipId, UUID membershipId, Instant now) {
        SpecialtyCtx ctx = support.context(tenantId, row, "onco", membershipId, now, document);
        ChemoInfo info = ctx.hooks().chemo().get();
        JsonNode choice = support.screen(row).path("oncDecision").path("choice");
        String decision = choice.isTextual() ? choice.asText() : null;
        Protocol protocol = reference.load().protocol(inputs.protocolKey());
        if (protocol == null) {
            throw new IllegalStateException("An order points at a protocol that is missing");
        }
        OncologyAssessment assessment = OncologyAssessment.compute(ctx.sibling(), info.cycle1WeightKg(), now, null);
        Computed computed = ChemoOrderCompute.compute(inputs, protocol, assessment, info, decision, checkerMembershipId);
        return new Fresh(computed, info, decision);
    }

    // ---- the stored inputs ----

    public OrderInputs parseInputs(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            Map<String, Double> doses = new LinkedHashMap<>();
            node.path("finalDoses").fields().forEachRemaining(e -> doses.put(e.getKey(), e.getValue().asDouble()));
            Set<String> premeds = new HashSet<>();
            node.path("premedsNotGiven").forEach(p -> premeds.add(p.asText()));
            return new OrderInputs(node.path("protocolKey").asText(), node.path("levelPct").asInt(100),
                    text(node, "reductionReasonKey"), text(node, "reductionReasonText"), doses, premeds,
                    node.path("allergyConfirmed").asBoolean(false), node.path("consentNewSigned").asBoolean(false),
                    text(node, "scheme"), node.path("priorOutsideMgM2").isNumber()
                    ? node.path("priorOutsideMgM2").asDouble() : null);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("A stored chemotherapy order could not be read", e);
        }
    }

    private static String text(JsonNode node, String property) {
        return node.path(property).isTextual() ? node.path(property).asText() : null;
    }

    public String writeInputs(OrderInputs in) {
        ObjectNode node = mapper.createObjectNode();
        node.put("schemaVersion", 1);
        node.put("protocolKey", in.protocolKey());
        node.put("levelPct", in.levelPct());
        putOrNull(node, "reductionReasonKey", in.reductionReasonKey());
        putOrNull(node, "reductionReasonText", in.reductionReasonText());
        ObjectNode doses = node.putObject("finalDoses");
        if (in.finalDoses() != null) {
            in.finalDoses().forEach(doses::put);
        }
        ArrayNode premeds = node.putArray("premedsNotGiven");
        if (in.premedsNotGiven() != null) {
            in.premedsNotGiven().stream().sorted().forEach(premeds::add);
        }
        node.put("allergyConfirmed", in.allergyConfirmed());
        node.put("consentNewSigned", in.consentNewSigned());
        putOrNull(node, "scheme", in.scheme());
        if (in.priorOutsideMgM2() == null) {
            node.putNull("priorOutsideMgM2");
        } else {
            node.put("priorOutsideMgM2", in.priorOutsideMgM2());
        }
        return node.toString();
    }

    private static void putOrNull(ObjectNode node, String name, String value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }

    // ---- the order sheet ----

    /**
     * The order sheet (ChemoOrderView). {@code row} is null before the first save (status NONE). For a SENT or HELD order
     * the doses of the stored snapshot are shown (the sent doses stay) and {@code factsChangedSinceSent} says whether the
     * record moved on since.
     */
    public ObjectNode sheet(UUID tenantId, ChemoOrderRow row, Fresh fresh, UUID recordId) {
        ObjectNode view = row != null && !row.draft() && row.snapshot != null && row.snapshot.length() > 2
                ? stored(row.snapshot) : draftView(tenantId, row, fresh, recordId);
        Computed c = fresh.computed();
        String status = row == null ? "NONE" : row.status;
        view.put("status", status);
        if (row == null) {
            view.putNull("id");
            view.put("revision", 0);
        } else {
            view.put("id", row.id.toString());
            view.put("revision", row.revision);
        }
        view.put("recordId", recordId.toString());
        view.put("fingerprint", row != null && !row.draft() ? row.fingerprint : c.fingerprint());
        view.put("factsChangedSinceSent", row != null && !row.draft() && !c.fingerprint().equals(row.fingerprint));
        Set<UUID> ids = new HashSet<>();
        if (row != null) {
            addId(ids, row.checkerMembershipId);
            addId(ids, row.confirmedByMembershipId);
        }
        Map<UUID, TeamMember> names = ids.isEmpty() ? Map.of() : team.findAll(tenantId, ids);
        overlayChecker(view, row, c, names);
        overlayStages(view, row, c);
        if (row != null && row.sentAt != null) {
            view.put("sentAt", row.sentAt.toString());
            String sentBy = row.sentBy == null ? null : team.findActiveByUserId(tenantId, row.sentBy)
                    .map(TeamMember::name).orElse(null);
            putOrNull(view, "sentByName", sentBy);
        } else {
            view.putNull("sentAt");
            view.putNull("sentByName");
        }
        if (row != null && "HELD".equals(row.status)) {
            ObjectNode hold = view.putObject("hold");
            hold.put("kind", row.holdKind);
            hold.put("reason", row.holdReason);
            hold.put("date", row.heldAt.atZone(SpecialtyRecordSupport.INDIA).toLocalDate().toString());
        } else {
            view.putNull("hold");
        }
        return view;
    }

    private static void addId(Set<UUID> ids, UUID id) {
        if (id != null) {
            ids.add(id);
        }
    }

    private ObjectNode stored(String json) {
        try {
            return (ObjectNode) mapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("A stored chemotherapy snapshot could not be read", e);
        }
    }

    /** The sheet content from the fresh computation (doses, checks, BSA, labs). Also what the snapshot column keeps. */
    public ObjectNode draftView(UUID tenantId, ChemoOrderRow row, Fresh fresh, UUID recordId) {
        Computed c = fresh.computed();
        OncologyAssessment a = c.assessment();
        OrderInputs in = row == null ? defaults(c, fresh) : parseInputs(row.inputs);
        ObjectNode view = mapper.createObjectNode();
        view.put("decisionRecorded", fresh.decision() != null);
        putOrNull(view, "decision", fresh.decision());
        if (row == null) {
            view.putNull("protocolKey");
        } else {
            view.put("protocolKey", c.protocol().key());
        }
        ObjectNode tags = view.putObject("protocolTags");
        for (Protocol protocol : reference.load().protocols()) {
            String tag = "CHANGE_REGIMEN".equals(fresh.decision()) && protocol.suggestedOnChange() ? "SUGGESTED"
                    : fresh.info().sentProtocols().contains(protocol.key()) ? "IN_PLAN" : "NEW_CONSENT";
            tags.put(protocol.key(), tag);
        }
        view.put("levelPct", in.levelPct());
        String reason = in.reductionReasonKey() != null ? REDUCTION_LABELS.getOrDefault(in.reductionReasonKey(),
                in.reductionReasonKey()) : in.reductionReasonText();
        putOrNull(view, "reductionReason", reason);
        ObjectNode bsa = view.putObject("bsa");
        number(bsa, "raw", a.bsa());
        number(bsa, "used", a.bsaUsed());
        bsa.put("capped", a.capped());
        bsa.put("source", "BSA from today's weight");
        number(view, "weightChangePct", a.weightChangePct());
        view.put("recalculated", a.recalculate());
        number(view, "weightKg", a.weightKg());
        ArrayNode drugs = view.putArray("drugs");
        for (DrugResult result : c.drugs()) {
            ObjectNode drug = drugs.addObject();
            drug.put("drugKey", result.drug().drugKey());
            drug.put("name", result.drug().name());
            drug.put("dosePerM2", result.drug().dosePerM2());
            var line = result.line();
            number(drug, "calculatedMg", line == null ? null : line.calculatedMg());
            number(drug, "roundedMg", line == null ? null : line.roundedMg());
            number(drug, "finalMg", line == null ? null : line.finalMg());
            drug.put("finalEdited", line != null && line.edited());
            drug.put("moreThan5Pct", line != null && line.moreThan5Pct());
            drug.put("vesicant", result.drug().vesicant());
            if (result.drug().anthracycline() && c.lifetime() != null) {
                ObjectNode lifetime = drug.putObject("lifetime");
                lifetime.put("earlierMgM2", c.lifetime().earlierMgM2());
                lifetime.put("thisCycleMgM2", c.lifetime().thisCycleMgM2());
                lifetime.put("totalMgM2", c.lifetime().totalMgM2());
                lifetime.put("limitMgM2", 450);
                lifetime.put("red", c.lifetime().red());
            } else {
                drug.putNull("lifetime");
            }
            drug.put("infusionMinutes", result.drug().infusionMinutes());
            putOrNull(drug, "note", result.drug().note());
        }
        ArrayNode premeds = view.putArray("premeds");
        for (Premed premed : c.protocol().premeds()) {
            ObjectNode node = premeds.addObject();
            node.put("key", premed.key());
            node.put("text", premed.text());
            node.put("minutes", premed.minutes());
            node.put("given", in.premedsNotGiven() == null || !in.premedsNotGiven().contains(premed.key()));
        }
        view.put("emeticRisk", c.protocol().emeticRisk());
        putOrNull(view, "labsDrawnAt", a.drawnAt() == null ? null : a.drawnAt().toString());
        view.put("allergyConfirmed", in.allergyConfirmed());
        view.put("consentNewSigned", in.consentNewSigned());
        putOrNull(view, "scheme", in.scheme());
        view.put("priorOutsideDoxorubicinMgM2", in.priorOutsideMgM2() == null ? fresh.info().latestOutsideMgM2()
                : in.priorOutsideMgM2());
        ArrayNode checks = view.putArray("checks");
        for (Check check : c.checks()) {
            ObjectNode node = checks.addObject();
            node.put("key", check.key());
            node.put("passed", check.passed());
            node.put("detail", check.detail());
            putOrNull(node, "action", check.action());
        }
        view.put("passedCount", ChemoRules.passedCount(c.checks()));
        view.put("totalCount", c.checks().size());
        return view;
    }

    private OrderInputs defaults(Computed c, Fresh fresh) {
        int level = "GIVE_REDUCED".equals(fresh.decision()) ? ChemoRules.nextLevelDown(100) : 100;
        return new OrderInputs(c.protocol().key(), level, null, null, Map.of(), Set.of(), false, false, null, null);
    }

    private static void number(ObjectNode node, String name, Double value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }

    private void overlayChecker(ObjectNode view, ChemoOrderRow row, Computed c, Map<UUID, TeamMember> names) {
        if (row != null && row.checkerMembershipId != null) {
            ObjectNode checker = view.putObject("checker");
            checker.put("membershipId", row.checkerMembershipId.toString());
            TeamMember member = names.get(row.checkerMembershipId);
            putOrNull(checker, "name", member == null ? null : member.name());
            checker.put("role", row.checkerRole);
            checker.put("namedAt", row.checkerNamedAt.toString());
        } else {
            view.putNull("checker");
        }
        ObjectNode second = view.putObject("secondCheck");
        boolean confirmed = row != null && row.confirmed() && c.fingerprint().equals(row.confirmedFingerprint);
        if (confirmed || (row != null && !row.draft() && row.confirmed())) {
            second.put("state", "CONFIRMED");
            TeamMember by = names.get(row.confirmedByMembershipId);
            putOrNull(second, "confirmedByName", by == null ? null : by.name());
            second.put("confirmedRole", row.checkerRole);
            second.put("via", row.confirmedVia);
            second.put("confirmedAt", row.confirmedAt.toString());
        } else {
            String state = row == null || row.checkerMembershipId == null
                    ? (row != null && row.confirmationClearedAt != null ? "CLEARED" : "NOT_NAMED")
                    : row.confirmationClearedAt != null ? "CLEARED" : "WAITING";
            second.put("state", state);
            second.putNull("confirmedByName");
            second.putNull("confirmedRole");
            second.putNull("via");
            second.putNull("confirmedAt");
        }
    }

    private void overlayStages(ObjectNode view, ChemoOrderRow row, Computed c) {
        boolean confirmed = row != null && row.confirmed() && c.fingerprint().equals(row.confirmedFingerprint);
        boolean sent = row != null && "SENT".equals(row.status);
        boolean allPass = c.allChecksPass();
        ObjectNode stages = view.putObject("stages");
        stages.put("decided", ChemoRules.isGive(c.decision()));
        stages.put("dosesChecked", allPass);
        stages.put("secondCheck", confirmed || sent);
        stages.put("pharmacy", sent ? "SENT" : "NOT_SENT");
        stages.put("chair", "LATER");
        ObjectNode send = view.putObject("send");
        boolean hold = "DELAY_1_WEEK".equals(c.decision()) || "STOP".equals(c.decision());
        send.put("holdMode", hold);
        boolean draft = row == null || row.draft();
        send.put("ready", draft && allPass && confirmed);
        int red = c.checks().size() - ChemoRules.passedCount(c.checks());
        send.put("note", red > 0 ? red + " check(s) still red" : !confirmed ? "Waiting for the second check"
                : "All checks passed. Ready to send.");
    }

    // ---- the checker's summary ----

    /** The read-only summary shown to the named checker (ChemoCheckSummary). Patient data is for the screen only. */
    public ObjectNode summary(ChemoOrderRow row, Fresh fresh, String patientName, String uhid, Integer age, String sex,
                              String prescriberName) {
        Computed c = fresh.computed();
        ObjectNode node = mapper.createObjectNode();
        node.put("orderId", row.id.toString());
        node.put("patientName", patientName);
        putOrNull(node, "uhid", uhid);
        if (age == null) {
            node.putNull("age");
        } else {
            node.put("age", age);
        }
        node.put("sex", sex);
        node.put("prescriberName", prescriberName);
        node.put("protocolLabel", c.protocol().label());
        OrderInputs in = parseInputs(row.inputs);
        node.put("levelPct", in.levelPct());
        putOrNull(node, "reductionReason", in.reductionReasonKey() != null
                ? REDUCTION_LABELS.getOrDefault(in.reductionReasonKey(), in.reductionReasonKey())
                : in.reductionReasonText());
        ArrayNode drugs = node.putArray("drugs");
        for (DrugResult result : c.drugs()) {
            if (result.line() == null) {
                continue;
            }
            ObjectNode drug = drugs.addObject();
            drug.put("name", result.drug().name());
            drug.put("calculatedMg", result.line().calculatedMg());
            drug.put("roundedMg", result.line().roundedMg());
            drug.put("finalMg", result.line().finalMg());
            drug.put("moreThan5Pct", result.line().moreThan5Pct());
        }
        ObjectNode bsa = node.putObject("bsa");
        bsa.put("used", c.assessment().bsaUsed() == null ? 0 : c.assessment().bsaUsed());
        bsa.put("capped", c.assessment().capped());
        bsa.put("source", "BSA from today's weight");
        putOrNull(node, "labsDrawnAt", c.assessment().drawnAt() == null ? null : c.assessment().drawnAt().toString());
        putOrNull(node, "decision", fresh.decision());
        ArrayNode checks = node.putArray("checks");
        for (Check check : c.checks()) {
            ObjectNode item = checks.addObject();
            item.put("key", check.key());
            item.put("passed", check.passed());
            item.put("detail", check.detail());
        }
        boolean confirmed = row.confirmed() && c.fingerprint().equals(row.confirmedFingerprint);
        ObjectNode second = node.putObject("secondCheck");
        second.put("state", confirmed ? "CONFIRMED" : "WAITING");
        putOrNull(second, "via", confirmed ? row.confirmedVia : null);
        putOrNull(second, "confirmedAt", confirmed ? row.confirmedAt.toString() : null);
        node.put("fingerprint", c.fingerprint());
        node.put("canConfirm", row.draft() && c.allChecksPass() && !confirmed);
        return node;
    }

    /** The first reason the order cannot go ahead: the default text of a hold. */
    public String firstFailedReason(Fresh fresh) {
        OncologyAssessment a = fresh.computed().assessment();
        List<String> failed = new ArrayList<>();
        OncologyRules.reasons(new OncologyRules.Facts(a.ecog(), OncologyRules.countOrOrganFail(a.fails()), a.fever(),
                a.response().category(), OncologyRules.lvefLow(a.fails()), a.worstGrade(), a.feverOrAdmission(),
                a.lineReady(), a.abnormalHeartExam(), a.labs().lvef(), a.fails())).stream().filter(r -> !r.ok())
                .forEach(r -> failed.add(r.text()));
        return failed.isEmpty() ? "Chemotherapy is not given this cycle" : failed.get(0);
    }

    /** A map of ids to names for a set of memberships. */
    public Map<UUID, TeamMember> names(UUID tenantId, Set<UUID> ids) {
        return ids.isEmpty() ? new HashMap<>() : team.findAll(tenantId, ids);
    }
}
