package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-37 the patient card setup API on the real {@code sahaj_test} database ({@code /api/v1/ipd/card-setup}, ADR-015,
 * docs/stories/SAH-37.md AC1 to AC16, AC25 to AC26, AC29 to AC32, AC35, AC43). It covers the live setup, the draft
 * (save, replace, discard, equal to live), publish and the versions list, the owner-only draft and versions (absent from
 * the raw JSON for everyone else), role refusals, another clinic's data, the two-owner conflicts, the double publish,
 * every validation rule V1 to V14 with nothing stored, Read-only and IPD Off, the change history and the logs.
 * Test plan: docs/test-plans/SAH-37.md.
 */
@OnTestDatabase
@WithPinPepper
class CardSetupIT extends CardSetupSupport {

    private static final String SECRET = "Secret label";

    // ---------------- AC1, AC2, AC17, AC21, AC28, AC37, AC38: the live setup, the standard and the catalogue ----------------

    @Test
    void aClinicThatNeverPublishedSeesTheStandardCardAsV1AndNothingIsStored() throws Exception {
        Clinic clinic = hospital();
        JsonNode v = view(clinic.owner());
        JsonNode live = v.get("live");
        assertThat(live.get("version").asInt()).isEqualTo(1);
        assertThat(live.get("isStandard").asBoolean()).isTrue();
        assertThat(live.get("setup")).isEqualTo(v.get("standard"));
        assertThat(v.has("draft")).as("the owner's answer names the draft, as null").isTrue();
        assertThat(v.get("draft").isNull()).isTrue();
        assertThat(v.get("canEdit").asBoolean()).isTrue();
        assertThat(v.path("readOnlyReason").asText("")).isEmpty();
        JsonNode versions = v.get("versions");
        assertThat(versions).hasSize(1);
        JsonNode v1 = versions.get(0);
        assertThat(v1.get("version").asInt()).isEqualTo(1);
        assertThat(v1.get("summary").asText()).isEqualTo("Sahaj standard patient card (default)");
        assertThat(v1.get("live").asBoolean()).isTrue();
        assertThat(v1.get("isStandard").asBoolean()).isTrue();
        assertThat(v1.path("publishedByName").asText("")).isEmpty();
        // reading creates no row, no audit line and no record-access row (AC63)
        Stored s = stored(clinic);
        assertThat(s.versions()).isZero();
        assertThat(s.drafts()).isZero();
        assertThat(s.cardAudits()).isZero();
    }

    @Test
    void theStandardSetupIsTheSah36CardPlusTheLockedAllergiesAndEarlyWarningLinesAndTheCatalogueHoldsOnlyFieldsWithData()
            throws Exception {
        Clinic clinic = hospital();
        JsonNode v = view(clinic.owner());
        JsonNode std = v.get("standard");
        assertThat(std.get("layout").asText()).isEqualTo("standard");
        assertThat(std.get("cardsPerRow").asInt()).isEqualTo(3);
        assertThat(sectionKeys(std)).containsExactly(SECTION_KEYS);
        // which sections are on, and which fields are on in each (DEC-027, the table of the design note section 2)
        boolean[] sectionOn = {true, true, true, true, true, true, false};
        for (int i = 0; i < SECTION_KEYS.length; i++) {
            assertThat(std.get("sections").get(i).get("on").asBoolean()).as(SECTION_KEYS[i]).isEqualTo(sectionOn[i]);
        }
        assertThat(onFields(std, "header")).containsExactly("name", "uhid", "ageSex", "bed");
        assertThat(onFields(std, "status")).containsExactly("alerts");
        assertThat(onFields(std, "stay")).containsExactly("admittedOn", "lengthOfStay", "ward", "room");
        assertThat(onFields(std, "care")).containsExactly("doctor");
        assertThat(onFields(std, "vitals")).as("only the locked Early warning line").containsExactly("ews");
        assertThat(onFields(std, "clinical")).as("Allergies on, Diagnosis off").containsExactly("allergies");
        assertThat(onFields(std, "billing")).containsExactly("runningBill", "deposit", "payer");
        assertThat(fieldKeys(std, "header")).containsExactly("name", "uhid", "ageSex", "bed", "ipNumber", "mobile");
        for (JsonNode f : std.get("sections").get(0).get("fields")) {
            assertThat(f.get("label").asText()).as("every label empty").isEmpty();
        }
        assertThat(std.get("actions")).hasSize(5);
        assertThat(texts(std.get("actions").findValues("key"))).containsExactly(ACTION_KEYS);
        for (String key : ACTION_KEYS) {
            assertThat(actionOf(std, key).get("on").asBoolean()).as(key).isEqualTo(!"deposit".equals(key));
        }
        // the catalogue
        JsonNode cat = v.get("catalogue");
        assertThat(texts(cat.get("sections").findValues("key"))).containsExactly(SECTION_KEYS);
        assertThat(texts(cat.get("sections").findValues("name"))).containsExactly("Card header", "Workflow status",
                "Admission and bed", "Care team", "Vitals", "Clinical", "Billing");
        assertThat(texts(cat.get("fields").findValues("key"))).containsExactly("name", "uhid", "ageSex", "bed", "ipNumber",
                "mobile", "alerts", "admittedOn", "lengthOfStay", "ward", "room", "admissionType", "doctor", "bp", "pulse",
                "spo2", "temp", "rr", "ews", "lastRecorded", "diagnosis", "allergies", "runningBill", "deposit", "payer");
        assertThat(texts(cat.get("fields").findValues("key"))).doesNotContain("stage", "assignedNurse", "caseNote");
        assertThat(cat.get("labelMaxLength").asInt()).isEqualTo(40);
        assertThat(texts(cat.get("viewAsRoles"))).containsExactly("OWNER", "DOCTOR", "NURSE", "FRONT_DESK");
        // safety fields: UHID, Alerts, Early warning, Allergies; Patient name is required
        List<String> safety = new ArrayList<>();
        List<String> required = new ArrayList<>();
        for (JsonNode f : cat.get("fields")) {
            if (f.get("safety").asBoolean()) {
                safety.add(f.get("key").asText());
            }
            if (f.get("required").asBoolean()) {
                required.add(f.get("key").asText());
            }
        }
        assertThat(safety).containsExactly("uhid", "alerts", "ews", "allergies");
        assertThat(required).containsExactly("name");
        // the data groups and who sees them (rule 11), the "who" line of the quick actions (AC38)
        assertThat(groupRoles(cat, "patient")).containsExactly("OWNER", "DOCTOR", "NURSE", "FRONT_DESK");
        assertThat(groupRoles(cat, "contact")).containsExactly("OWNER", "FRONT_DESK");
        assertThat(groupRoles(cat, "clinical")).containsExactly("OWNER", "DOCTOR", "NURSE");
        assertThat(groupRoles(cat, "vitals")).containsExactly("OWNER", "DOCTOR", "NURSE");
        assertThat(groupRoles(cat, "billing")).containsExactly("OWNER", "FRONT_DESK");
        assertThat(usableBy(cat, "nursing")).containsExactly("OWNER", "DOCTOR", "NURSE");
        assertThat(usableBy(cat, "emr")).containsExactly("OWNER", "DOCTOR", "NURSE");
        assertThat(usableBy(cat, "transfer")).containsExactly("OWNER", "NURSE", "FRONT_DESK");
        assertThat(usableBy(cat, "discharge")).containsExactly("OWNER", "DOCTOR");
        assertThat(usableBy(cat, "deposit")).containsExactly("OWNER", "FRONT_DESK");
        // the three templates (AC17, AC18)
        JsonNode templates = v.get("templates");
        List<String> templateKeys = new ArrayList<>();
        templates.forEach(t -> templateKeys.add(t.get("key").asText()));
        assertThat(templateKeys).containsExactly("smallClinic", "nursingHome", "hospital");
        JsonNode small = templates.get(0);
        assertThat(small.get("layout").asText()).isEqualTo("compact");
        assertThat(small.get("description").asText()).isEqualTo("Compact card, no billing");
        assertThat(small.get("sections").get(6).get("on").asBoolean()).isFalse();
        JsonNode home = templates.get(1);
        assertThat(home.get("layout").asText()).isEqualTo("standard");
        assertThat(home.get("description").asText()).isEqualTo("Standard card with deposit action");
        JsonNode homeDeposit = null;
        for (JsonNode a : home.get("actions")) {
            if ("deposit".equals(a.get("key").asText())) {
                homeDeposit = a;
            }
        }
        assertThat(homeDeposit.get("on").asBoolean()).isTrue();
        JsonNode hospital = templates.get(2);
        assertThat(hospital.get("layout").asText()).isEqualTo("expanded");
        assertThat(hospital.get("description").asText()).isEqualTo("Expanded card, billing section, all actions");
        assertThat(hospital.get("sections").get(6).get("on").asBoolean()).isTrue();
        for (JsonNode a : hospital.get("actions")) {
            assertThat(a.get("on").asBoolean()).as("hospital " + a.get("key")).isTrue();
        }
    }

