package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-37 what is stored: the repair of a stored setup on every read (AC35, AC57, AC61), the protections of the two new
 * tables (row-level security, grants, append-only triggers, check constraints, the unique version key) and the known
 * hole in the draft revision (ADR-015 D5, docs/database/SAH-37.md section 7). Rows are written the way only the table
 * rules allow: as sahaj_app with the clinic bound, or as the table owner for the checks that the application cannot make.
 * Test plan: docs/test-plans/SAH-37.md.
 */
@OnTestDatabase
@WithPinPepper
class CardSetupStoredIT extends CardSetupSupport {

    private static final String VERSION_TABLE = "ipd.card_setup_version";
    private static final String DRAFT_TABLE = "ipd.card_setup_draft";

    private void insertVersion(Clinic clinic, int version, String setupJson) throws Exception {
        commitAsTenant(clinic.tenantId(), "insert into ipd.card_setup_version (version, setup, is_standard, changed_parts, "
                + "published_by) values (?, ?::jsonb, false, array['fields']::text[], ?::uuid)", version, setupJson,
                clinic.ownerUserId().toString());
    }

    private void insertDraft(Clinic clinic, int base, long revision, String setupJson) throws Exception {
        commitAsTenant(clinic.tenantId(), "insert into ipd.card_setup_draft (setup, base_version, revision, saved_by) "
                + "values (?::jsonb, ?, ?, ?::uuid)", setupJson, base, revision, clinic.ownerUserId().toString());
    }

    /** A stored v2 that lacks or breaks every locked item and holds keys the catalogue does not know. */
    private ObjectNode damaged(RequestPostProcessor owner) throws Exception {
        ObjectNode s = standard(owner);
        removeField(s, "uhid");
        fieldOn(s, "allergies", false);
        fieldLabel(s, "allergies", "Allergy-free");
        fieldLabel(s, "alerts", "Warnings");
        fieldOn(s, "ews", false);
        sectionOn(s, "vitals", false);
        sectionOn(s, "status", false);
        sectionOn(s, "clinical", false);
        removeField(s, "payer");
        moveField(s, "name", "stay");
        moveSection(s, "header", 3);
        ((ArrayNode) sectionOf(s, "stay").get("fields")).add(
                objectMapper.createObjectNode().put("key", "bogusField").put("on", true).put("label", ""));
        ((ArrayNode) s.get("sections")).add(objectMapper.createObjectNode().put("key", "ghost").put("on", true)
                .set("fields", objectMapper.createArrayNode()));
        ((ArrayNode) s.get("actions")).remove(4);
        s.put("layout", "huge");
        s.put("cardsPerRow", 9);
        return s;
    }

    // ---------------- AC35, AC57: repair on read, in memory only ----------------

    @Test
    void aStoredSetupThatLacksLockedItemsIsRepairedOnEveryReadAndTheStoredRowIsNotRewritten() throws Exception {
        Ward w = ward();
        Clinic clinic = w.clinic();
        insertVersion(clinic, 2, damaged(clinic.owner()).toString());
        String storedBefore = values(clinic, "select setup::text from ipd.card_setup_version where version = 2").get(0);
        assertThat(storedBefore).contains("Allergy-free").contains("bogusField").contains("ghost");

        JsonNode live = view(clinic.owner()).get("live");
        assertThat(live.get("version").asInt()).isEqualTo(2);
        assertThat(live.get("isStandard").asBoolean()).isFalse();
        JsonNode repaired = live.get("setup");
        assertRepairedLocks(repaired);
        assertThat(repaired.get("layout").asText()).as("an unknown layout falls back to the standard one").isEqualTo("standard");
        assertThat(repaired.get("cardsPerRow").asInt()).isEqualTo(3);
        assertThat(sectionKeys(repaired)).containsExactlyInAnyOrder(SECTION_KEYS).hasSize(7);
        assertThat(repaired.toString()).doesNotContain("bogusField").doesNotContain("ghost").doesNotContain("Allergy-free")
                .doesNotContain("Warnings");
        // a field that arrived late comes in off, in its standard section; a missing action is off
        assertThat(fieldKeys(repaired, "billing")).contains("payer");
        assertThat(fieldOf(repaired, "payer").get("on").asBoolean()).isFalse();
        assertThat(actionOf(repaired, "deposit").get("on").asBoolean()).isFalse();
        assertThat(repaired.get("actions")).hasSize(5);
        // the repaired setup passes every rule: saving it is a no-change (equal to live), not a 400
        assertThat(saveOk(clinic.owner(), 2, null, repaired).get("draft").isNull()).isTrue();

        // every role's Admitted list draws the repaired setup: the locked items are on the card
        for (RequestPostProcessor auth : new RequestPostProcessor[] {clinic.owner(), w.nurse().auth(), w.doctor().auth(),
            staff(clinic, "FRONT_DESK").auth()}) {
            JsonNode card = listOf(auth).get("cardSetup");
            assertThat(card.get("version").asInt()).isEqualTo(2);
            assertThat(card.get("fallback").asBoolean()).isFalse();
            assertThat(card.get("sections").get(0).get("key").asText()).isEqualTo("header");
            assertThat(card.get("sections").get(0).get("fields").get(0).get("key").asText()).isEqualTo("name");
            List<String> shown = new java.util.ArrayList<>();
            card.get("sections").forEach(sec -> sec.get("fields").forEach(f -> shown.add(f.get("key").asText())));
            assertThat(shown).contains("name", "uhid", "alerts", "ews", "allergies");
            for (JsonNode sec : card.get("sections")) {
                for (JsonNode f : sec.get("fields")) {
                    if (f.get("safety").asBoolean()) {
                        assertThat(f.get("label").asText()).as("a safety label is the standard name").isIn("UHID", "Alerts",
                                "Early warning", "Allergies");
                    }
                }
            }
        }
        // reading never rewrote the row
        assertThat(values(clinic, "select setup::text from ipd.card_setup_version where version = 2").get(0))
                .isEqualTo(storedBefore);
        assertThat(rows(clinic, "card_setup_version")).isEqualTo(1);
    }

