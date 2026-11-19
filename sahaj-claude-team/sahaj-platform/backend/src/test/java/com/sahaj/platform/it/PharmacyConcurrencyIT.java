package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-78 WP-B under concurrency, on the real database and the real transaction layer (only the servlet container is MockMvc):
 * two requests that start at the same moment must end in exactly one winner and one clean answer (200, 201 or 409), never a 500
 * from a unique index. The unique indexes of the database are the backstop; these tests prove the service takes its locks first
 * (unit lock for adopt, create and local settings; store lock for submit and approve). Test plan: docs/test-plans/SAH-78.md
 * (T78-CC-*).
 */
@OnTestDatabase
class PharmacyConcurrencyIT extends Sah78BSupport {

    private record Req(HttpMethod method, String path, RequestPostProcessor auth, String body) {
    }

    /** Sends every request at the same moment (one thread each, a start latch) and returns "status|body" in the order given. */
    private List<String> inParallel(List<Req> requests) throws Exception {
        int n = requests.size();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (Req r : requests) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    var builder = MockMvcRequestBuilders.request(r.method(), java.net.URI.create(r.path())).with(r.auth());
                    if (r.body() != null) {
                        builder.contentType(MediaType.APPLICATION_JSON).content(r.body());
                    }
                    var response = mvc.perform(builder).andReturn().getResponse();
                    return response.getStatus() + "|" + response.getContentAsString();
                }));
            }
            ready.await();
            start.countDown();
            List<String> answers = new ArrayList<>();
            for (Future<String> f : futures) {
                answers.add(f.get());
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long statuses(List<String> answers, int status) {
        return answers.stream().filter(a -> a.startsWith(status + "|")).count();
    }

    private String problemType(String answer) throws Exception {
        return bodyOf(answer).get("type").asText();
    }

    @Test
    void twoAdoptsOfTheSameProductAtTheSameMomentGiveOneCreatedAndOneAlreadyThere() throws Exception {
        Clinic clinic = pharmacyClinic();
        String url = adoptUrl(catalogueId("G-000101"));
        List<String> answers = inParallel(List.of(new Req(HttpMethod.POST, url, clinic.owner(), null), new Req(HttpMethod.POST, url, clinic.owner(), null),
                new Req(HttpMethod.POST, url, clinic.owner(), null)));
        assertThat(statuses(answers, 201)).as(answers.toString()).isOne();
        assertThat(statuses(answers, 200)).isEqualTo(2);
        assertThat(pharmacyRows(clinic, "product")).isOne();
        assertThat(auditRowsOf(clinic, "PHARMACY_PRODUCT_ADOPTED")).hasSize(1);
        assertThat(bodyOf(answers.get(0)).get("productId").asText()).isEqualTo(bodyOf(answers.get(1)).get("productId").asText());
    }

    @Test
    void twoSavesOfTheLocalSettingsWithTheSameVersionGiveOneWinnerAndOneSettingsChanged() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode product = adopt(clinic, "G-000103");
        String url = localUrl(product.get("productId").asText());
        java.util.Map<String, Object> a = localBody(product);
        a.put("alias", "First");
        java.util.Map<String, Object> b = localBody(product);
        b.put("alias", "Second");
        List<String> answers = inParallel(List.of(new Req(HttpMethod.PUT, url, clinic.owner(), js(a)), new Req(HttpMethod.PUT, url, clinic.owner(), js(b))));
        assertThat(statuses(answers, 200)).as(answers.toString()).isOne();
        assertThat(statuses(answers, 409)).isOne();
        String loser = answers.stream().filter(x -> x.startsWith("409|")).findFirst().orElseThrow();
        assertThat(problemType(loser)).endsWith("settings-changed");
        JsonNode after = getProduct(clinic, product.get("productId").asText());
        assertThat(after.get("version").asLong()).isOne();
        assertThat(after.get("localSettings").get("alias").asText()).isIn("First", "Second");
        assertThat(auditRowsOf(clinic, "PHARMACY_LOCAL_SETTINGS_CHANGED")).hasSize(1);
    }

    @Test
    void twoOwnersApprovingTheSameImportAtTheSameMomentGiveOneApprovalAndOneImportState() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode central = centralStore(clinic);
        Pn keeper = crew(clinic, "PHARMACIST", "Pia Pharmacist");
        mapStaff(clinic, idOf(central), keeper, "CENTRAL_STORE_USER");
        Pn second = crew(clinic, "OWNER", "Second Owner");
        adopt(clinic, "G-000101");
        adopt(clinic, "G-000103");
        JsonNode started = draftOfCleanFile(keeper.auth(), "CS", idOf(central), "G-000101", "G-000103");
        submit(keeper.auth(), idOf(central), idOf(started));
        String url = importUrl(idOf(central), idOf(started)) + "/approve";
        List<String> answers = inParallel(List.of(new Req(HttpMethod.POST, url, clinic.owner(), null), new Req(HttpMethod.POST, url, second.auth(), null)));
        assertThat(statuses(answers, 200)).as(answers.toString()).isOne();
        assertThat(statuses(answers, 409)).isOne();
        String loser = answers.stream().filter(x -> x.startsWith("409|")).findFirst().orElseThrow();
        assertThat(problemType(loser)).endsWith("import-state");
        assertThat(importStatuses(clinic, idOf(central))).containsExactly("APPROVED");
        assertThat(auditRowsOf(clinic, "PHARMACY_OPENING_STOCK_APPROVED")).hasSize(1);
    }

    @Test
    void anApproveAndARejectAtTheSameMomentLeaveOneFinalStateAndTheOtherGets409() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode central = centralStore(clinic);
        Pn keeper = crew(clinic, "PHARMACIST", "Pia Pharmacist");
        mapStaff(clinic, idOf(central), keeper, "CENTRAL_STORE_USER");
        Pn second = crew(clinic, "OWNER", "Second Owner");
        adopt(clinic, "G-000101");
        adopt(clinic, "G-000103");
        JsonNode started = draftOfCleanFile(keeper.auth(), "CS", idOf(central), "G-000101", "G-000103");
        submit(keeper.auth(), idOf(central), idOf(started));
        String base = importUrl(idOf(central), idOf(started));
        List<String> answers = inParallel(List.of(new Req(HttpMethod.POST, base + "/approve", clinic.owner(), null),
                new Req(HttpMethod.POST, base + "/reject", second.auth(), "{\"reasonKey\":\"WRONG_FILE\",\"note\":null}")));
        assertThat(statuses(answers, 200)).as(answers.toString()).isOne();
        assertThat(statuses(answers, 409)).isOne();
        List<String> statusesNow = importStatuses(clinic, idOf(central));
        assertThat(statusesNow).hasSize(1);
        assertThat(statusesNow.get(0)).isIn("APPROVED", "REJECTED");
        boolean approved = statusesNow.get(0).equals("APPROVED");
        assertThat(ok(HttpMethod.GET, STORES + "/" + idOf(central), clinic.owner(), null, 200).get("hasStock").asBoolean())
                .as("the store has stock exactly when the file was approved").isEqualTo(approved);
        assertThat(auditRowsOf(clinic, approved ? "PHARMACY_OPENING_STOCK_APPROVED" : "PHARMACY_OPENING_STOCK_REJECTED")).hasSize(1);
        assertThat(auditRowsOf(clinic, approved ? "PHARMACY_OPENING_STOCK_REJECTED" : "PHARMACY_OPENING_STOCK_APPROVED")).isEmpty();
    }

    @Test
    void twoPeopleSubmittingCleanFilesForTheSameStoreAtTheSameMomentLeaveOneWaitingAndTheOtherGets409ImportWaiting() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode central = centralStore(clinic);
        Pn keeper = crew(clinic, "PHARMACIST", "Pia Pharmacist");
        Pn keeper2 = crew(clinic, "PHARMACIST", "Second Pharmacist");
        mapStaff(clinic, idOf(central), keeper, "CENTRAL_STORE_USER");
        mapStaff(clinic, idOf(central), keeper2, "CENTRAL_STORE_USER");
        adopt(clinic, "G-000101");
        adopt(clinic, "G-000103");
        JsonNode first = draftOfCleanFile(keeper.auth(), "CS", idOf(central), "G-000101", "G-000103");
        JsonNode other = draftOfCleanFile(keeper2.auth(), "CS", idOf(central), "G-000101", "G-000103");
        List<String> answers = inParallel(List.of(new Req(HttpMethod.POST, importUrl(idOf(central), idOf(first)) + "/submit", keeper.auth(), null),
                new Req(HttpMethod.POST, importUrl(idOf(central), idOf(other)) + "/submit", keeper2.auth(), null)));
        assertThat(statuses(answers, 200)).as(answers.toString()).isOne();
        assertThat(statuses(answers, 409)).isOne();
        String loser = answers.stream().filter(x -> x.startsWith("409|")).findFirst().orElseThrow();
        assertThat(problemType(loser)).endsWith("import-waiting");
        List<String> now = importStatuses(clinic, idOf(central));
        assertThat(now).containsExactlyInAnyOrder("WAITING_APPROVAL", "DRAFT");
    }

    @Test
    void twoStartsWithTheSameClientKeyAtTheSameMomentGiveOneImport() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode central = centralStore(clinic);
        Pn keeper = crew(clinic, "PHARMACIST", "Pia Pharmacist");
        mapStaff(clinic, idOf(central), keeper, "CENTRAL_STORE_USER");
        UUID key = UUID.randomUUID();
        String body = js(startBody(key, 3));
        String url = importsUrl(idOf(central));
        List<String> answers = inParallel(List.of(new Req(HttpMethod.POST, url, keeper.auth(), body), new Req(HttpMethod.POST, url, keeper.auth(), body)));
        assertThat(statuses(answers, 201) + statuses(answers, 200)).as(answers.toString()).isEqualTo(2);
        assertThat(statuses(answers, 201)).isLessThanOrEqualTo(1);
        assertThat(importStatuses(clinic, idOf(central))).as("one import, not two").hasSize(1);
        assertThat(bodyOf(answers.get(0)).get("id").asText()).isEqualTo(bodyOf(answers.get(1)).get("id").asText());
    }
}