    private static List<String> onFields(JsonNode setup, String sectionKey) {
        List<String> keys = new ArrayList<>();
        sectionOf(setup, sectionKey).get("fields").forEach(f -> {
            if (f.get("on").asBoolean()) {
                keys.add(f.get("key").asText());
            }
        });
        return keys;
    }

    private static List<String> groupRoles(JsonNode catalogue, String group) {
        for (JsonNode g : catalogue.get("groups")) {
            if (group.equals(g.get("key").asText())) {
                return texts(g.get("visibleTo"));
            }
        }
        throw new AssertionError(group);
    }

    private static List<String> usableBy(JsonNode catalogue, String action) {
        for (JsonNode a : catalogue.get("actions")) {
            if (action.equals(a.get("key").asText())) {
                return texts(a.get("usableBy"));
            }
        }
        throw new AssertionError(action);
    }

    // ---------------- AC3, AC4, AC10: the draft ----------------

    @Test
    void saveDraftStoresItForTheOwnerWithRevisionAndChangedPartsAndTheLiveCardIsUntouched() throws Exception {
        Clinic clinic = hospital();
        Staff nurse = staff(clinic, "NURSE");
        ObjectNode compact = edit(standard(clinic.owner()), s -> s.put("layout", "compact"));
        JsonNode saved = saveOk(clinic.owner(), 1, null, compact);
        JsonNode draft = saved.get("draft");
        long r1 = draft.get("revision").asLong();
        assertThat(r1).as("an opaque token, not a count of saves").isPositive();
        assertThat(draft.get("baseVersion").asInt()).isEqualTo(1);
        assertThat(texts(draft.get("changedParts"))).containsExactly("layout");
        assertThat(draft.get("setup").get("layout").asText()).isEqualTo("compact");
        assertThat(draft.get("savedByName").asText()).isEqualTo(nameOf(clinic, clinic.ownerUserId()));
        assertThat(instant(draft.get("savedAt"))).isNotNull();
        assertThat(saved.get("live").get("version").asInt()).as("live is untouched").isEqualTo(1);
        assertThat(saved.get("live").get("setup").get("layout").asText()).isEqualTo("standard");
        Stored s = stored(clinic);
        assertThat(s.drafts()).isEqualTo(1);
        assertThat(s.versions()).isZero();
        assertThat(s.draft().get(0)).isEqualTo(String.valueOf(r1));
        assertThat(s.draft().get(1)).isEqualTo("1");
        // a new owner session (another device) reads the same draft
        JsonNode again = view(clinic.owner());
        assertThat(again.get("draft").get("setup").get("layout").asText()).isEqualTo("compact");
        // the changed parts count layout, sections, fields and quick actions (AC3: "N changes")
        ObjectNode more = edit(compact, x -> {
            sectionOn(x, "care", false);
            fieldOn(x, "bed", false);
            actionOn(x, "deposit", true);
            x.put("cardsPerRow", 4);
        });
        JsonNode second = saveOk(clinic.owner(), 1, r1, more);
        assertThat(second.get("draft").get("revision").asLong()).as("every save gives a new token").isGreaterThan(r1);
        assertThat(texts(second.get("draft").get("changedParts"))).containsExactly("layout", "sections", "fields", "quickActions");
        assertThat(stored(clinic).drafts()).as("still one draft per clinic").isEqualTo(1);
        // staff still see the live card and never the draft (AC10)
        JsonNode staffView = view(nurse.auth());
        assertThat(staffView.get("live").get("setup").get("layout").asText()).isEqualTo("standard");
        assertThat(staffView.has("draft")).as("draft is absent, not null").isFalse();
        assertThat(staffView.has("versions")).isFalse();
    }

    @Test
    void theDraftAndTheVersionsAreAbsentFromTheRawJsonOfEveryNonOwnerAndNoLabelOrNameLeaks() throws Exception {
        Clinic clinic = hospital();
        publishNow(clinic, edit(standard(clinic.owner()), s -> fieldLabel(s, "bed", "Bay")));
        String label = uniqueName("Dr").substring(0, 14);
        ObjectNode draftSetup = edit(standard(clinic.owner()), s -> {
            s.put("layout", "expanded");
            fieldLabel(s, "ward", label);
        });
        JsonNode v = view(clinic.owner());
        saveOk(clinic.owner(), v.get("live").get("version").asInt(), null, draftSetup);
        assertThat(call(HttpMethod.GET, CARD_SETUP, clinic.owner(), null).andReturn().getResponse().getContentAsString())
                .contains(label);
        for (String role : new String[] {"FRONT_DESK", "NURSE", "DOCTOR"}) {
            Staff who = staff(clinic, role);
            String raw = call(HttpMethod.GET, CARD_SETUP, who.auth(), null).andReturn().getResponse().getContentAsString();
            JsonNode n = objectMapper.readTree(raw);
            assertThat(n.has("draft")).as(role + " draft").isFalse();
            assertThat(n.has("versions")).as(role + " versions").isFalse();
            assertThat(raw).as(role + " raw").doesNotContain(label).doesNotContain("\"revision\"").doesNotContain("savedByName")
                    .doesNotContain("changedParts").doesNotContain("\"baseVersion\"");
            assertThat(n.get("canEdit").asBoolean()).isFalse();
            assertThat(n.get("readOnlyReason").asText()).isEqualTo("ROLE");
            // the live card (v2 with the label Bay) is theirs to read
            assertThat(n.get("live").get("version").asInt()).isEqualTo(2);
            assertThat(fieldOf(n.get("live").get("setup"), "bed").get("label").asText()).isEqualTo("Bay");
            assertThat(raw).as(role + " live publishedByName is only the name of a colleague").doesNotContain("password");
        }
    }

    // ---------------- AC8: a draft equal to the live setup ----------------

    @Test
    void aDraftThatEqualsTheLiveSetupRemovesTheDraftAndWritesNoHistoryLine() throws Exception {
        Clinic clinic = hospital();
        ObjectNode changed = edit(standard(clinic.owner()), s -> fieldOn(s, "ward", false));
        long rChanged = revisionOf(saveOk(clinic.owner(), 1, null, changed));
        assertThat(stored(clinic).drafts()).isEqualTo(1);
        long audits = stored(clinic).cardAudits();
        // the owner switched it back: the same canonical setup as live
        JsonNode back = saveOk(clinic.owner(), 1, rChanged, standard(clinic.owner()));
        assertThat(back.get("draft").isNull()).isTrue();
        Stored s = stored(clinic);
        assertThat(s.drafts()).isZero();
        assertThat(s.cardAudits()).as("no history line for a draft that equals live").isEqualTo(audits);
        // a label that is the standard name, or only blanks, is the standard name too (canonical form)
        ObjectNode sameName = edit(standard(clinic.owner()), x -> fieldLabel(x, "bed", "Bed"));
        assertThat(saveOk(clinic.owner(), 1, null, sameName).get("draft").isNull()).isTrue();
        ObjectNode blanks = edit(standard(clinic.owner()), x -> fieldLabel(x, "bed", "   "));
        assertThat(saveOk(clinic.owner(), 1, null, blanks).get("draft").isNull()).isTrue();
        assertThat(stored(clinic).drafts()).isZero();
        // a safety label equal to the standard name is not a change either
        ObjectNode safe = edit(standard(clinic.owner()), x -> fieldLabel(x, "allergies", "Allergies"));
        assertThat(saveOk(clinic.owner(), 1, null, safe).get("draft").isNull()).isTrue();
    }