    private static void assertRepairedLocks(JsonNode setup) {
        assertThat(sectionKeys(setup).get(0)).isEqualTo("header");
        assertThat(sectionOf(setup, "header").get("on").asBoolean()).isTrue();
        assertThat(fieldKeys(setup, "header").get(0)).isEqualTo("name");
        assertThat(fieldOf(setup, "name").get("on").asBoolean()).isTrue();
        for (String section : new String[] {"status", "vitals", "clinical"}) {
            assertThat(sectionOf(setup, section).get("on").asBoolean()).as(section).isTrue();
        }
        for (String field : new String[] {"uhid", "alerts", "ews", "allergies"}) {
            assertThat(fieldOf(setup, field).get("on").asBoolean()).as(field).isTrue();
            assertThat(fieldOf(setup, field).get("label").asText()).as(field + " label").isEmpty();
        }
    }

    @Test
    void aStoredDraftWithMissingLockedItemsIsRepairedForTheOwnerOnly() throws Exception {
        Clinic clinic = hospital();
        Staff nurse = staff(clinic, "NURSE");
        ObjectNode broken = standard(clinic.owner());
        removeField(broken, "allergies");
        fieldOn(broken, "uhid", false);
        sectionOn(broken, "header", false);
        insertDraft(clinic, 1, 1, broken.toString());
        JsonNode draft = view(clinic.owner()).get("draft");
        assertRepairedLocks(draft.get("setup"));
        assertThat(draft.get("revision").asLong()).as("the stored token (inserted as 1) is shown as stored").isEqualTo(1);
        // the owner can continue from the repaired draft; the next token is new
        assertThat(saveOk(clinic.owner(), 1, 1L, edit(draft.get("setup"), s -> s.put("layout", "compact")))
                .get("draft").get("revision").asLong()).isGreaterThan(1);
        assertThat(view(nurse.auth()).has("draft")).isFalse();
    }

    @Test
    void aStoredSetupThatCannotBeReadAtAllShowsTheStandardCardWithFallbackAndTheListStillLoads() throws Exception {
        Ward w = ward();
        Clinic clinic = w.clinic();
        insertVersion(clinic, 2, "{\"layout\":42,\"sections\":\"x\",\"actions\":3}");
        JsonNode v = view(clinic.owner());
        assertThat(v.get("live").get("version").asInt()).isEqualTo(2);
        assertThat(v.get("live").get("setup")).isEqualTo(v.get("standard"));
        for (RequestPostProcessor auth : new RequestPostProcessor[] {clinic.owner(), w.nurse().auth(), w.doctor().auth(),
            staff(clinic, "FRONT_DESK").auth()}) {
            JsonNode l = listOf(auth);
            JsonNode card = l.get("cardSetup");
            assertThat(card.get("fallback").asBoolean()).isTrue();
            assertThat(card.get("layout").asText()).isEqualTo("standard");
            assertThat(card.get("sections").get(0).get("fields").get(0).get("key").asText()).isEqualTo("name");
            assertThat(l.get("items")).as("the patient list still loads").hasSize(1);
        }
        // and a normal publish on top of it still works
        JsonNode next = publishOk(clinic.owner(), 2, null, edit(standard(clinic.owner()), s -> s.put("cardsPerRow", 2)));
        assertThat(next.get("live").get("version").asInt()).isEqualTo(3);
        assertThat(listOf(clinic.owner()).get("cardSetup").get("fallback").asBoolean()).isFalse();
    }

