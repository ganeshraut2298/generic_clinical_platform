package com.sahaj.opd.application.emr.specialty;

import static com.sahaj.opd.application.emr.specialty.Sp.arr;
import static com.sahaj.opd.application.emr.specialty.Sp.bool;
import static com.sahaj.opd.application.emr.specialty.Sp.eN;
import static com.sahaj.opd.application.emr.specialty.Sp.iN;
import static com.sahaj.opd.application.emr.specialty.Sp.obj;
import static com.sahaj.opd.application.emr.specialty.Sp.opt;
import static com.sahaj.opd.application.emr.specialty.Sp.req;
import static com.sahaj.opd.application.emr.specialty.Sp.s;
import static com.sahaj.opd.application.emr.specialty.Sp.sN;
import static com.sahaj.opd.application.emr.specialty.Sp.uuidN;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.EmrAccess;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.specialty.VisitTemplateRepository.Row;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Visit templates (SAH-60-4, ADR-023 D10, H12): "Start from a visit template" lists the clinic's templates; "Save as visit
 * template" keeps the current complaints, diagnoses, investigations, services, medicines and advice (master keys or text,
 * never patient data) for the clinic's doctors. Applying one is a client merge followed by the normal draft save, so the
 * duplicate, allergy and bill rules of the save apply unchanged. At most 100 templates; a name is unique in the clinic
 * ignoring case. Audit {@code EMR_VISIT_TEMPLATE_CREATED} with the id and item counts only. This class never logs.
 */
@Service
public class VisitTemplateService {

    static final int MAX_TEMPLATES = 100;

    private static final Sp.Shape ENTRY = obj(req("itemKey", sN(40)), req("text", s(1, 200)),
            req("newToList", bool()));
    private static final Sp.Shape MEDICINE = obj(req("medicineId", uuidN()), req("name", s(1, 160)),
            opt("frequency", eN("1-0-0", "1-0-1", "0-0-1", "1-1-1", "0-1-0")), opt("days", iN(1, 365)),
            opt("when", eN("AFTER_FOOD", "BEFORE_FOOD", "BEDTIME", "MORNING_EMPTY_STOMACH")));
    private static final Sp.Shape ITEMS = obj(opt("complaints", arr(ENTRY, 20)), opt("diagnoses", arr(ENTRY, 20)),
            opt("investigations", arr(ENTRY, 20)), opt("services", arr(ENTRY, 20)),
            opt("medicines", arr(MEDICINE, 20)), opt("advice", arr(ENTRY, 20)), opt("repeatLastRx", bool()));
    private static final Sp.Shape NAME = s(1, 60);
    private static final Sp.Shape SUB = sN(120);

    /** The body of POST /emr/visit-templates. */
    public record Input(String name, String subLine, JsonNode items) {
    }

    private final EmrAccess access;
    private final VisitTemplateRepository templates;
    private final AuditLog auditLog;
    private final ObjectMapper mapper;

    public VisitTemplateService(EmrAccess access, VisitTemplateRepository templates, AuditLog auditLog,
                                ObjectMapper mapper) {
        this.access = access;
        this.templates = templates;
        this.auditLog = auditLog;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<ObjectNode> list(ClinicUser user) {
        access.requireConsultationWrite(user);
        List<ObjectNode> result = new ArrayList<>();
        for (Row row : templates.active(user.tenantId())) {
            result.add(view(row));
        }
        return result;
    }

    @Transactional
    public ObjectNode create(ClinicUser user, Input input) {
        TeamMember member = access.requireConsultationWrite(user);
        UUID tenantId = user.tenantId();
        Sp.Errors errors = new Sp.Errors("");
        if (input == null) {
            throw new InvalidFieldsException(List.of(new FieldMessage("name", "Enter a name of 1 to 60 characters.")));
        }
        JsonNode name = input.name() == null ? null : mapper.getNodeFactory().textNode(input.name());
        JsonNode cleanName = name == null ? null : NAME.check(name, "name", errors);
        if (name == null) {
            errors.add("name", "Enter a name of 1 to 60 characters.");
        }
        JsonNode cleanSub = input.subLine() == null ? null : SUB.check(mapper.getNodeFactory().textNode(
                input.subLine()), "subLine", errors);
        JsonNode cleanItems = input.items() == null ? null : ITEMS.check(input.items(), "items", errors);
        if (input.items() == null) {
            errors.add("items", "Send the items.");
        }
        if (errors.any()) {
            throw new InvalidFieldsException(errors.messages());
        }
        String templateName = cleanName.asText();
        if (templates.nameExists(tenantId, templateName)) {
            throw new InvalidFieldsException(List.of(new FieldMessage("name", "A template with this name exists")));
        }
        if (templates.countActive(tenantId) >= MAX_TEMPLATES) {
            throw EmrProblemException.of(400, "visit-template-limit", "Template limit reached",
                    "A clinic can have up to 100 visit templates.");
        }
        ObjectNode stored = ((ObjectNode) cleanItems).deepCopy();
        stored.put("schemaVersion", 1);
        UUID id = UUID.randomUUID();
        String sub = cleanSub == null || cleanSub.isNull() ? null : cleanSub.asText();
        templates.insert(tenantId, new Row(id, templateName, sub, stored.toString()), member.membershipId(),
                user.userId());
        Map<String, Object> audit = new LinkedHashMap<>();
        for (String key : List.of("complaints", "diagnoses", "investigations", "services", "medicines", "advice")) {
            audit.put(key, stored.path(key).size());
        }
        auditLog.record("EMR_VISIT_TEMPLATE_CREATED", "EMR_VISIT_TEMPLATE", id, null, audit);
        return view(new Row(id, templateName, sub, stored.toString()));
    }

    private ObjectNode view(Row row) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", row.id().toString());
        node.put("name", row.name());
        if (row.subLine() == null) {
            node.putNull("subLine");
        } else {
            node.put("subLine", row.subLine());
        }
        try {
            ObjectNode items = (ObjectNode) mapper.readTree(row.items());
            items.remove("schemaVersion");
            node.set("items", items);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("A stored visit template could not be read", e);
        }
        return node;
    }

}
