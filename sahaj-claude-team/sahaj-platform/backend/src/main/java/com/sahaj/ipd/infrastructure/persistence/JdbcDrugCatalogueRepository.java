package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.DrugCatalogueRepository;
import com.sahaj.ipd.domain.Drug;
import com.sahaj.ipd.domain.DrugInteraction;
import com.sahaj.ipd.domain.MedicineSafetyCheck.Severity;
import java.sql.Array;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The medicine catalogue (SAH-31, ADR-009 D3): two global reference tables the application can only read. The
 * statements are fixed text.
 */
@Repository
public class JdbcDrugCatalogueRepository implements DrugCatalogueRepository {

    private static final String DRUGS = """
            select key, name, route, classes, high_alert
            from ipd.drug
            order by sort_order
            """;
    private static final String INTERACTIONS = """
            select class_a, class_b, severity, text
            from ipd.drug_interaction
            order by sort_order
            """;

    private final JdbcClient jdbc;

    public JdbcDrugCatalogueRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Drug> findDrugs() {
        return jdbc.sql(DRUGS).query((rs, rowNum) -> new Drug(rs.getString("key"), rs.getString("name"),
                rs.getString("route"), classes(rs.getArray("classes")), rs.getString("high_alert"))).list();
    }

    @Override
    public List<DrugInteraction> findInteractions() {
        return jdbc.sql(INTERACTIONS).query((rs, rowNum) -> new DrugInteraction(rs.getString("class_a"),
                rs.getString("class_b"), Severity.valueOf(rs.getString("severity")), rs.getString("text"))).list();
    }

    private static Set<String> classes(Array array) throws SQLException {
        Set<String> result = new HashSet<>();
        if (array != null) {
            for (Object value : (Object[]) array.getArray()) {
                result.add((String) value);
            }
        }
        return result;
    }
}