    // ---------------- the tables: RLS, grants, triggers, constraints ----------------

    @Test
    void bothTablesHaveForcedRowLevelSecurityAndOnlyTheAppPoliciesOfTheDesign() throws Exception {
        Clinic clinic = hospital();
        List<String> flags = column(clinic, "select c.relname || ':' || c.relrowsecurity || ':' || c.relforcerowsecurity "
                + "from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'ipd' "
                + "and c.relname in ('card_setup_version', 'card_setup_draft') order by c.relname");
        assertThat(flags).containsExactly("card_setup_draft:true:true", "card_setup_version:true:true");
        List<String> policies = column(clinic, "select c.relname || ':' || p.polname || ':' || p.polcmd::text || ':' "
                + "|| array_to_string(array(select r.rolname from pg_roles r where r.oid = any(p.polroles)), ',') "
                + "from pg_policy p join pg_class c on c.oid = p.polrelid join pg_namespace n on n.oid = c.relnamespace "
                + "where n.nspname = 'ipd' and c.relname in ('card_setup_version', 'card_setup_draft') order by 1");
        assertThat(policies).containsExactly(
                "card_setup_draft:p_card_setup_draft_delete:d:sahaj_app",
                "card_setup_draft:p_card_setup_draft_insert:a:sahaj_app",
                "card_setup_draft:p_card_setup_draft_select:r:sahaj_app",
                "card_setup_draft:p_card_setup_draft_update:w:sahaj_app",
                "card_setup_version:p_card_setup_version_insert:a:sahaj_app",
                "card_setup_version:p_card_setup_version_select:r:sahaj_app");
        // the tenant column and the policies compare with the same function
        assertThat(column(clinic, "select pg_get_expr(p.polqual, p.polrelid) from pg_policy p where p.polname = "
                + "'p_card_setup_version_select'").get(0)).contains("current_tenant");
    }

    @Test
    void withNoClinicBoundNothingIsVisibleAndNothingCanBeWrittenAndAnotherClinicsRowCannotBeNamed() throws Exception {
        Clinic mine = hospital();
        Clinic theirs = hospital();
        publishNow(theirs, edit(standard(theirs.owner()), s -> s.put("layout", "compact")));
        saveOk(theirs.owner(), 2, null, edit(standard(theirs.owner()), s -> s.put("layout", "expanded")));
        assertThat(unboundCount("select count(*) from ipd.card_setup_version")).isZero();
        assertThat(unboundCount("select count(*) from ipd.card_setup_draft")).isZero();
        assertThat(count(mine, "select count(*) from ipd.card_setup_version")).isZero();
        assertThat(count(mine, "select count(*) from ipd.card_setup_draft")).isZero();
        String noTenantInsert = "insert into ipd.card_setup_draft (setup, base_version, revision, saved_by) "
                + "values ('{}'::jsonb, 1, 1, gen_random_uuid())";
        assertThat(appErrNoTenant(noTenantInsert).state()).isIn("23502", "42501");
        // naming another clinic's id is refused: the column is not in the insert grant, and the policy would refuse it too
        String foreign = "insert into ipd.card_setup_draft (tenant_id, setup, base_version, revision, saved_by) "
                + "values (?::uuid, '{}'::jsonb, 1, 1, gen_random_uuid())";
        assertThat(appErr(mine, foreign, theirs.tenantId().toString()).state()).isEqualTo("42501");
        assertThat(appErr(mine, foreign, mine.tenantId().toString()).state()).isEqualTo("42501");
        // their rows cannot be changed or removed from my session
        Long changed = db().inTenant(mine.tenantId(), c -> {
            DbAccess.execute(c, "update ipd.card_setup_draft set revision = 99");
            return DbAccess.count(c, "select count(*) from ipd.card_setup_draft where revision = 99");
        });
        assertThat(changed.longValue()).isZero();
        assertThat(count(theirs, "select count(*) from ipd.card_setup_draft where revision = 99")).isZero();
        db().inTenant(mine.tenantId(), c -> {
            DbAccess.execute(c, "delete from ipd.card_setup_draft");
            return null;
        });
        assertThat(count(theirs, "select count(*) from ipd.card_setup_draft")).isEqualTo(1);
    }

