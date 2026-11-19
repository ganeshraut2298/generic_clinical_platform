package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.pharmacy.domain.ProductKeys;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * Code review N-8: the duplicate key that Java computes for a hand-typed product ({@code ProductKeys.dupKey}) must equal the
 * {@code dup_key} that the sample seed (SQL, migration 1030) stored for every catalogue row, otherwise a typed product misses its
 * catalogue twin. Also with non-breaking spaces (U+00A0), which text pasted from a spreadsheet or a PDF carries. Test plan:
 * docs/test-plans/SAH-78.md (T78-N8-*).
 */
@OnTestDatabase
class PharmacySeedDupKeyIT extends Sah78BSupport {

    private static final String NBSP = "\u00A0";

    private record Seeded(String code, String salt, String strength, String form, String dupKey) {
    }

    private List<Seeded> seeded() throws Exception {
        return db().asApp(c -> {
            List<Seeded> out = new ArrayList<>();
            try (var st = c.createStatement(); var rs = st.executeQuery(
                    "select code, salt, strength, form, dup_key from pharmacy.catalogue_product order by code")) {
                while (rs.next()) {
                    out.add(new Seeded(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
                }
            }
            return out;
        });
    }

    private static String nbsp(String text) {
        return text.replace(" ", NBSP);
    }

    private Map<String, Seeded> byCode() throws Exception {
        Map<String, Seeded> m = new LinkedHashMap<>();
        for (Seeded s : seeded()) {
            m.put(s.code(), s);
        }
        return m;
    }

    @Test
    void theJavaKeyEqualsTheStoredKeyOfEverySeededCatalogueRow() throws Exception {
        List<Seeded> rows = seeded();
        assertThat(rows).as("the 30 sample products of the test profile").hasSize(30);
        List<String> mismatches = new ArrayList<>();
        for (Seeded s : rows) {
            String java = ProductKeys.dupKey(s.salt(), s.strength(), s.form());
            if (!java.equals(s.dupKey())) {
                mismatches.add(s.code() + " java=[" + java + "] sql=[" + s.dupKey() + "]");
            }
        }
        assertThat(mismatches).as("Java and SQL keys differ for these rows").isEmpty();
    }

    @Test
    void thePlusAndTheGramRowsHaveTheDocumentedKeys() throws Exception {
        Map<String, Seeded> rows = byCode();
        // G-000110: a combination with a plus sign and two strengths
        Seeded plus = rows.get("G-000110");
        assertThat(plus.dupKey()).isEqualTo("amoxicillin + clavulanic acid|500mg+125mg|tablet");
        assertThat(ProductKeys.dupKey(plus.salt(), plus.strength(), plus.form())).isEqualTo(plus.dupKey());
        // G-000120: 21 g is written as milligrams
        Seeded ors = rows.get("G-000120");
        assertThat(ors.strength()).isEqualTo("21 g");
        assertThat(ors.dupKey()).isEqualTo("oral rehydration salts|21000mg|powder");
        assertThat(ProductKeys.dupKey(ors.salt(), ors.strength(), ors.form())).isEqualTo(ors.dupKey());
        // G-000130: 500 g
        Seeded cotton = rows.get("G-000130");
        assertThat(cotton.strength()).isEqualTo("500 g");
        assertThat(cotton.dupKey()).isEqualTo("absorbent cotton|500000mg|roll");
        assertThat(ProductKeys.dupKey(cotton.salt(), cotton.strength(), cotton.form())).isEqualTo(cotton.dupKey());
    }

    @Test
    void aNonBreakingSpaceInAnyPartGivesTheStoredKeyOfEverySeededRow() throws Exception {
        List<String> mismatches = new ArrayList<>();
        for (Seeded s : seeded()) {
            // every space is a no-break space, and the three parts are padded and doubled with no-break spaces
            String key = ProductKeys.dupKey(NBSP + nbsp(s.salt()).replace(NBSP, NBSP + NBSP) + NBSP, nbsp(s.strength()) + NBSP,
                    NBSP + nbsp(s.form()));
            if (!key.equals(s.dupKey())) {
                mismatches.add(s.code() + " nbsp=[" + key + "] stored=[" + s.dupKey() + "]");
            }
        }
        assertThat(mismatches).isEmpty();
    }

    @Test
    void aTypedProductWithNonBreakingSpacesFindsItsCatalogueTwinThroughTheDuplicateCheck() throws Exception {
        Clinic clinic = pharmacyClinic();
        // %C2%A0 is U+00A0 in a query string
        String plusRow = DUPLICATES + "?salt=Amoxicillin%C2%A0%2B%C2%A0Clavulanic%C2%A0acid%C2%A0&strength=500%C2%A0mg%C2%A0%2B%C2%A0125%C2%A0mg&form=%C2%A0tablet";
        assertThat(candidateCodes(ok(HttpMethod.GET, plusRow, clinic.owner(), null, 200))).contains("G-000110");
        String gramRow = DUPLICATES + "?salt=Oral%C2%A0rehydration%C2%A0salts&strength=21%C2%A0g&form=powder";
        assertThat(candidateCodes(ok(HttpMethod.GET, gramRow, clinic.owner(), null, 200))).contains("G-000120");
        // control: the same typed with plain spaces finds the same product
        String plain = DUPLICATES + "?salt=Oral%20rehydration%20salts&strength=21%20g&form=powder";
        assertThat(candidateCodes(ok(HttpMethod.GET, plain, clinic.owner(), null, 200))).contains("G-000120");
        // and creating the product with a no-break space is refused as a possible duplicate, nothing saved
        Map<String, Object> body = ownBody("Typed ORS", "Oral" + NBSP + "rehydration" + NBSP + "salts", "21" + NBSP + "g", "powder");
        JsonNode dup = problem(call(HttpMethod.POST, PRODUCTS, clinic.owner(), js(body)), 409);
        assertType(dup, "possible-duplicate");
        assertThat(candidateCodes(dup)).contains("G-000120");
        assertThat(pharmacyRows(clinic, "product")).isZero();
    }

    private static List<String> candidateCodes(JsonNode answer) {
        List<String> codes = new ArrayList<>();
        answer.get("candidates").forEach(c -> codes.add(c.get("code").asText()));
        return codes;
    }
}
