package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.platform.support.LogCapture;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-2 acceptance criterion 4 (backend part): a valid sales enquiry is saved and invalid input gets
 * field errors. The row is read back through a sahaj_migrator connection, because sahaj_app has
 * INSERT only on {@code platform.sales_enquiry}. Each test sends from its own connection address
 * so the 5-per-hour limit of one test cannot affect another. The 429 with a real HTTP client is in
 * {@link SalesEnquiryRateLimitIT}.
 */
@OnTestDatabase
class SalesEnquiryIT extends BaseIntegrationTest {

    private static final String URL = "/api/v1/platform/public/sales-enquiries";

    private static String body(String name, String clinicCity, String mobile, String bestTime, String source) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"name\":").append(quote(name));
        if (clinicCity != null) {
            json.append(",\"clinicCity\":").append(quote(clinicCity));
        }
        json.append(",\"mobile\":").append(quote(mobile));
        if (bestTime != null) {
            json.append(",\"bestTime\":").append(quote(bestTime));
        }
        if (source != null) {
            json.append(",\"source\":").append(quote(source));
        }
        return json.append('}').toString();
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private ResultActions submit(String json, RequestPostProcessor client) throws Exception {
        return mvc.perform(post(URL).with(client).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private long rowsNamed(String name) throws Exception {
        return db().asMigrator(c -> DbAccess.count(c, "select count(*) from platform.sales_enquiry where name = ?", name));
    }

    // ---- saved row ----

    @Test
    void aValidEnquiryIsSavedAndTheResponseCarriesTheId() throws Exception {
        String name = uniqueName("IT visitor");
        OffsetDateTime before = OffsetDateTime.now();

        MvcResult result = submit(body(name, "Sunrise Clinic, Pune", "9123456780", "AFTER_OPD", "BOOK_DEMO"), fromNewClient())
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn();
        UUID id = UUID.fromString(com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id"));

        db().asMigrator(c -> {
            try (var ps = c.prepareStatement("select name, clinic_city, mobile, best_time, source, created_at from platform.sales_enquiry where id = ?")) {
                ps.setObject(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("a row with the returned id exists").isTrue();
                    assertThat(rs.getString("name")).isEqualTo(name);
                    assertThat(rs.getString("clinic_city")).isEqualTo("Sunrise Clinic, Pune");
                    assertThat(rs.getString("mobile")).isEqualTo("9123456780");
                    assertThat(rs.getString("best_time")).isEqualTo("AFTER_OPD");
                    assertThat(rs.getString("source")).isEqualTo("BOOK_DEMO");
                    assertThat(Duration.between(before, rs.getObject("created_at", OffsetDateTime.class)).abs())
                            .isLessThan(Duration.ofMinutes(2));
                    assertThat(rs.next()).isFalse();
                }
            }
            return null;
        });
    }

    @Test
    void anEnquiryWithOnlyTheRequiredFieldsIsSavedWithNullOptionalColumns() throws Exception {
        String name = uniqueName("IT minimal");
        submit(body(name, null, "6123456789", null, "CONTACT_SALES"), fromNewClient()).andExpect(status().isCreated());
        db().asMigrator(c -> {
            assertThat(DbAccess.count(c, "select count(*) from platform.sales_enquiry where name = ? and clinic_city is null and best_time is null and source = 'CONTACT_SALES'", name))
                    .isEqualTo(1);
            return null;
        });
    }

    @Test
    void textIsTrimmedBeforeItIsSavedAndBlankCityBecomesNull() throws Exception {
        String name = uniqueName("IT trim");
        submit(body("  " + name + "  ", "   ", "7123456789", "MORNING", "CONTACT_SALES"), fromNewClient())
                .andExpect(status().isCreated());
        assertThat(rowsNamed(name)).isEqualTo(1);
        db().asMigrator(c -> {
            assertThat(DbAccess.count(c, "select count(*) from platform.sales_enquiry where name = ? and clinic_city is null", name)).isEqualTo(1);
            return null;
        });
    }

    @Test
    void everyBestTimeOptionAndBothSourcesAreAccepted() throws Exception {
        for (String time : new String[] {"MORNING", "AFTERNOON", "AFTER_OPD"}) {
            for (String source : new String[] {"CONTACT_SALES", "BOOK_DEMO"}) {
                submit(body("IT option", null, "8123456789", time, source), fromNewClient()).andExpect(status().isCreated());
            }
        }
    }

    @Test
    void theSavedRowHasNoTenantBecauseTheEndpointIsPublic() throws Exception {
        String name = uniqueName("IT public");
        String other = UUID.randomUUID().toString();
        // criterion 10 for the public endpoint: a tenant id in the body, header and query changes nothing.
        mvc.perform(post(URL + "?tenantId=" + other + "&tenant_id=" + other)
                        .with(fromNewClient())
                        .header("X-Tenant-Id", other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"mobile\":\"9123456781\",\"source\":\"CONTACT_SALES\",\"tenantId\":\"" + other + "\"}"))
                .andExpect(status().isCreated());
        db().asMigrator(c -> {
            assertThat(DbAccess.count(c, "select count(*) from platform.sales_enquiry where name = ?", name)).isEqualTo(1);
            assertThat(DbAccess.count(c, "select count(*) from information_schema.columns where table_schema = 'platform' and table_name = 'sales_enquiry' and column_name like '%tenant%'"))
                    .as("no tenant column").isZero();
            return null;
        });
    }

    @Test
    void theEndpointNeedsNoLoginAndTheListIsNotExposed() throws Exception {
        submit(body("IT anonymous", null, "9123456782", null, "CONTACT_SALES"), fromNewClient()).andExpect(status().isCreated());
        // Nothing reads enquiries back through the API.
        mvc.perform(get(URL)).andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    // ---- 400 with problem details, nothing saved ----

    @Test
    void aMissingOrBlankNameIsRejectedWithAFieldErrorAndNothingIsSaved() throws Exception {
        long before = db().asMigrator(c -> DbAccess.count(c, "select count(*) from platform.sales_enquiry where mobile = '9000000002'"));
        for (String bad : new String[] {"", "   "}) {
            submit(body(bad, null, "9000000002", null, "CONTACT_SALES"), fromNewClient())
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "validation"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
                    .andExpect(jsonPath("$.fieldErrors[0].message").value("Enter your name"));
        }
        submit("{\"mobile\":\"9000000002\",\"source\":\"CONTACT_SALES\"}", fromNewClient())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
        long after = db().asMigrator(c -> DbAccess.count(c, "select count(*) from platform.sales_enquiry where mobile = '9000000002'"));
        assertThat(after).isEqualTo(before);
    }

    @Test
    void invalidMobileNumbersAreRejectedWithAFieldErrorOnMobile() throws Exception {
        for (String bad : new String[] {"5123456789", "98765", "91234567890", "91234abcde", "+919123456789", " 9123456789", "0123456789", ""}) {
            String name = uniqueName("IT bad mobile");
            submit(body(name, null, bad, null, "CONTACT_SALES"), fromNewClient())
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("mobile"))
                    .andExpect(jsonPath("$.fieldErrors[0].message").value("Enter a valid 10-digit mobile number"));
            assertThat(rowsNamed(name)).as("nothing saved for mobile '" + bad + "'").isZero();
        }
    }

    @Test
    void mobileNumbersAtTheEdgesOfTheValidRangeAreAccepted() throws Exception {
        for (String ok : new String[] {"6000000000", "9999999999", "7000000000", "8000000000"}) {
            submit(body("IT edge", null, ok, null, "CONTACT_SALES"), fromNewClient()).andExpect(status().isCreated());
        }
    }

    @Test
    void everyMissingRequiredFieldIsReportedTogether() throws Exception {
        submit("{}", fromNewClient())
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.fieldErrors[*].field", containsInAnyOrder("name", "mobile", "source")));
    }

    @Test
    void anUnknownBestTimeOrSourceIsRejectedWithoutEchoingTheValue() throws Exception {
        submit("{\"name\":\"IT x\",\"mobile\":\"9123456783\",\"source\":\"CONTACT_SALES\",\"bestTime\":\"MIDNIGHT\"}", fromNewClient())
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("bestTime"))
                .andExpect(content().string(not(containsString("MIDNIGHT"))));
        submit("{\"name\":\"IT x\",\"mobile\":\"9123456783\",\"source\":\"NEWSLETTER\"}", fromNewClient())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("source"))
                .andExpect(content().string(not(containsString("NEWSLETTER"))));
    }

    @Test
    void nameAndCityLengthLimitsAreEnforcedAtTheirBoundaries() throws Exception {
        String stamp = UUID.randomUUID().toString().substring(0, 8);
        String name120 = (stamp + "x".repeat(120)).substring(0, 120);
        submit(body(name120, "c".repeat(200), "9123456784", null, "CONTACT_SALES"), fromNewClient()).andExpect(status().isCreated());
        assertThat(rowsNamed(name120)).isEqualTo(1);

        String name121 = (stamp + "y".repeat(121)).substring(0, 121);
        submit(body(name121, null, "9123456784", null, "CONTACT_SALES"), fromNewClient())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
        assertThat(rowsNamed(name121)).isZero();

        submit(body("IT long city", "c".repeat(201), "9123456784", null, "CONTACT_SALES"), fromNewClient())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("clinicCity"));
    }

    @Test
    void brokenJsonAndAnEmptyBodyGet400AsProblemJson() throws Exception {
        submit("{\"name\": \"IT\", \"mobile\": ", fromNewClient())
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "malformed-request"));
        mvc.perform(post(URL).with(fromNewClient()).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    @Test
    void aWrongContentTypeGets415AsProblemJson() throws Exception {
        mvc.perform(post(URL).with(fromNewClient()).contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    @Test
    void theNameCanContainQuotesAndNonLatinTextAndIsStoredAsSent() throws Exception {
        String name = "O'Neil आशा " + UUID.randomUUID().toString().substring(0, 8);
        submit(body(name, null, "9123456785", null, "CONTACT_SALES"), fromNewClient()).andExpect(status().isCreated());
        assertThat(rowsNamed(name)).isEqualTo(1);
    }

    @Test
    void sqlTextInAFieldIsStoredAsPlainTextAndDoesNotRunAsSql() throws Exception {
        String name = "x'); drop table platform.sales_enquiry;-- " + UUID.randomUUID().toString().substring(0, 8);
        submit(body(name, null, "9123456786", null, "CONTACT_SALES"), fromNewClient()).andExpect(status().isCreated());
        assertThat(rowsNamed(name)).isEqualTo(1);
    }

    // ---- the mobile number never reaches a log or an error body ----

    @Test
    void theMobileNumberIsAbsentFromLogsAndFromEveryResponseBody() throws Exception {
        String mobile = "9234567801";
        String badMobile = "5234567801";
        RequestPostProcessor client = fromNewClient();
        try (LogCapture logs = new LogCapture()) {
            // saved, bad name, bad mobile, broken JSON, wrong option, then more until the limit answers 429
            submit(body("IT log probe", null, mobile, null, "CONTACT_SALES"), client)
                    .andExpect(status().isCreated()).andExpect(content().string(not(containsString(mobile))));
            submit(body(" ", null, mobile, null, "CONTACT_SALES"), client)
                    .andExpect(status().isBadRequest()).andExpect(content().string(not(containsString(mobile))));
            submit(body("IT log probe", null, badMobile, null, "CONTACT_SALES"), client)
                    .andExpect(status().isBadRequest()).andExpect(content().string(not(containsString(badMobile))));
            submit("{\"name\":\"IT\",\"mobile\":\"" + mobile + "\", ", client)
                    .andExpect(status().isBadRequest()).andExpect(content().string(not(containsString(mobile))));
            submit("{\"name\":\"IT\",\"mobile\":\"" + mobile + "\",\"source\":\"CONTACT_SALES\",\"bestTime\":\"X\"}", client)
                    .andExpect(status().isBadRequest()).andExpect(content().string(not(containsString(mobile))));
            submit(body("IT log probe", null, mobile, null, "CONTACT_SALES"), client)
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().exists("Retry-After"))
                    .andExpect(content().string(not(containsString(mobile))));

            assertThat(logs.allText()).doesNotContain(mobile).doesNotContain(badMobile);
        }
    }
}