    @Test
    void theVersionTableIsAppendOnlyForEveryoneAndTheAppCanOnlyInsertTheDeclaredColumns() throws Exception {
        Clinic clinic = hospital();
        publishNow(clinic, edit(standard(clinic.owner()), s -> s.put("layout", "compact")));
        // the app has no update, delete or truncate right at all
        assertThat(appErr(clinic, "update ipd.card_setup_version set is_standard = true").state()).isEqualTo("42501");
        assertThat(appErr(clinic, "delete from ipd.card_setup_version").state()).isEqualTo("42501");
        assertThat(appErr(clinic, "truncate ipd.card_setup_version").state()).isEqualTo("42501");
        // the table owner is stopped by the trigger as well
        SqlErr update = ownerErr(VERSION_TABLE, "update ipd.card_setup_version set is_standard = true");
        assertThat(update.state()).isEqualTo("42501");
        assertThat(update.message()).contains("append-only");
        SqlErr delete = ownerErr(VERSION_TABLE, "delete from ipd.card_setup_version");
        assertThat(delete.state()).isEqualTo("42501");
        assertThat(delete.message()).contains("append-only");
        SqlErr truncate = ownerErr(VERSION_TABLE, "truncate ipd.card_setup_version");
        assertThat(truncate.state()).isEqualTo("42501");
        assertThat(truncate.message()).contains("append-only");
        assertThat(rows(clinic, "card_setup_version")).isEqualTo(1);
        // columns the app may not name
        String rest = "version, setup, is_standard, changed_parts, published_by) values (%s, 3, '{}'::jsonb, false, array[]::text[], "
                + "gen_random_uuid())";
        assertThat(appErr(clinic, "insert into ipd.card_setup_version (id, " + rest.formatted("gen_random_uuid()")).state())
                .isEqualTo("42501");
        assertThat(appErr(clinic, "insert into ipd.card_setup_version (tenant_id, " + rest.formatted("gen_random_uuid()")).state())
                .isEqualTo("42501");
        assertThat(appErr(clinic, "insert into ipd.card_setup_version (published_at, " + rest.formatted("now()")).state())
                .isEqualTo("42501");
    }

    @Test
    void theCheckConstraintsAndTheUniqueVersionKeyRefuseWhatTheValidatorWouldHaveStopped() throws Exception {
        Clinic clinic = hospital();
        String cols = "insert into ipd.card_setup_version (version, setup, is_standard, changed_parts, published_by) ";
        String ok = "'{}'::jsonb, false, array['layout']::text[], gen_random_uuid())";
        assertThat(appErr(clinic, cols + "values (1, " + ok).state()).as("version >= 2").isEqualTo("23514");
        assertThat(appErr(clinic, cols + "values (0, " + ok).state()).isEqualTo("23514");
        assertThat(appErr(clinic, cols + "values (2, '[]'::jsonb, false, array[]::text[], gen_random_uuid())").state())
                .as("setup must be an object").isEqualTo("23514");
        assertThat(appErr(clinic, cols + "values (2, jsonb_build_object('x', repeat('a', 17000)), false, array[]::text[], "
                + "gen_random_uuid())").state()).as("setup at most 16 KB").isEqualTo("23514");
        assertThat(appErr(clinic, cols + "values (2, '{}'::jsonb, false, array['bogus']::text[], gen_random_uuid())").state())
                .as("an unknown changed part").isEqualTo("23514");
        assertThat(appErr(clinic, cols + "values (2, '{}'::jsonb, false, array['layout', null]::text[], gen_random_uuid())")
                .state()).as("a null element").isEqualTo("23514");
        assertThat(appErr(clinic, cols + "values (2, '{}'::jsonb, false, null, gen_random_uuid())").state())
                .isEqualTo("23502");
        // the same version twice for one clinic is refused (23505), and the empty list of parts passes
        assertThat(appErr(clinic, cols + "select 2, '{}'::jsonb, false, array[]::text[], gen_random_uuid() "
                + "from generate_series(1, 2)").state()).isEqualTo("23505");
        commitAsTenant(clinic.tenantId(), cols + "values (2, '{}'::jsonb, false, array[]::text[], gen_random_uuid())");
        assertThat(appErr(clinic, cols + "values (2, " + ok).state()).as("version 2 exists").isEqualTo("23505");
        // the draft table
        String draft = "insert into ipd.card_setup_draft (setup, base_version, revision, saved_by) ";
        assertThat(appErr(clinic, draft + "values ('{}'::jsonb, 0, 1, gen_random_uuid())").state()).isEqualTo("23514");
        assertThat(appErr(clinic, draft + "values ('{}'::jsonb, 1, 0, gen_random_uuid())").state()).isEqualTo("23514");
        assertThat(appErr(clinic, draft + "values ('[]'::jsonb, 1, 1, gen_random_uuid())").state()).isEqualTo("23514");
        assertThat(appErr(clinic, draft + "values (jsonb_build_object('x', repeat('a', 17000)), 1, 1, gen_random_uuid())")
                .state()).isEqualTo("23514");
        commitAsTenant(clinic.tenantId(), draft + "values ('{}'::jsonb, 1, 1, gen_random_uuid())");
        assertThat(appErr(clinic, draft + "values ('{}'::jsonb, 1, 2, gen_random_uuid())").state())
                .as("one draft per clinic").isEqualTo("23505");
    }