    // ---------------- AC5: discard ----------------

    @Test
    void discardRemovesTheDraftWritesNoHistoryAndWithNoDraftIsA200() throws Exception {
        Clinic clinic = hospital();
        long rv = revisionOf(saveOk(clinic.owner(), 1, null, edit(standard(clinic.owner()), s -> s.put("layout", "compact"))));
        long audits = stored(clinic).cardAudits();
        assertThat(audits).isEqualTo(1);
        JsonNode after = ok(HttpMethod.DELETE, DRAFT + "?draftRevision=" + rv, clinic.owner(), null, 200);
        assertThat(after.get("draft").isNull()).isTrue();
        assertThat(after.get("live").get("version").asInt()).isEqualTo(1);
        Stored s = stored(clinic);
        assertThat(s.drafts()).isZero();
        assertThat(s.cardAudits()).as("discard writes no history line").isEqualTo(audits);
        // nothing to discard: the outcome the owner wanted
        assertThat(ok(HttpMethod.DELETE, DRAFT + "?draftRevision=" + rv, clinic.owner(), null, 200).get("draft").isNull()).isTrue();
        // a missing or silly revision
        problem(call(HttpMethod.DELETE, DRAFT, clinic.owner(), null), 400);
        assertType(problem(discard(clinic.owner(), 0), 400), "validation");
        problem(discard(clinic.owner(), "abc"), 400);
    }

    // ---------------- AC7, AC9, AC16: publish and the versions list ----------------

    @Test
    void publishMakesTheDraftTheNextVersionDeletesTheDraftAndTheVersionListIsNewestFirstWithOneLive() throws Exception {
        Clinic clinic = hospital();
        String ownerName = nameOf(clinic, clinic.ownerUserId());
        // v2: layout + fields
        ObjectNode setup2 = edit(standard(clinic.owner()), s -> {
            s.put("layout", "expanded");
            fieldOn(s, "ipNumber", true);
        });
        long rDraft = revisionOf(saveOk(clinic.owner(), 1, null, setup2));
        JsonNode r2 = publishOk(clinic.owner(), 1, rDraft, setup2);
        assertThat(r2.get("live").get("version").asInt()).isEqualTo(2);
        assertThat(r2.get("live").get("isStandard").asBoolean()).isFalse();
        assertThat(r2.get("live").get("setup").get("layout").asText()).isEqualTo("expanded");
        assertThat(r2.get("live").get("publishedByName").asText()).isEqualTo(ownerName);
        assertThat(r2.get("draft").isNull()).as("the draft is deleted by Publish").isTrue();
        assertThat(stored(clinic).drafts()).isZero();
        // v3: quick actions only, published without a saved draft (the body is the editor's draft)
        ObjectNode setup3 = edit(setup2, s -> actionOn(s, "deposit", true));
        JsonNode r3 = publishOk(clinic.owner(), 2, null, setup3);
        assertThat(r3.get("live").get("version").asInt()).isEqualTo(3);
        // v4: back to the Sahaj standard card
        JsonNode r4 = publishOk(clinic.owner(), 3, null, standard(clinic.owner()));
        assertThat(r4.get("live").get("version").asInt()).isEqualTo(4);
        assertThat(r4.get("live").get("isStandard").asBoolean()).isTrue();
        JsonNode versions = r4.get("versions");
        assertThat(versions).hasSize(4);
        assertThat(versions.findValues("version").stream().map(JsonNode::asInt)).containsExactly(4, 3, 2, 1);
        assertThat(versions.get(0).get("summary").asText()).isEqualTo("Back to the Sahaj standard card");
        assertThat(versions.get(1).get("summary").asText()).isEqualTo("Changed: quick actions");
        assertThat(versions.get(2).get("summary").asText()).isEqualTo("Changed: layout, fields");
        assertThat(versions.get(3).get("summary").asText()).isEqualTo("Sahaj standard patient card (default)");
        assertThat(texts(versions.get(2).get("changedParts"))).containsExactly("layout", "fields");
        assertThat(versions.findValues("live").stream().filter(JsonNode::asBoolean).count()).as("one Live pill").isEqualTo(1);
        assertThat(versions.get(0).get("live").asBoolean()).isTrue();
        assertThat(versions.get(3).get("live").asBoolean()).isFalse();
        for (int i = 0; i < 3; i++) {
            assertThat(versions.get(i).get("publishedByName").asText()).isEqualTo(ownerName);
            assertThat(instant(versions.get(i).get("publishedAt"))).isNotNull();
        }
        assertThat(versions.get(0).has("restore")).as("no restore button").isFalse();
        // newest first by time too
        assertThat(java.time.Instant.parse(versions.get(0).get("publishedAt").asText()))
                .isAfterOrEqualTo(java.time.Instant.parse(versions.get(2).get("publishedAt").asText()));
        Stored s = stored(clinic);
        assertThat(s.versions()).isEqualTo(3);
        assertThat(values(clinic, "select string_agg(version::text, ',' order by version) from ipd.card_setup_version"))
                .containsExactly("2,3,4");
        assertThat(values(clinic, "select is_standard::text from ipd.card_setup_version where version = 4"))
                .containsExactly("true");
    }

    @Test
    void publishingWhatIsAlreadyLiveIs409UnchangedAndStoresNothing() throws Exception {
        Clinic clinic = hospital();
        Stored before = stored(clinic);
        JsonNode p = problem(publish(clinic.owner(), 1, null, standard(clinic.owner())), 409);
        assertType(p, "card-setup-unchanged");
        assertThat(stored(clinic)).isEqualTo(before);
        // also after a publish
        ObjectNode changed = edit(standard(clinic.owner()), s -> s.put("cardsPerRow", 2));
        publishOk(clinic.owner(), 1, null, changed);
        Stored mid = stored(clinic);
        assertType(problem(publish(clinic.owner(), 2, null, changed), 409), "card-setup-unchanged");
        assertThat(stored(clinic)).isEqualTo(mid);
    }

    // ---------------- AC8, AC12: a double publish ----------------

    @Test
    void aSecondPublishWithTheOldBaseVersionIs409AndExactlyOneVersionIsMade() throws Exception {
        Clinic clinic = hospital();
        ObjectNode setup = edit(standard(clinic.owner()), s -> s.put("layout", "compact"));
        publishOk(clinic.owner(), 1, null, setup);
        conflictOf(publish(clinic.owner(), 1, null, setup));
        assertThat(stored(clinic).versions()).isEqualTo(1);
    }

