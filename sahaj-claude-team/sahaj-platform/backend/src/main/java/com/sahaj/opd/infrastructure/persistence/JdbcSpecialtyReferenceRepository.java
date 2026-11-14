package com.sahaj.opd.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.opd.application.emr.specialty.SpecialtyReferenceData;
import com.sahaj.opd.application.emr.specialty.SpecialtyReferenceData.JointItem;
import com.sahaj.opd.application.emr.specialty.SpecialtyReferenceData.Milestone;
import com.sahaj.opd.application.emr.specialty.SpecialtyReferenceRepository;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.Drug;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.Premed;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.Protocol;
import com.sahaj.opd.domain.emr.specialty.PaedsRules.GrowthBand;
import com.sahaj.opd.domain.emr.specialty.PaedsRules.Group;
import com.sahaj.opd.domain.emr.specialty.PaedsRules.Vaccine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The global reference tables of the specialty screens (ADR-023 D4). Each table has no clinic column and sahaj_app may only
 * select from it; the rows change by migration only, so the first read is kept for the life of the process. Values are
 * tagged "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed".
 */
@Repository
public class JdbcSpecialtyReferenceRepository implements SpecialtyReferenceRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private volatile SpecialtyReferenceData cached;

    public JdbcSpecialtyReferenceRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public SpecialtyReferenceData load() {
        SpecialtyReferenceData data = cached;
        if (data == null) {
            data = read();
            cached = data;
        }
        return data;
    }

    @Override
    public Map<String, MedicineRef> medicinesByName(Collection<String> names) {
        if (names.isEmpty()) {
            return Map.of();
        }
        List<String> wanted = names.stream().map(JdbcSpecialtyReferenceRepository::normalise).distinct().toList();
        Map<String, MedicineRef> found = new LinkedHashMap<>();
        jdbc.sql("""
                select id, name, form from opd.medicine
                where lower(regexp_replace(btrim(name), '[ \t]+', ' ', 'g')) in (:names)
                order by name, id
                """).param("names", wanted).query((rs, row) -> {
            MedicineRef ref = new MedicineRef(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("form"));
            found.putIfAbsent(normalise(ref.name()), ref);
            return ref;
        }).list();
        return Map.copyOf(found);
    }

    private static String normalise(String name) {
        return name == null ? "" : name.strip().replaceAll("[ \\t]+", " ").toLowerCase(Locale.ROOT);
    }

    private SpecialtyReferenceData read() {
        List<Group> groups = jdbc.sql("""
                select group_key, label, position, window_from_value, window_from_unit, window_to_value, window_to_unit
                from opd.emr_immunization_group order by position
                """).query((rs, row) -> new Group(rs.getString("group_key"), rs.getString("label"),
                rs.getInt("position"), rs.getInt("window_from_value"), rs.getString("window_from_unit"),
                rs.getInt("window_to_value"), rs.getString("window_to_unit"))).list();
        List<Vaccine> vaccines = jdbc.sql("""
                select v.vaccine_key, v.group_key, v.label, v.position
                from opd.emr_immunization_vaccine v join opd.emr_immunization_group g on g.group_key = v.group_key
                order by g.position, v.position
                """).query((rs, row) -> new Vaccine(rs.getString("vaccine_key"), rs.getString("group_key"),
                rs.getString("label"), rs.getInt("position"))).list();
        List<GrowthBand> bands = jdbc.sql("""
                select measure, sex, age_months_from, age_months_to, upper_exclusive, label, alert, position
                from opd.emr_growth_band order by measure, age_months_from, position
                """).query((rs, row) -> {
            BigDecimal upper = rs.getBigDecimal("upper_exclusive");
            return new GrowthBand(rs.getString("measure"), rs.getString("sex"), rs.getInt("age_months_from"),
                    rs.getInt("age_months_to"), upper == null ? null : upper.doubleValue(), rs.getString("label"),
                    rs.getBoolean("alert"), rs.getInt("position"));
        }).list();
        List<Milestone> milestones = jdbc.sql("""
                select age_band_months, milestone_key, label, domain, position from opd.emr_milestone
                order by age_band_months, position
                """).query((rs, row) -> new Milestone(rs.getInt("age_band_months"), rs.getString("milestone_key"),
                rs.getString("label"), rs.getString("domain"), rs.getInt("position"))).list();
        List<JointItem> joints = jdbc.sql("""
                select joint_key, kind, item_key, label, normal_from_deg, normal_to_deg, position from opd.emr_joint_item
                order by joint_key, kind, position
                """).query((rs, row) -> {
            BigDecimal from = rs.getBigDecimal("normal_from_deg");
            BigDecimal to = rs.getBigDecimal("normal_to_deg");
            return new JointItem(rs.getString("joint_key"), rs.getString("kind"), rs.getString("item_key"),
                    rs.getString("label"), from == null ? null : from.intValue(), to == null ? null : to.intValue(),
                    rs.getInt("position"));
        }).list();
        List<Protocol> protocols = new ArrayList<>();
        jdbc.sql("""
                select protocol_key, label, sub_line, interval_days, emetic_risk, suggested_on_change,
                       definition::text as definition
                from opd.emr_chemo_protocol order by position
                """).query((rs, row) -> {
            protocols.add(protocol(rs.getString("protocol_key"), rs.getString("label"), rs.getString("sub_line"),
                    rs.getInt("interval_days"), rs.getString("emetic_risk"), rs.getBoolean("suggested_on_change"),
                    rs.getString("definition")));
            return null;
        }).list();
        return new SpecialtyReferenceData(List.copyOf(groups), List.copyOf(vaccines), List.copyOf(bands),
                List.copyOf(milestones), List.copyOf(joints), List.copyOf(protocols));
    }

    private Protocol protocol(String key, String label, String subLine, int interval, String emetic, boolean suggested,
                              String definition) {
        try {
            JsonNode node = mapper.readTree(definition);
            List<Drug> drugs = new ArrayList<>();
            for (JsonNode d : node.path("drugs")) {
                drugs.add(new Drug(d.path("drugKey").asText(), d.path("name").asText(), d.path("dosePerM2").asDouble(),
                        d.path("infusionMinutes").asInt(), d.path("vialStepMg").asInt(), d.path("vesicant").asBoolean(),
                        d.path("anthracycline").asBoolean(), d.path("note").isTextual() ? d.path("note").asText()
                        : null));
            }
            List<Premed> premeds = new ArrayList<>();
            for (JsonNode p : node.path("premeds")) {
                premeds.add(new Premed(p.path("key").asText(), p.path("text").asText(), p.path("minutes").asInt()));
            }
            return new Protocol(key, label, subLine, interval, emetic, suggested, List.copyOf(drugs),
                    List.copyOf(premeds));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("A chemotherapy protocol could not be read", e);
        }
    }
}