    @Test
    void theDraftTableAllowsReplaceAndDeleteByTheAppButNotMovingTheRowToAnotherClinicOrTruncate() throws Exception {
        Clinic clinic = hospital();
        long saved = revisionOf(saveOk(clinic.owner(), 1, null, edit(standard(clinic.owner()), s -> s.put("layout", "compact"))));
        appOkCommitted(clinic, "update ipd.card_setup_draft set revision = revision + 1, saved_at = now()");
        assertThat(values(clinic, "select revision::text from ipd.card_setup_draft")).containsExactly(String.valueOf(saved + 1));
        assertThat(appErr(clinic, "update ipd.card_setup_draft set tenant_id = gen_random_uuid()").state()).isEqualTo("42501");
        assertThat(appErr(clinic, "update ipd.card_setup_draft set base_version = 0").state()).isEqualTo("23514");
        assertThat(appErr(clinic, "truncate ipd.card_setup_draft").state()).isEqualTo("42501");
        SqlErr truncate = ownerErr(DRAFT_TABLE, "truncate ipd.card_setup_draft");
        assertThat(truncate.state()).isEqualTo("42501");
        assertThat(truncate.message()).contains("append-only");
        appOkCommitted(clinic, "delete from ipd.card_setup_draft");
        assertThat(rows(clinic, "card_setup_draft")).isZero();
    }

    private long unboundCount(String sql) throws Exception {
        Long n = db().asApp(c -> DbAccess.count(c, sql));
        return n;
    }

    private void appOkCommitted(Clinic clinic, String sql) throws Exception {
        commitAsTenant(clinic.tenantId(), sql);
    }

    // ---------------- the former hole: the revision restarted at 1 (fixed in the SAH-37 fix pass) ----------------

    /**
     * ADR-015 amendment A2: the draft revision is an opaque token that never repeats for a clinic. Before the fix pass the
     * revision restarted at 1 when the row was gone, so a stale editor holding (base 1, revision 1) passed both comparisons
     * on a draft that another owner had discarded and recreated on the same base, and overwrote it (this test used to document
     * that, "knownGap..."). Now the stale editor is refused with 409 card-setup-changed on Save draft and on Publish, and
     * nothing is stored.
     */
    @Test
    void aStaleEditorHoldingTheOldRevisionIsRefusedOnSaveAndPublishAfterADiscardAndRecreateOnTheSameBase() throws Exception {
        Clinic clinic = hospital();
        Staff other = secondOwner(clinic);
        // owner A saves the first draft on base version 1 and keeps its token pair (base 1, revision r1)
        JsonNode first = saveOk(clinic.owner(), 1, null, edit(standard(clinic.owner()), s -> s.put("layout", "compact")));
        long r1 = first.get("draft").get("revision").asLong();
        // owner B discards it and starts a new draft on the same base
        ok(HttpMethod.DELETE, DRAFT + "?draftRevision=" + r1, other.auth(), null, 200);
        JsonNode recreated = saveOk(other.auth(), 1, null, edit(standard(clinic.owner()), s -> s.put("layout", "expanded")));
        long r2 = recreated.get("draft").get("revision").asLong();
        assertThat(r2).as("the token of a recreated draft never repeats an earlier one").isNotEqualTo(r1);
        Stored before = stored(clinic);
        // A, still holding the old pair, tries Save draft: refused, B's draft is untouched
        conflictOf(putDraft(clinic.owner(), 1, r1, edit(standard(clinic.owner()), s -> s.put("cardsPerRow", 4))));
        assertThat(stored(clinic)).as("a refused save stores nothing").isEqualTo(before);
        assertThat(view(other.auth()).get("draft").get("setup").get("layout").asText()).isEqualTo("expanded");
        // the same through Publish: refused, no version, B's recreated draft still there
        conflictOf(publish(clinic.owner(), 1, r1, edit(standard(clinic.owner()), s -> s.put("cardsPerRow", 4))));
        assertThat(stored(clinic)).as("a refused publish stores nothing").isEqualTo(before);
        assertThat(view(other.auth()).get("live").get("version").asInt()).isEqualTo(1);
        // and A with the CURRENT token (after reloading) is accepted
        JsonNode mine = ok(HttpMethod.PUT, DRAFT, clinic.owner(), writeBody(1, r2, edit(standard(clinic.owner()), s -> s.put("cardsPerRow", 4))), 200);
        assertThat(mine.get("draft").get("revision").asLong()).isNotIn(r1, r2);
        // the token never repeats across repeated discard and recreate cycles
        java.util.Set<Long> seen = new java.util.HashSet<>(java.util.List.of(r1, r2, mine.get("draft").get("revision").asLong()));
        long current = mine.get("draft").get("revision").asLong();
        for (int i = 0; i < 4; i++) {
            ok(HttpMethod.DELETE, DRAFT + "?draftRevision=" + current, other.auth(), null, 200);
            current = saveOk(other.auth(), 1, null, edit(standard(clinic.owner()), s -> s.put("layout", "compact"))).get("draft").get("revision").asLong();
            assertThat(seen.add(current)).as("revision " + current + " was used before").isTrue();
        }
    }