    @Test
    void twoPublishesAtTheSameMomentMakeExactlyOneVersionAndTheOtherGets409() throws Exception {
        Clinic clinic = hospital();
        ObjectNode setup = edit(standard(clinic.owner()), s -> s.put("layout", "compact"));
        String body = writeBody(1, null, setup);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Callable<Integer> task = () -> {
                    go.await();
                    return call(HttpMethod.POST, PUBLISH, clinic.owner(), body).andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(task));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }
            assertThat(statuses).as("one wins, the other is refused, never a 500").containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(stored(clinic).versions()).isEqualTo(1);
        assertThat(values(clinic, "select count(distinct version)::text from ipd.card_setup_version")).containsExactly("1");
    }

    // ---------------- AC12: two owners ----------------

    @Test
    void anOwnerWhoLoadedBeforeAnotherSavedADraftIsRefusedAndTheOtherDraftIsKept() throws Exception {
        Clinic clinic = hospital();
        Staff second = secondOwner(clinic);
        ObjectNode first = edit(standard(clinic.owner()), s -> s.put("layout", "compact"));
        saveOk(second.auth(), 1, null, first);
        Stored before = stored(clinic);
        ObjectNode mine = edit(standard(clinic.owner()), s -> s.put("layout", "expanded"));
        conflictOf(putDraft(clinic.owner(), 1, null, mine));
        conflictOf(publish(clinic.owner(), 1, null, mine));
        assertThat(stored(clinic)).as("nothing stored, the other owner's draft is as it was").isEqualTo(before);
        assertThat(view(clinic.owner()).get("draft").get("setup").get("layout").asText()).isEqualTo("compact");
    }

    @Test
    void aStaleDraftRevisionIsRefusedForSaveDiscardAndPublish() throws Exception {
        Clinic clinic = hospital();
        Staff second = secondOwner(clinic);
        long rOld = revisionOf(saveOk(clinic.owner(), 1, null, edit(standard(clinic.owner()), s -> s.put("layout", "compact"))));
        // both hold the first token; the second owner saves again and the token changes
        long rNew = revisionOf(saveOk(second.auth(), 1, rOld, edit(standard(clinic.owner()), s -> s.put("layout", "expanded"))));
        Stored before = stored(clinic);
        assertThat(before.draft().get(0)).isEqualTo(String.valueOf(rNew));
        assertThat(rNew).isNotEqualTo(rOld);
        ObjectNode stale = edit(standard(clinic.owner()), s -> s.put("cardsPerRow", 4));
        conflictOf(putDraft(clinic.owner(), 1, rOld, stale));
        conflictOf(publish(clinic.owner(), 1, rOld, stale));
        conflictOf(discard(clinic.owner(), rOld));
        assertThat(stored(clinic)).isEqualTo(before);
        // the fresh revision is accepted
        assertThat(saveOk(clinic.owner(), 1, rNew, stale).get("draft").get("revision").asLong()).isGreaterThan(rNew);
    }

    @Test
    void aDraftDiscardedByAnotherOwnerCannotBeSavedOrPublishedFromTheOldRevision() throws Exception {
        Clinic clinic = hospital();
        Staff second = secondOwner(clinic);
        long rMine = revisionOf(saveOk(clinic.owner(), 1, null, edit(standard(clinic.owner()), s -> s.put("layout", "compact"))));
        ok(HttpMethod.DELETE, DRAFT + "?draftRevision=" + rMine, second.auth(), null, 200);
        Stored before = stored(clinic);
        ObjectNode mine = edit(standard(clinic.owner()), s -> s.put("layout", "expanded"));
        conflictOf(putDraft(clinic.owner(), 1, rMine, mine));
        conflictOf(publish(clinic.owner(), 1, rMine, mine));
        assertThat(stored(clinic)).isEqualTo(before);
        assertThat(before.drafts()).isZero();
        assertThat(before.versions()).isZero();
    }

    @Test
    void anOwnerWhoLoadedBeforeAnotherPublishedIsRefusedOnTheStaleBaseVersionAndNothingIsOverwritten() throws Exception {
        Clinic clinic = hospital();
        Staff second = secondOwner(clinic);
        ObjectNode theirs = edit(standard(clinic.owner()), s -> s.put("layout", "compact"));
        publishOk(second.auth(), 1, null, theirs);
        Stored before = stored(clinic);
        ObjectNode mine = edit(standard(clinic.owner()), s -> s.put("layout", "expanded"));
        conflictOf(putDraft(clinic.owner(), 1, null, mine));
        conflictOf(publish(clinic.owner(), 1, null, mine));
        assertThat(stored(clinic)).isEqualTo(before);
        assertThat(view(clinic.owner()).get("live").get("setup").get("layout").asText()).isEqualTo("compact");
        assertThat(view(clinic.owner()).get("live").get("version").asInt()).isEqualTo(2);
        // a draft saved on the old base is also stale after another owner's publish
        long rTheirs = revisionOf(saveOk(second.auth(), 2, null, edit(theirs, s -> s.put("cardsPerRow", 2))));
        publishOk(second.auth(), 2, rTheirs, edit(theirs, s -> s.put("cardsPerRow", 2)));
        conflictOf(putDraft(clinic.owner(), 2, null, mine));
    }

    // ---------------- AC10, AC11, AC25, AC35, AC43: roles ----------------

    @Test
    void everyWriteByAnyoneButTheOwnerIs403AndNothingIsStored() throws Exception {
        Clinic clinic = hospital();
        Staff nurse = staff(clinic, "NURSE");
        saveOk(clinic.owner(), 1, null, edit(standard(clinic.owner()), s -> s.put("layout", "compact")));
        Stored before = stored(clinic);
        ObjectNode setup = edit(standard(clinic.owner()), s -> s.put("layout", "expanded"));
        List<RequestPostProcessor> callers = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String role : new String[] {"FRONT_DESK", "NURSE", "DOCTOR", "BILLING", "PHARMACIST", "VISITING_DOCTOR", "COUNSELLOR"}) {
            callers.add(staff(clinic, role).auth());
            names.add(role);
        }
        callers.add(nurse.auth());
        names.add("NURSE (2nd)");
        for (int i = 0; i < callers.size(); i++) {
            RequestPostProcessor who = callers.get(i);
            forbiddenOf(putDraft(who, 1, 1L, setup));
            forbiddenOf(publish(who, 1, 1L, setup));
            forbiddenOf(discard(who, 1));
            assertThat(stored(clinic)).as(names.get(i)).isEqualTo(before);
        }
        // a role in the token that the membership does not have (an owner token on a demoted person)
        Staff demoted = secondOwner(clinic);
        setMembershipRole(clinic, demoted.userId(), "NURSE");
        forbiddenOf(putDraft(demoted.auth(), 1, 1L, setup));
        forbiddenOf(publish(demoted.auth(), 1, 1L, setup));
        forbiddenOf(discard(demoted.auth(), 1));
        JsonNode asNurse = view(demoted.auth());
        assertThat(asNurse.has("draft")).as("a demoted owner no longer gets the draft").isFalse();
        assertThat(asNurse.get("canEdit").asBoolean()).isFalse();
        assertThat(stored(clinic)).isEqualTo(before);
        // a signed-out caller
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(DRAFT)
                        .contentType("application/json").content(writeBody(1, null, setup)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    @Test
    void theFourListRolesMayReadTheLiveSetupAndEveryOtherRoleGets403() throws Exception {
        Clinic clinic = hospital();
        for (String role : new String[] {"FRONT_DESK", "NURSE", "DOCTOR"}) {
            JsonNode v = view(staff(clinic, role).auth());
            assertThat(v.get("live").get("version").asInt()).isEqualTo(1);
            assertThat(v.get("catalogue")).isNotNull();
        }
        for (String role : NO_CARD_ROLES) {
            forbiddenOf(call(HttpMethod.GET, CARD_SETUP, staff(clinic, role).auth(), null));
        }
        // an inactive membership with a valid token
        Staff gone = staff(clinic, "NURSE");
        view(gone.auth());
        setMembershipStatus(clinic.tenantId(), gone.userId(), "INACTIVE");
        forbiddenOf(call(HttpMethod.GET, CARD_SETUP, gone.auth(), null));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(CARD_SETUP))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    // ---------------- AC11, AC65: another clinic ----------------

    @Test
    void anotherClinicsSetupDraftAndVersionsAreInvisibleAndCannotBeChanged() throws Exception {
        Clinic mine = hospital();
        Clinic theirs = hospital();
        ObjectNode setup = edit(standard(theirs.owner()), s -> {
            s.put("layout", "compact");
            fieldLabel(s, "ward", SECRET + " Zeta");
        });
        publishNow(theirs, setup);
        saveOk(theirs.owner(), 2, null, edit(setup, s -> s.put("cardsPerRow", 4)));
        // my clinic: the standard v1, no draft, one version row, no trace of their label
        String raw = call(HttpMethod.GET, CARD_SETUP, mine.owner(), null).andReturn().getResponse().getContentAsString();
        JsonNode v = objectMapper.readTree(raw);
        assertThat(v.get("live").get("version").asInt()).isEqualTo(1);
        assertThat(v.get("live").get("isStandard").asBoolean()).isTrue();
        assertThat(v.get("draft").isNull()).isTrue();
        assertThat(v.get("versions")).hasSize(1);
        assertThat(raw).doesNotContain("Zeta").doesNotContain(SECRET);
        // their staff see their own clinic's v2 only
        Staff theirNurse = staff(theirs, "NURSE");
        assertThat(view(theirNurse.auth()).get("live").get("version").asInt()).isEqualTo(2);
        // my owner cannot change theirs: the clinic comes from the sign-in, not from the body
        Stored mineBefore = stored(mine);
        Stored theirsBefore = stored(theirs);
        ObjectNode withTenant = writeBodyTree(1, null, edit(standard(mine.owner()), s -> s.put("layout", "expanded")));
        withTenant.put("tenantId", theirs.tenantId().toString());
        JsonNode refused = problem(call(HttpMethod.PUT, DRAFT, mine.owner(), withTenant.toString()), 400);
        assertType(refused, "validation");
        assertThat(problem(call(HttpMethod.POST, PUBLISH, mine.owner(), withTenant.toString()), 400).get("detail").asText())
                .isEqualTo("This property is not allowed.");
        // their live version number does not apply to my clinic
        conflictOf(publish(mine.owner(), 2, null, edit(standard(mine.owner()), s -> s.put("layout", "expanded"))));
        assertThat(stored(mine)).isEqualTo(mineBefore);
        assertThat(stored(theirs)).isEqualTo(theirsBefore);
        // the list of my clinic draws my card (v1), not theirs
        JsonNode list = listOf(mine.owner());
        assertThat(list.get("cardSetup").get("version").asInt()).isEqualTo(1);
        assertThat(list.toString()).doesNotContain("Zeta");
        // at the database: each clinic sees only its own rows
        assertThat(count(mine, "select count(*) from ipd.card_setup_version")).isZero();
        assertThat(count(mine, "select count(*) from ipd.card_setup_draft")).isZero();
        assertThat(count(theirs, "select count(*) from ipd.card_setup_version")).isEqualTo(1);
        assertThat(count(theirs, "select count(*) from ipd.card_setup_draft")).isEqualTo(1);
    }

    private ObjectNode writeBodyTree(Integer base, Long revision, JsonNode setup) throws Exception {
        return (ObjectNode) objectMapper.readTree(writeBody(base, revision, setup));
    }

    // ---------------- AC26, AC29, AC31, AC32, AC35, AC43: validation, V1 to V14 ----------------

    private record Bad(String name, Consumer<ObjectNode> change, String path, String message) {
    }

    private static Bad bad(String name, Consumer<ObjectNode> change, String path, String message) {
        return new Bad(name, change, path, message);
    }

    private static final String SAFETY_SECTION = " section is a safety section and stays on.";
    private static final String HEADER_MESSAGE = "The Card header section is always first and always on.";
    private static final String NAME_FIRST = "Patient name stays first in the Card header.";
    private static final String LABEL_LONG = "Keep the label to 40 characters.";
    private static final String LABEL_CONTROL = "Label cannot contain control or text-direction characters";

    private static String repeat(String s, int n) {
        return s.repeat(n);
    }

    @Test
    void everyRuleOfTheValidatorIs400WithItsPathAndMessageOnSaveAndOnPublishAndNothingIsStored() throws Exception {
        Clinic clinic = hospital();
        RequestPostProcessor owner = clinic.owner();
        ObjectNode std = standard(owner);
        List<Bad> cases = List.of(
                bad("layout", s -> s.put("layout", "wide"), "setup.layout", "Choose Compact, Standard or Expanded."),
                bad("cards 5", s -> s.put("cardsPerRow", 5), "setup.cardsPerRow", "Choose 2, 3 or 4 cards per row."),
                bad("cards 1", s -> s.put("cardsPerRow", 1), "setup.cardsPerRow", "Choose 2, 3 or 4 cards per row."),
                bad("cards 0", s -> s.put("cardsPerRow", 0), "setup.cardsPerRow", "Choose 2, 3 or 4 cards per row."),
                bad("section key", s -> ((ObjectNode) s.get("sections").get(6)).put("key", "bogus"), "setup.sections[6].key",
                        "Unknown section."),
                bad("section twice", s -> ((ObjectNode) s.get("sections").get(6)).put("key", "care"), "setup.sections[6].key",
                        "Each section can appear only once."),
                bad("section missing", s -> ((com.fasterxml.jackson.databind.node.ArrayNode) s.get("sections")).remove(6),
                        "setup.sections", "The Billing section is missing."),
                bad("header second", s -> moveSection(s, "header", 1), "setup.sections[1].key", HEADER_MESSAGE),
                bad("header off", s -> sectionOn(s, "header", false), "setup.sections[0].on", HEADER_MESSAGE),
                bad("status off", s -> sectionOn(s, "status", false), "setup.sections[1].on",
                        "The Workflow status" + SAFETY_SECTION),
                bad("vitals off", s -> sectionOn(s, "vitals", false), "setup.sections[4].on", "The Vitals" + SAFETY_SECTION),
                bad("clinical off", s -> sectionOn(s, "clinical", false), "setup.sections[5].on", "The Clinical" + SAFETY_SECTION),
                bad("unknown field", s -> fieldOf(s, "mobile").put("key", "bogus"), "setup.sections[0].fields[5].key",
                        "Unknown field."),
                bad("field twice", s -> fieldOf(s, "ipNumber").put("key", "bed"), "setup.sections[0].fields[4].key",
                        "Each field can appear only once."),
                bad("field missing", s -> removeField(s, "payer"), "setup.sections", "Payer is missing from the card setup."),
                bad("name second", s -> {
                    ((com.fasterxml.jackson.databind.node.ArrayNode) sectionOf(s, "header").get("fields")).insert(1,
                            ((com.fasterxml.jackson.databind.node.ArrayNode) sectionOf(s, "header").get("fields")).remove(0));
                }, "setup.sections[0].fields[1].key", NAME_FIRST),
                bad("name in another section", s -> moveField(s, "name", "stay"), "setup.sections[2].fields[5].key", NAME_FIRST),
                bad("name off", s -> fieldOn(s, "name", false), "setup.sections[0].fields[0].on", NAME_FIRST),
                bad("name label", s -> fieldLabel(s, "name", "Patient"), "setup.sections[0].fields[0].label", NAME_FIRST),
                bad("uhid off", s -> fieldOn(s, "uhid", false), "setup.sections[0].fields[1].on",
                        "UHID is a safety field and cannot be hidden."),
                bad("alerts off", s -> fieldOn(s, "alerts", false), "setup.sections[1].fields[0].on",
                        "Alerts is a safety field and cannot be hidden."),
                bad("early warning off", s -> fieldOn(s, "ews", false), "setup.sections[4].fields[5].on",
                        "Early warning is a safety field and cannot be hidden."),
                bad("allergies off", s -> fieldOn(s, "allergies", false), "setup.sections[5].fields[1].on",
                        "Allergies is a safety field and cannot be hidden."),
                bad("allergies in a section that is off", s -> moveField(s, "allergies", "billing"),
                        "setup.sections[6].fields[3].key", "Allergies is a safety field. Move it to a section that is switched on."),
                bad("uhid in a section that is off", s -> moveField(s, "uhid", "billing"), "setup.sections[6].fields[3].key",
                        "UHID is a safety field. Move it to a section that is switched on."),
                bad("uhid label", s -> fieldLabel(s, "uhid", "Patient ID"), "setup.sections[0].fields[1].label",
                        "The label of UHID cannot be changed."),
                bad("alerts label", s -> fieldLabel(s, "alerts", "Warnings"), "setup.sections[1].fields[0].label",
                        "The label of Alerts cannot be changed."),
                bad("early warning label", s -> fieldLabel(s, "ews", "Score"), "setup.sections[4].fields[5].label",
                        "The label of Early warning cannot be changed."),
                bad("allergies label", s -> fieldLabel(s, "allergies", "Sensitivities"), "setup.sections[5].fields[1].label",
                        "The label of Allergies cannot be changed."),
                bad("label 41", s -> fieldLabel(s, "bed", repeat("a", 41)), "setup.sections[0].fields[3].label", LABEL_LONG),
                bad("label 41 symbols", s -> fieldLabel(s, "bed", repeat("😀", 41)),
                        "setup.sections[0].fields[3].label", LABEL_LONG),
                bad("label 401", s -> fieldLabel(s, "bed", repeat("a", 401)), "setup.sections[0].fields[3].label", LABEL_LONG),
                bad("label control", s -> fieldLabel(s, "bed", "Bed\u0007x"), "setup.sections[0].fields[3].label", LABEL_CONTROL),
                bad("label tab", s -> fieldLabel(s, "bed", "Bed\tx"), "setup.sections[0].fields[3].label", LABEL_CONTROL),
                bad("label bidi", s -> fieldLabel(s, "bed", "‮bed"), "setup.sections[0].fields[3].label", LABEL_CONTROL),
                bad("action unknown", s -> ((ObjectNode) s.get("actions").get(4)).put("key", "bogus"), "setup.actions[4].key",
                        "Unknown quick action."),
                bad("action twice", s -> ((ObjectNode) s.get("actions").get(4)).put("key", "nursing"), "setup.actions[4].key",
                        "Each quick action can appear only once."),
                bad("action missing", s -> ((com.fasterxml.jackson.databind.node.ArrayNode) s.get("actions")).remove(4),
                        "setup.actions", "The Deposit quick action is missing."));
        Stored before = stored(clinic);
        for (Bad c : cases) {
            ObjectNode setup = edit(std, c.change());
            JsonNode save = problem(putDraft(owner, 1, null, setup), 400);
            assertType(save, "validation");
            assertThat(hasError(save, c.path(), c.message())).as("save: " + c.name() + " -> " + save.get("fieldErrors")).isTrue();
            JsonNode pub = problem(publish(owner, 1, null, setup), 400);
            assertType(pub, "validation");
            assertThat(hasError(pub, c.path(), c.message())).as("publish: " + c.name() + " -> " + pub.get("fieldErrors")).isTrue();
            assertThat(stored(clinic)).as("nothing stored after: " + c.name()).isEqualTo(before);
        }
        assertThat(cases).as("rules covered").hasSize(38);
    }

    @Test
    void theLabelLimitIs40CharactersNotBytesAndMarkupIsKeptAsTypedAndTrimmed() throws Exception {
        Clinic clinic = hospital();
        RequestPostProcessor owner = clinic.owner();
        ObjectNode std = standard(owner);
        // exactly 40 characters, and 40 symbols of two UTF-16 units each, are accepted
        long r1 = revisionOf(saveOk(owner, 1, null, edit(std, s -> fieldLabel(s, "bed", repeat("a", 40)))));
        JsonNode symbols = saveOk(owner, 1, r1, edit(std, s -> fieldLabel(s, "bed", repeat("😀", 40))));
        assertThat(fieldOf(symbols.get("draft").get("setup"), "bed").get("label").asText().codePointCount(0, 80)).isEqualTo(40);
        // markup is text: stored and sent back as typed (the screen shows it as typed), trimmed
        JsonNode markup = saveOk(owner, 1, revisionOf(symbols), edit(std, s -> fieldLabel(s, "ward", "  <b>x</b> & \"q\"  ")));
        assertThat(fieldOf(markup.get("draft").get("setup"), "ward").get("label").asText()).isEqualTo("<b>x</b> & \"q\"");
        // labels travel with the field; moving a field keeps its label
        JsonNode moved = saveOk(owner, 1, revisionOf(markup), edit(std, s -> {
            fieldLabel(s, "ward", "Unit");
            moveField(s, "ward", "care");
        }));
        assertThat(fieldKeys(moved.get("draft").get("setup"), "care")).containsExactly("doctor", "ward");
        assertThat(fieldOf(moved.get("draft").get("setup"), "ward").get("label").asText()).isEqualTo("Unit");
        // the same two limits on the whole-label unit "label of a field that is off" (labels are kept while a field is off)
        JsonNode off = saveOk(owner, 1, revisionOf(moved), edit(std, s -> {
            fieldLabel(s, "room", "Bay");
            fieldOn(s, "room", false);
        }));
        assertThat(fieldOf(off.get("draft").get("setup"), "room").get("label").asText()).isEqualTo("Bay");
    }

    @Test
    void aBodyThatIsNotTheDeclaredShapeIs400WithAProblemBodyAndNothingIsStored() throws Exception {
        Clinic clinic = hospital();
        RequestPostProcessor owner = clinic.owner();
        ObjectNode std = standard(owner);
        Stored before = stored(clinic);
        String ok = writeBody(1, null, std);
        ObjectNode extra = (ObjectNode) objectMapper.readTree(ok);
        extra.put("unknown", true);
        ObjectNode setupExtra = (ObjectNode) objectMapper.readTree(ok);
        ((ObjectNode) setupExtra.get("setup")).put("theme", "dark");
        ObjectNode fieldExtra = (ObjectNode) objectMapper.readTree(ok);
        ((ObjectNode) fieldExtra.get("setup").get("sections").get(0).get("fields").get(0)).put("color", "red");
        ObjectNode wrongType = (ObjectNode) objectMapper.readTree(ok);
        ((ObjectNode) wrongType.get("setup")).put("cardsPerRow", "many");
        ObjectNode sectionsNotList = (ObjectNode) objectMapper.readTree(ok);
        ((ObjectNode) sectionsNotList.get("setup")).put("sections", "x");
        ObjectNode noSetup = (ObjectNode) objectMapper.readTree(ok);
        noSetup.remove("setup");
        ObjectNode noRevisionProperty = (ObjectNode) objectMapper.readTree(ok);
        noRevisionProperty.remove("draftRevision");
        ObjectNode baseZero = (ObjectNode) objectMapper.readTree(ok);
        baseZero.put("baseVersion", 0);
        ObjectNode baseNull = (ObjectNode) objectMapper.readTree(ok);
        baseNull.putNull("baseVersion");
        ObjectNode revisionZero = (ObjectNode) objectMapper.readTree(ok);
        revisionZero.put("draftRevision", 0);
        ObjectNode noBase = (ObjectNode) objectMapper.readTree(ok);
        noBase.remove("baseVersion");
        String[] bodies = {extra.toString(), setupExtra.toString(), fieldExtra.toString(), wrongType.toString(),
            sectionsNotList.toString(), noSetup.toString(), noRevisionProperty.toString(), baseZero.toString(),
            baseNull.toString(), revisionZero.toString(), noBase.toString(), "[]", "\"x\"", "{}", "null", "{"};
        for (String body : bodies) {
            for (HttpMethod method : new HttpMethod[] {HttpMethod.PUT, HttpMethod.POST}) {
                String url = method == HttpMethod.PUT ? DRAFT : PUBLISH;
                JsonNode p = problem(call(method, url, owner, body), 400);
                assertThat(p.get("type").asText()).as(body).startsWith(PROBLEM_BASE);
                assertThat(p.get("detail").asText()).as("a message the user can read: " + body).isNotBlank();
            }
        }
        assertThat(extra.toString()).isNotEqualTo(ok);
        assertThat(stored(clinic)).isEqualTo(before);
        // an unknown property is named by its parent, not echoed
        JsonNode p = problem(call(HttpMethod.PUT, DRAFT, owner, extra.toString()), 400);
        assertThat(p.get("detail").asText()).isEqualTo("This property is not allowed.");
        assertThat(p.toString()).doesNotContain("unknown\"").doesNotContain("theme");
        // several mistakes together: one fieldError each, the first message is the detail
        ObjectNode many = edit(std, s -> {
            s.put("layout", "wide");
            s.put("cardsPerRow", 9);
            fieldOn(s, "uhid", false);
        });
        JsonNode all = problem(putDraft(owner, 1, null, many), 400);
        assertThat(all.get("fieldErrors")).hasSize(3);
        assertThat(all.get("detail").asText()).isEqualTo("Choose Compact, Standard or Expanded.");
        assertThat(stored(clinic)).isEqualTo(before);
    }

    // ---------------- AC30: where a safety field may sit ----------------

    @Test
    void aSafetyFieldMayMoveToAnotherSectionOnlyWhileThatSectionIsOn() throws Exception {
        Clinic clinic = hospital();
        RequestPostProcessor owner = clinic.owner();
        ObjectNode std = standard(owner);
        // Billing is off in the standard setup: refused
        ObjectNode intoOff = edit(std, s -> moveField(s, "allergies", "billing"));
        assertThat(hasMessage(problem(putDraft(owner, 1, null, intoOff), 400),
                "Allergies is a safety field. Move it to a section that is switched on.")).isTrue();
        // Billing on: accepted, in any order
        ObjectNode intoOn = edit(std, s -> {
            sectionOn(s, "billing", true);
            moveField(s, "allergies", "billing");
        });
        JsonNode saved = saveOk(owner, 1, null, intoOn);
        assertThat(fieldKeys(saved.get("draft").get("setup"), "billing")).contains("allergies");
        // then switching that section off while the safety field sits in it is refused (the server side of AC23)
        ObjectNode off = edit(intoOn, s -> sectionOn(s, "billing", false));
        JsonNode p = problem(putDraft(owner, 1, revisionOf(saved), off), 400);
        assertThat(hasMessage(p, "Allergies is a safety field. Move it to a section that is switched on.")).isTrue();
        // the card header may hold other fields, e.g. Early warning, after the patient name
        ObjectNode intoHeader = edit(std, s -> moveField(s, "ews", "header"));
        JsonNode headerSaved = saveOk(owner, 1, revisionOf(saved), intoHeader);
        assertThat(revisionOf(headerSaved)).isGreaterThan(revisionOf(saved));
        // other sections may be reordered, switched off (Care, Stay, Billing) and any non-safety field switched off
        ObjectNode reordered = edit(std, s -> {
            moveSection(s, "care", 1);
            sectionOn(s, "stay", false);
            fieldOn(s, "doctor", false);
        });
        assertThat(revisionOf(saveOk(owner, 1, revisionOf(headerSaved), reordered))).isGreaterThan(revisionOf(headerSaved));
    }

    // ---------------- AC14, AC1: Read-only and IPD Off ----------------

    @Test
    void whenIpdIsReadOnlyTheOwnerReadsButEveryWriteIs409IpdReadOnlyAfterTheBodyCheckAndNothingIsStored() throws Exception {
        Clinic clinic = hospital();
        Staff nurse = staff(clinic, "NURSE");
        assertThat(view(clinic.owner()).get("state").asText()).isEqualTo("trial");
        ObjectNode draftSetup = edit(standard(clinic.owner()), s -> s.put("layout", "compact"));
        saveOk(clinic.owner(), 1, null, draftSetup);
        expireTrial(clinic);
        JsonNode v = view(clinic.owner());
        assertThat(v.get("state").asText()).isEqualTo("readonly");
        assertThat(v.get("canEdit").asBoolean()).isFalse();
        assertThat(v.get("readOnlyReason").asText()).isEqualTo("IPD_READONLY");
        assertThat(v.get("draft").get("setup").get("layout").asText()).as("the owner still reads the draft").isEqualTo("compact");
        assertThat(v.get("versions")).hasSize(1);
        assertThat(view(nurse.auth()).get("readOnlyReason").asText()).isEqualTo("ROLE");
        Stored before = stored(clinic);
        ObjectNode setup = edit(standard(clinic.owner()), s -> s.put("layout", "expanded"));
        for (JsonNode p : new JsonNode[] {problem(putDraft(clinic.owner(), 1, 1L, setup), 409),
            problem(publish(clinic.owner(), 1, 1L, setup), 409), problem(discard(clinic.owner(), 1), 409)}) {
            assertType(p, "ipd-readonly");
            assertThat(p.get("detail").asText()).isEqualTo("IPD is read-only. Nothing can be changed.");
        }
        // a stale token or a bad body is not what is reported first: the body check comes before Read-only, the role before both
        assertType(problem(putDraft(clinic.owner(), 1, null, edit(setup, s -> s.put("layout", "wide"))), 400), "validation");
        forbiddenOf(putDraft(nurse.auth(), 1, 1L, setup));
        assertThat(stored(clinic)).isEqualTo(before);
        // the Admitted list still draws the published (here: standard) card
        assertThat(listOf(clinic.owner()).get("cardSetup").get("version").asInt()).isEqualTo(1);
    }

    @Test
    void whenIpdIsOffTheSetupAndEveryWriteAre403ModuleOff() throws Exception {
        Clinic clinic = newClinic();
        ObjectNode setup = sampleSetup();
        assertType(problem(call(HttpMethod.GET, CARD_SETUP, clinic.owner(), null), 403), "module-off");
        assertType(problem(putDraft(clinic.owner(), 1, null, setup), 403), "module-off");
        assertType(problem(publish(clinic.owner(), 1, null, setup), 403), "module-off");
        assertType(problem(discard(clinic.owner(), 1), 403), "module-off");
        Stored s = stored(clinic);
        assertThat(s.versions()).isZero();
        assertThat(s.drafts()).isZero();
        assertThat(s.cardAudits()).isZero();
    }

    /** A shape-correct body for a clinic whose IPD is off: the module check comes before the body check. */
    private ObjectNode sampleSetup() throws Exception {
        Clinic donor = hospital();
        return standard(donor.owner());
    }

    // ---------------- AC9: the versions list and its bound ----------------

    @Test
    void theVersionListHoldsAtMost50RowsAndKeepsV1UntilThe50StoredVersionsPushItOut() throws Exception {
        Clinic clinic = hospital();
        String setupJson = standard(clinic.owner()).toString();
        // 49 stored versions (2..50) + the virtual v1 = 50 rows
        insertVersions(clinic, setupJson, 2, 50);
        JsonNode v = view(clinic.owner());
        assertThat(v.get("live").get("version").asInt()).isEqualTo(50);
        assertThat(v.get("versions")).hasSize(50);
        assertThat(v.get("versions").get(0).get("version").asInt()).isEqualTo(50);
        assertThat(v.get("versions").get(49).get("version").asInt()).isEqualTo(1);
        // 50 stored versions (2..51): the list is cut at 50 rows, v1 is no longer in it
        insertVersions(clinic, setupJson, 51, 51);
        JsonNode cut = view(clinic.owner());
        assertThat(cut.get("versions")).hasSize(50);
        assertThat(cut.get("versions").get(0).get("version").asInt()).isEqualTo(51);
        assertThat(cut.get("versions").get(49).get("version").asInt()).isEqualTo(2);
        assertThat(cut.get("versions").get(0).get("live").asBoolean()).isTrue();
        assertThat(cut.get("live").get("version").asInt()).isEqualTo(51);
        // the next publish is v52 and Live moves to it
        ObjectNode changed = edit(standard(clinic.owner()), s -> s.put("cardsPerRow", 2));
        JsonNode r = publishOk(clinic.owner(), 51, null, changed);
        assertThat(r.get("live").get("version").asInt()).isEqualTo(52);
        assertThat(r.get("versions").get(0).get("version").asInt()).isEqualTo(52);
    }

    private void insertVersions(Clinic clinic, String setupJson, int from, int to) throws Exception {
        commitAsTenant(clinic.tenantId(), "insert into ipd.card_setup_version (version, setup, is_standard, changed_parts, "
                + "published_by) select g, ?::jsonb, true, array[]::text[], ?::uuid from generate_series(?, ?) g",
                setupJson, clinic.ownerUserId().toString(), from, to);
    }

    // ---------------- AC16, AC63: change history, record access and logs ----------------

    @Test
    void saveDraftAndPublishWriteOneHistoryLineEachWithVersionNumbersAndPartNamesOnlyAndNothingElseWritesOne() throws Exception {
        Clinic clinic = hospital();
        String label = uniqueName("Lb").substring(0, 12);
        ObjectNode setup = edit(standard(clinic.owner()), s -> {
            s.put("layout", "compact");
            fieldLabel(s, "ward", label);
        });
        long access = count(clinic, "select count(*) from patient.record_access");
        long[] revisions = new long[1];
        try (com.sahaj.platform.support.LogCapture logs = new com.sahaj.platform.support.LogCapture()) {
            long firstRevision = revisionOf(saveOk(clinic.owner(), 1, null, setup));
            // refused or empty writes write no line
            problem(putDraft(clinic.owner(), 1, null, edit(setup, s -> s.put("layout", "wide"))), 400);
            conflictOf(putDraft(clinic.owner(), 1, null, setup));
            ok(HttpMethod.DELETE, DRAFT + "?draftRevision=" + firstRevision, clinic.owner(), null, 200);
            long secondRevision = revisionOf(saveOk(clinic.owner(), 1, null, setup));
            assertThat(secondRevision).as("the token of the second draft is not the first one").isNotEqualTo(firstRevision);
            publishOk(clinic.owner(), 1, secondRevision, setup);
            revisions[0] = firstRevision;
            view(clinic.owner());
            listOf(clinic.owner());
            assertThat(logs.allText()).as("no label and no setup in a log line").doesNotContain(label);
        }
        List<String[]> saved = audit(clinic, "IPD_CARD_DRAFT_SAVED");
        List<String[]> published = audit(clinic, "IPD_CARD_PUBLISHED");
        assertThat(saved).as("two accepted saves").hasSize(2);
        assertThat(published).hasSize(1);
        String[] save = saved.get(0);
        assertThat(save[0]).isEqualTo("IPD_CONFIG");
        JsonNode before = objectMapper.readTree(save[2]);
        JsonNode after = objectMapper.readTree(save[3]);
        assertThat(keys(before)).containsExactly("liveVersion");
        assertThat(before.get("liveVersion").asInt()).isEqualTo(1);
        assertThat(keys(after)).containsExactlyInAnyOrder("liveVersion", "draftRevision", "changedParts");
        assertThat(after.get("draftRevision").asLong()).isEqualTo(revisions[0]);
        assertThat(texts(after.get("changedParts"))).containsExactly("layout", "fields");
        assertThat(save[4]).isEqualTo(clinic.ownerUserId().toString());
        String[] pub = published.get(0);
        assertThat(pub[0]).isEqualTo("IPD_CONFIG");
        JsonNode pBefore = objectMapper.readTree(pub[2]);
        JsonNode pAfter = objectMapper.readTree(pub[3]);
        assertThat(keys(pBefore)).containsExactly("version");
        assertThat(pBefore.get("version").asInt()).isEqualTo(1);
        assertThat(keys(pAfter)).containsExactlyInAnyOrder("version", "changedParts", "isStandard", "factsOn");
        assertThat(texts(pAfter.get("factsOn"))).as("no sensitive fact is switched on in this card").isEmpty();
        assertThat(pAfter.get("version").asInt()).isEqualTo(2);
        assertThat(pAfter.get("isStandard").asBoolean()).isFalse();
        assertThat(texts(pAfter.get("changedParts"))).containsExactly("layout", "fields");
        assertThat(pub[4]).isEqualTo(clinic.ownerUserId().toString());
        // no label, no layout name, no patient data in any history line
        for (String[] line : new String[][] {save, saved.get(1), pub}) {
            assertThat(String.join(" ", line)).doesNotContain(label).doesNotContain("compact").doesNotContain("Bay");
        }
        assertThat(audits(clinic, "IPD_CARD_DISCARDED")).as("discard writes none").isZero();
        // the Clinic setup change history (the platform audit endpoint) shows the lines and no label
        String history = ok(HttpMethod.GET, AUDIT_EVENTS, clinic.owner(), null, 200).toString();
        assertThat(history).contains("IPD_CARD_PUBLISHED").contains("IPD_CARD_DRAFT_SAVED").doesNotContain(label);
        // reading the setup and the list wrote no record-access row (AC63)
        assertThat(count(clinic, "select count(*) from patient.record_access")).isEqualTo(access);
    }

    /** Security review SAH-37, finding 3: saving a draft equal to the stored draft changes nothing and writes no history line. */
    @Test
    void savingAnUnchangedDraftAgainKeepsTheTokenAndWritesNoSecondHistoryLine() throws Exception {
        Clinic clinic = hospital();
        Staff second = secondOwner(clinic);
        ObjectNode compact = edit(standard(clinic.owner()), s -> s.put("layout", "compact"));
        JsonNode first = saveOk(clinic.owner(), 1, null, compact);
        long token = revisionOf(first);
        Stored before = stored(clinic);
        assertThat(before.cardAudits()).isEqualTo(1);
        // the same owner presses Save draft again with nothing changed, and so does the second owner
        JsonNode again = saveOk(clinic.owner(), 1, token, compact);
        assertThat(revisionOf(again)).as("the token stays").isEqualTo(token);
        JsonNode byOther = saveOk(second.auth(), 1, token, compact);
        assertThat(revisionOf(byOther)).isEqualTo(token);
        assertThat(stored(clinic)).as("nothing changes: same draft row, saver and time, one history line").isEqualTo(before);
        assertThat(byOther.get("draft").get("savedByName").asText()).isEqualTo(nameOf(clinic, clinic.ownerUserId()));
    }

    /** Security review SAH-37, finding 1b: the publish history line names which sensitive facts the card switches on (keys only). */
    @Test
    void thePublishHistoryLineNamesTheSensitiveFactsSwitchedOnAsKeysOnly() throws Exception {
        Clinic clinic = hospital();
        publishOk(clinic.owner(), 1, null, edit(standard(clinic.owner()), s -> {
            fieldOn(s, "diagnosis", true);
            fieldOn(s, "mobile", true);
            fieldOn(s, "bp", true);
            fieldLabel(s, "diagnosis", "Why admitted");
        }));
        String[] line = audit(clinic, "IPD_CARD_PUBLISHED").get(0);
        JsonNode after = objectMapper.readTree(line[3]);
        assertThat(texts(after.get("factsOn"))).containsExactly("diagnosis", "bp", "mobile");
        assertThat(String.join(" ", line)).as("keys only, never a label").doesNotContain("Why admitted");
    }

    @Test
    void aSecondOwnerIsNamedAsThePersonWhoPublished() throws Exception {
        Clinic clinic = hospital();
        Staff second = secondOwner(clinic);
        publishOk(second.auth(), 1, null, edit(standard(clinic.owner()), s -> s.put("cardsPerRow", 4)));
        List<String[]> lines = audit(clinic, "IPD_CARD_PUBLISHED");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)[4]).isEqualTo(second.userId().toString());
        assertThat(values(clinic, "select published_by::text from ipd.card_setup_version where version = 2"))
                .containsExactly(second.userId().toString());
    }
}
