package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.DrugCodeRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code ipd.drug_code} (SAH-76, ADR-027 D9): global reference rows, select only, no tenant column. The codes the
 * caller sends are bind parameters; a typed code is never logged.
 */
@Repository
public class JdbcDrugCodeRepository implements DrugCodeRepository {

    private static final String CODES_OF = "select code from ipd.drug_code where drug_key = :drugKey";
    private static final String DRUG_OF = "select drug_key from ipd.drug_code where code = :code";
    private static final String WITH_CODES = """
            select distinct drug_key from ipd.drug_code where drug_key = any(cast(:keys as text[]))
            """;

    private final JdbcClient jdbc;

    public JdbcDrugCodeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> codesOf(String drugKey) {
        return new HashSet<>(jdbc.sql(CODES_OF).param("drugKey", drugKey).query(String.class).list());
    }

    @Override
    public Optional<String> drugKeyOf(String code) {
        return jdbc.sql(DRUG_OF).param("code", code).query(String.class).optional();
    }

    @Override
    public Set<String> drugKeysWithCodes(Collection<String> drugKeys) {
        if (drugKeys.isEmpty()) {
            return Set.of();
        }
        StringBuilder literal = new StringBuilder("{");
        boolean first = true;
        for (String key : drugKeys) {
            // Drug keys are short lower-case words (ipd.drug.key); anything else cannot match a row.
            if (!key.matches("[a-z0-9_-]{1,40}")) {
                continue;
            }
            literal.append(first ? "" : ",").append(key);
            first = false;
        }
        literal.append('}');
        return new HashSet<>(jdbc.sql(WITH_CODES).param("keys", literal.toString()).query(String.class).list());
    }
}