    @Test
    void aStaleEditorIsRefusedWhenTheRecreatedDraftHasAnotherBaseVersionBecauseTheServiceAlsoComparesTheBase() throws Exception {
        Clinic clinic = hospital();
        Staff other = secondOwner(clinic);
        long staleRevision = saveOk(clinic.owner(), 1, null, edit(standard(clinic.owner()), s -> s.put("layout", "compact"))).get("draft").get("revision").asLong();
        // B discards, publishes v2 and starts a new draft on base 2 (a new token, and base 2)
        ok(HttpMethod.DELETE, DRAFT + "?draftRevision=" + staleRevision, other.auth(), null, 200);
        publishOk(other.auth(), 1, null, edit(standard(clinic.owner()), s -> s.put("cardsPerRow", 2)));
        JsonNode recreated = saveOk(other.auth(), 2, null, edit(standard(clinic.owner()), s -> s.put("cardsPerRow", 4)));
        assertThat(recreated.get("draft").get("revision").asLong()).isNotEqualTo(staleRevision);
        assertThat(recreated.get("draft").get("baseVersion").asInt()).isEqualTo(2);
        Stored before = stored(clinic);
        conflictOf(putDraft(clinic.owner(), 1, staleRevision, edit(standard(clinic.owner()), s -> s.put("layout", "expanded"))));
        conflictOf(publish(clinic.owner(), 1, staleRevision, edit(standard(clinic.owner()), s -> s.put("layout", "expanded"))));
        assertThat(stored(clinic)).isEqualTo(before);
        // and with the current base but the old draft token pair of a draft that no longer exists as such
        assertThat(view(clinic.owner()).get("draft").get("setup").get("cardsPerRow").asInt()).isEqualTo(4);
    }

    // ---------------- DEC-032 G-5: schema_version on the two jsonb tables ----------------

    @Test
    void theSchemaVersionColumnIsASmallintNotNullWithDefault1AndACheckOnBothTables() throws Exception {
        Clinic clinic = hospital();
        List<String> columns = column(clinic, "select table_name || ':' || data_type || ':' || is_nullable || ':' "
                + "|| column_default from information_schema.columns where table_schema = 'ipd' "
                + "and table_name in ('card_setup_version', 'card_setup_draft') and column_name = 'schema_version' "
                + "order by table_name");
        assertThat(columns).containsExactly("card_setup_draft:smallint:NO:1", "card_setup_version:smallint:NO:1");
        List<String> checks = column(clinic, "select conrelid::regclass::text || ':' || conname from pg_constraint "
                + "where conname in ('ck_card_setup_version_schema_version', 'ck_card_setup_draft_schema_version') order by 1");
        assertThat(checks).containsExactly("ipd.card_setup_draft:ck_card_setup_draft_schema_version",
                "ipd.card_setup_version:ck_card_setup_version_schema_version");
    }

    @Test
    void aNewDraftAndANewVersionStoreSchemaVersion1AndAnUpdateKeepsIt() throws Exception {
        Clinic clinic = hospital();
        RequestPostProcessor owner = clinic.owner();
        JsonNode first = saveOk(owner, 1, null, edit(standard(owner), s -> s.put("layout", "compact")));
        assertThat(values(clinic, "select schema_version::text from ipd.card_setup_draft")).containsExactly("1");
        JsonNode second = saveOk(owner, 1, revisionOf(first), edit(standard(owner), s -> s.put("layout", "expanded")));
        assertThat(revisionOf(second)).as("a real update, not a no-change").isNotEqualTo(revisionOf(first));
        assertThat(values(clinic, "select schema_version::text from ipd.card_setup_draft")).as("update keeps it")
                .containsExactly("1");
        publishOk(owner, 1, revisionOf(second), edit(standard(owner), s -> s.put("layout", "expanded")));
        assertThat(column(clinic, "select version::text || ':' || schema_version::text from ipd.card_setup_version "
                + "order by version")).containsExactly("2:1");
        saveOk(owner, 2, null, edit(standard(owner), s -> s.put("cardsPerRow", 4)));
        publishNow(clinic, edit(standard(owner), s -> s.put("cardsPerRow", 4)));
        assertThat(column(clinic, "select version::text || ':' || schema_version::text from ipd.card_setup_version "
                + "order by version")).containsExactly("2:1", "3:1");
    }

    @Test
    void aRowInsertedWithoutTheColumnGetsTheDefault1AndZeroOrNegativeIsRefusedWith23514() throws Exception {
        Clinic clinic = hospital();
        insertVersion(clinic, 2, standard(clinic.owner()).toString());
        insertDraft(clinic, 2, 5, standard(clinic.owner()).toString());
        assertThat(values(clinic, "select schema_version::text from ipd.card_setup_version")).containsExactly("1");
        assertThat(values(clinic, "select schema_version::text from ipd.card_setup_draft")).containsExactly("1");
        String version = "insert into ipd.card_setup_version (version, setup, is_standard, changed_parts, published_by, "
                + "schema_version) values (3, '{}'::jsonb, false, array[]::text[], gen_random_uuid(), %s)";
        assertThat(appErr(clinic, version.formatted("0")).state()).as("version table, 0").isEqualTo("23514");
        assertThat(appErr(clinic, version.formatted("-1")).state()).as("version table, -1").isEqualTo("23514");
        assertThat(appErr(clinic, version.formatted("null")).state()).as("version table, null").isEqualTo("23502");
        assertThat(appErr(clinic, version.formatted("32768")).state()).as("past smallint").isEqualTo("22003");
        Clinic empty = hospital();
        String draft = "insert into ipd.card_setup_draft (setup, base_version, revision, saved_by, schema_version) "
                + "values ('{}'::jsonb, 1, 1, gen_random_uuid(), %s)";
        assertThat(appErr(empty, draft.formatted("0")).state()).as("draft table, 0").isEqualTo("23514");
        assertThat(appErr(empty, draft.formatted("-1")).state()).as("draft table, -1").isEqualTo("23514");
        assertThat(appErr(empty, draft.formatted("null")).state()).as("draft table, null").isEqualTo("23502");
        // an update of the existing draft to 0 is refused as well; the version table cannot be updated at all
        assertThat(appErr(clinic, "update ipd.card_setup_draft set schema_version = 0").state()).isEqualTo("23514");
        assertThat(appErr(clinic, "update ipd.card_setup_version set schema_version = 2").state()).isEqualTo("42501");
        assertThat(values(clinic, "select schema_version::text from ipd.card_setup_draft")).containsExactly("1");
        assertThat(rows(empty, "card_setup_draft")).isZero();
        // 1 and higher are accepted by the database (the application decides what it can read)
        commitAsTenant(empty.tenantId(), draft.formatted("2"));
        assertThat(values(empty, "select schema_version::text from ipd.card_setup_draft")).containsExactly("2");
    }

    @Test
    void aLiveVersionWithSchemaVersion2GivesTheStandardSetupWithFallbackOnTheCardSetupAndOnTheAdmittedListAndThePatientListStillLoads()
            throws Exception {
        Ward w = ward();
        Clinic clinic = w.clinic();
        ObjectNode future = edit(standard(clinic.owner()), s -> {
            s.put("layout", "compact");
            s.put("cardsPerRow", 2);
            fieldLabel(s, "ward", "Unit");
        });
        commitAsTenant(clinic.tenantId(), "insert into ipd.card_setup_version (version, setup, is_standard, changed_parts, "
                + "published_by, schema_version) values (2, ?::jsonb, false, array['layout']::text[], ?::uuid, 2)",
                future.toString(), clinic.ownerUserId().toString());
        JsonNode v = view(clinic.owner());
        assertThat(v.get("live").get("version").asInt()).isEqualTo(2);
        assertThat(v.get("live").get("setup")).as("the newer shape is not read: the standard setup").isEqualTo(v.get("standard"));
        assertThat(v.get("live").get("setup").get("layout").asText()).isEqualTo("standard");
        assertThat(v.get("live").get("isStandard").asBoolean()).isTrue();
        for (RequestPostProcessor auth : new RequestPostProcessor[] {clinic.owner(), w.nurse().auth(), w.doctor().auth(),
            staff(clinic, "FRONT_DESK").auth()}) {
            JsonNode l = listOf(auth);
            JsonNode card = l.get("cardSetup");
            assertThat(card.get("fallback").asBoolean()).isTrue();
            assertThat(card.get("version").asInt()).isEqualTo(2);
            assertThat(card.get("layout").asText()).isEqualTo("standard");
            assertThat(card.toString()).doesNotContain("Unit");
            assertThat(l.get("items")).as("the patient list still loads").hasSize(1);
        }
        assertThat(values(clinic, "select schema_version::text from ipd.card_setup_version")).as("never rewritten")
                .containsExactly("2");
        // the owner can publish on top of it: version 3 is written in the shape this build knows
        JsonNode next = publishOk(clinic.owner(), 2, null, edit(standard(clinic.owner()), s -> s.put("cardsPerRow", 2)));
        assertThat(next.get("live").get("version").asInt()).isEqualTo(3);
        assertThat(column(clinic, "select version::text || ':' || schema_version::text from ipd.card_setup_version "
                + "order by version")).containsExactly("2:2", "3:1");
        assertThat(listOf(clinic.owner()).get("cardSetup").get("fallback").asBoolean()).isFalse();
    }

    @Test
    void aDraftWithSchemaVersion2GivesTheStandardSetupInTheOwnersDraftAndTheNextSaveWritesVersion1() throws Exception {
        Clinic clinic = hospital();
        Staff nurse = staff(clinic, "NURSE");
        ObjectNode future = edit(standard(clinic.owner()), s -> {
            s.put("layout", "expanded");
            fieldLabel(s, "ward", "Unit");
        });
        commitAsTenant(clinic.tenantId(), "insert into ipd.card_setup_draft (setup, base_version, revision, saved_by, "
                + "schema_version) values (?::jsonb, 1, 9, ?::uuid, 2)", future.toString(), clinic.ownerUserId().toString());
        JsonNode v = view(clinic.owner());
        assertThat(v.get("draft").get("setup")).as("the newer shape is not read: the standard setup").isEqualTo(v.get("standard"));
        assertThat(v.get("draft").get("revision").asLong()).isEqualTo(9);
        assertThat(v.get("live").get("version").asInt()).isEqualTo(1);
        assertThat(v.toString()).doesNotContain("Unit");
        // other roles never see the draft, whatever its shape
        assertThat(view(nurse.auth()).has("draft")).isFalse();
        assertThat(values(clinic, "select schema_version::text from ipd.card_setup_draft")).as("reading does not rewrite")
                .containsExactly("2");
        // the owner's next Save draft writes the shape this build knows
        JsonNode saved = saveOk(clinic.owner(), 1, 9L, edit(standard(clinic.owner()), s -> s.put("layout", "compact")));
        assertThat(saved.get("draft").get("setup").get("layout").asText()).isEqualTo("compact");
        assertThat(values(clinic, "select schema_version::text from ipd.card_setup_draft")).containsExactly("1");
    }

    @Test
    void schemaVersionIsInvisibleToTheOtherClinicAndNeverInTheApiBody() throws Exception {
        Clinic mine = hospital();
        Clinic theirs = hospital();
        commitAsTenant(theirs.tenantId(), "insert into ipd.card_setup_version (version, setup, is_standard, changed_parts, "
                + "published_by, schema_version) values (2, '{}'::jsonb, false, array[]::text[], ?::uuid, 2)",
                theirs.ownerUserId().toString());
        // my clinic is not affected by their unreadable shape
        JsonNode v = view(mine.owner());
        assertThat(v.get("live").get("version").asInt()).isEqualTo(1);
        assertThat(count(mine, "select count(*) from ipd.card_setup_version")).isZero();
        assertThat(call(HttpMethod.GET, CARD_SETUP, mine.owner(), null).andReturn().getResponse().getContentAsString())
                .doesNotContain("schema_version").doesNotContain("schemaVersion");
        assertThat(call(HttpMethod.GET, CARD_SETUP, theirs.owner(), null).andReturn().getResponse().getContentAsString())
                .doesNotContain("schema_version").doesNotContain("schemaVersion");
    }
}
