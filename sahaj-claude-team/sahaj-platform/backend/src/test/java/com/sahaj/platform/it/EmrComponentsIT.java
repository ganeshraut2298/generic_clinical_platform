package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-56 (EMR list), SAH-48 (prescription rows, drug-class allergy check and its review audit) and SAH-57 (body map, MLC
 * rule and audit, pin numbers) through the real draft save of an OPD record (ADR-022 D2 to D5, D8): the component values
 * are checked, normalised and stamped by the server; the covered sibling fields and the old phase 2 shapes are refused;
 * Done is computed from the components. Real database as {@code sahaj_app}; throwaway data. Test plan:
 * docs/test-plans/SAH-48-59.md.
 */
@OnTestDatabase
class EmrComponentsIT extends EmrPhase3Support {

    private static void assertFieldError(JsonNode problem, String field) {
        assertThat(problem.get("fieldErrors").get(0).get("field").asText()).isEqualTo(field);
    }

    private static JsonNode item(JsonNode record, int index) {
        return valueOf(record, "complaints", "complaint").get("items").get(index);
    }

    private Rec lesionRecord(Clinic clinic, Staff doctor) throws Exception {
        return switchDepartment(startRecord(clinic, doctor), "derm");
    }

    /** The lesion map section with the required Morphology filled too, so the section can be Done (a required field must be filled). */
    private ObjectNode lesionDone(JsonNode map) {
        ObjectNode section = oneField("lesion_map", "body_map", map);
        ((ObjectNode) section.get("values")).put("morphology", "Plaque");
        return section;
    }

    private static JsonNode bodyMapOf(Rec rec) {
        return valueOf(rec.record(), "lesion_map", "body_map");
    }

    // ================= the library fields carry the binding (ADR-022 D2) =================

    @Test
    void theComponentFieldsAndTheFieldsTheyCoverAreMarkedInTheLibraryByTheirValueKind() throws Exception {
        Clinic clinic = newClinic();
        String[][] expected = {
                {"complaints", "complaint", "COMPONENT"}, {"complaints", "duration", "COVERED"},
                {"complaints", "severity", "COVERED"}, {"complaints", "notes", "TEXT"},
                {"diagnosis", "provisional_diagnosis", "COMPONENT"}, {"diagnosis", "final_diagnosis", "COVERED"},
                {"diagnosis", "type", "COVERED"}, {"investigations", "lab_tests", "COMPONENT"},
                {"investigations", "radiology", "COVERED"}, {"rx", "drug", "COMPONENT"},
                {"rx", "dose_frequency", "COVERED"}, {"rx", "duration", "COVERED"}, {"rx", "timing", "COVERED"},
                {"rx", "instructions", "COVERED"}, {"advice", "advice", "COMPONENT"},
                {"procedures", "procedure", "COMPONENT"}, {"lesion_map", "body_map", "COMPONENT"}};
        for (String[] e : expected) {
            JsonNode section = get(LIBRARY + "/" + e[0], clinic.owner());
            JsonNode field = null;
            for (JsonNode f : section.get("fields")) {
                if (e[1].equals(f.get("key").asText())) {
                    field = f;
                }
            }
            assertThat(field).as(e[0] + "." + e[1]).isNotNull();
            assertThat(field.get("valueKind").asText()).as(e[0] + "." + e[1]).isEqualTo(e[2]);
        }
        JsonNode rx = null;
        for (JsonNode f : get(LIBRARY + "/rx", clinic.owner()).get("fields")) {
            if ("drug".equals(f.get("key").asText())) {
                rx = f;
            }
        }
        assertThat(rx.get("component").get("type").asText()).isEqualTo("PRESCRIPTION");
        JsonNode complaint = get(LIBRARY + "/complaints", clinic.owner()).get("fields").get(0);
        assertThat(complaint.get("component").get("type").asText()).isEqualTo("EMR_LIST");
        assertThat(complaint.get("component").get("listKind").asText()).isEqualTo("COMPLAINTS");
        // the allergy list is a plain item list, not a component (the allergy classes are its picked items)
        JsonNode allergy = get(LIBRARY + "/allergy", clinic.owner()).get("fields").get(0);
        assertThat(allergy.get("valueKind").asText()).isEqualTo("ITEM_LIST");
    }

    @Test
    void theRecordViewCarriesTheNewFieldsEmptyAtTheStart() throws Exception {
        Rec rec = newRec();
        JsonNode record = rec.record();
        assertThat(record.get("allergyAlerts")).isEmpty();
        assertThat(record.get("completeBlockers")).isEmpty();
        assertThat(record.get("lastSignedPrescription").isNull()).isTrue();
        assertThat(record.get("printPreferences").get("language").isNull()).isTrue();
        assertThat(record.get("printPreferences").get("paperSize").isNull()).isTrue();
        assertThat(record.get("prescriptionIsOrder").asBoolean()).as("the EMR prescription never creates an IPD order").isFalse();
        assertThat(record.get("header").get("mlc").asBoolean()).isFalse();
    }

    // ================= SAH-56 · EMR list =================

    @Test
    void aTypedItemIsCapitalisedGetsDefaultAttributesAndIsStampedByTheServerAndTheOrderOfAddingIsKept() throws Exception {
        Rec rec = newRec();
        Rec saved = save(rec, complaintsSection(typedItem("fever"), typedItem("Cough with phlegm")));
        JsonNode first = item(saved.record(), 0);
        assertThat(first.get("text").asText()).as("a typed item gets its first letter capitalised").isEqualTo("Fever");
        assertThat(first.get("itemKey").isNull()).isTrue();
        assertThat(first.get("newToList").asBoolean()).isTrue();
        assertThat(first.get("attr1").asText()).as("duration default 3 days").isEqualTo("D3");
        assertThat(first.get("attr2").asText()).as("severity default").isEqualTo("MODERATE");
        assertThat(first.get("active").asBoolean()).isTrue();
        assertThat(first.get("addedAt").asText()).isNotBlank();
        assertThat(first.get("addedByMembershipId").asText()).isEqualTo(rec.doctor().membershipId().toString());
        assertThat(first.get("addedByName").asText()).isEqualTo("Dr Test Doctor");
        assertThat(itemTexts(valueOf(saved.record(), "complaints", "complaint"))).containsExactly("Fever", "Cough with phlegm");
        assertThat(recordSection(saved.record(), "complaints").get("status").asText())
                .as("the duration is covered by the list: one active complaint is enough").isEqualTo("DONE");
        // a later save that reorders keeps the new order
        ObjectNode reordered = complaintsSection((ObjectNode) item(saved.record(), 1).deepCopy(), (ObjectNode) item(saved.record(), 0).deepCopy());
        Rec again = save(saved, reordered);
        assertThat(itemTexts(valueOf(again.record(), "complaints", "complaint"))).containsExactly("Cough with phlegm", "Fever");
    }

    @Test
    void anItemFromTheMasterKeepsItsKeyAndADiagnosisFromTheMasterGetsItsCodeFromTheServer() throws Exception {
        Rec rec = newRec();
        List<String> complaint = globalRows("select id::text, name from opd.emr_complaint order by sort_order, lower(name), id limit 1").get(0);
        List<String> diagnosis = globalRows("select code, name from opd.diagnosis order by lower(name), code limit 1").get(0);
        Rec saved = save(rec, complaintsSection(keyedItem(complaint.get(0), complaint.get(1))),
                oneField("diagnosis", "provisional_diagnosis", listValue("DIAGNOSIS", keyedItem(diagnosis.get(0), diagnosis.get(1)),
                        typedItem("viral fever"))));
        assertThat(item(saved.record(), 0).get("itemKey").asText()).isEqualTo(complaint.get(0));
        assertThat(item(saved.record(), 0).get("newToList").asBoolean()).isFalse();
        JsonNode coded = valueOf(saved.record(), "diagnosis", "provisional_diagnosis").get("items").get(0);
        assertThat(coded.get("code").asText()).as("the ICD-10 code is the key of the master item, set by the server").isEqualTo(diagnosis.get(0));
        assertThat(coded.get("attr1").asText()).isEqualTo("PROVISIONAL");
        assertThat(coded.get("attr2").asText()).isEqualTo("ACTIVE");
        JsonNode typed = valueOf(saved.record(), "diagnosis", "provisional_diagnosis").get("items").get(1);
        assertThat(typed.get("code").isNull()).as("a typed diagnosis has no code").isTrue();
        assertThat(typed.get("text").asText()).isEqualTo("Viral fever");
        // a code typed by the client on a typed item is not accepted either
        ObjectNode forged = typedItem("Mystery");
        forged.put("code", "A00");
        Rec after = save(saved, oneField("diagnosis", "provisional_diagnosis", listValue("DIAGNOSIS", forged)));
        assertThat(valueOf(after.record(), "diagnosis", "provisional_diagnosis").get("items").get(0).get("code").isNull()).isTrue();
    }

    @Test
    void theSameTextTwiceIgnoringCaseIs400DuplicateItemAndNothingIsStored() throws Exception {
        Rec rec = newRec();
        JsonNode problem = refuseSave(rec, 400, "duplicate-item", complaintsSection(typedItem("Fever"), typedItem("fever")));
        assertThat(problem.toString()).doesNotContain("Fever").doesNotContain("fever");
        assertThat(current(rec).get("revision").asLong()).as("nothing was stored").isEqualTo(rec.revision());
        assertThat(auditCount(rec.clinic(), "EMR_RECORD_DRAFT_SAVED")).isZero();
        // the same text in two different lists is fine
        save(rec, complaintsSection(typedItem("Fever")), oneField("advice", "advice", listValue("ADVICE", typedItem("Fever"))));
    }

    @Test
    void anAttributeOutsideItsListACodeOfTheWrongKindOrAKeyThatDoesNotMatchTheNewToListFlagIs400() throws Exception {
        Rec rec = newRec();
        ObjectNode badAttr = typedItem("Fever");
        badAttr.put("attr1", "D4");
        assertFieldError(refuseSave(rec, 400, "validation", complaintsSection(badAttr)), "sections[0].values.complaint");
        ObjectNode badSeverity = typedItem("Fever");
        badSeverity.put("attr2", "EXTREME");
        refuseSave(rec, 400, "validation", complaintsSection(badSeverity));
        ObjectNode keyedButTyped = typedItem("Fever");
        keyedButTyped.put("itemKey", newId());
        refuseSave(rec, 400, "validation", complaintsSection(keyedButTyped));
        ObjectNode listedButFlaggedNew = keyedItem(newId(), "Fever");
        listedButFlaggedNew.put("newToList", true);
        refuseSave(rec, 400, "validation", complaintsSection(listedButFlaggedNew));
        ObjectNode longText = typedItem("t".repeat(201));
        refuseSave(rec, 400, "validation", complaintsSection(longText));
        ObjectNode blank = typedItem("   ");
        refuseSave(rec, 400, "validation", complaintsSection(blank));
        ObjectNode extra = typedItem("Fever");
        extra.put("zxqnotallowed", 1);
        JsonNode problem = refuseSave(rec, 400, "validation", complaintsSection(extra));
        assertThat(problem.toString()).doesNotContain("zxqnotallowed");
        ObjectNode markup = typedItem("<script>alert(1)</script>");
        refuseSave(rec, 400, "validation", complaintsSection(markup));
        ObjectNode sameId = typedItem("Cough");
        ObjectNode twin = typedItem("Pain");
        twin.put("id", sameId.get("id").asText());
        refuseSave(rec, 400, "validation", complaintsSection(sameId, twin));
        // a list of the wrong kind for its field
        refuseSave(rec, 400, "validation", oneField("complaints", "complaint", listValue("DIAGNOSIS", typedItem("Fever"))));
        assertThat(current(rec).get("revision").asLong()).isEqualTo(rec.revision());
    }

    @Test
    void aListOfFiftyItemsIsAcceptedAndOfFiftyOneIs400() throws Exception {
        Rec rec = newRec();
        ObjectNode[] fifty = new ObjectNode[50];
        for (int i = 0; i < 50; i++) {
            fifty[i] = typedItem("Complaint number " + i);
        }
        Rec saved = save(rec, complaintsSection(fifty));
        assertThat(valueOf(saved.record(), "complaints", "complaint").get("items")).hasSize(50);
        ObjectNode[] more = new ObjectNode[51];
        for (int i = 0; i < 51; i++) {
            more[i] = typedItem("Other complaint " + i);
        }
        refuseSave(saved, 400, "validation", complaintsSection(more));
    }

    @Test
    void whatTheClientSendsForTheServerStampedPropertiesIsIgnoredAndTheFirstStampIsKeptOnLaterSaves() throws Exception {
        Rec rec = newRec();
        ObjectNode forged = typedItem("Fever");
        forged.put("addedAt", "2001-01-01T00:00:00Z");
        forged.put("addedByMembershipId", newId());
        Rec saved = save(rec, complaintsSection(forged));
        JsonNode stamped = item(saved.record(), 0);
        assertThat(stamped.get("addedAt").asText()).doesNotStartWith("2001");
        assertThat(stamped.get("addedByMembershipId").asText()).isEqualTo(rec.doctor().membershipId().toString());
        String firstStamp = stamped.get("addedAt").asText();
        // the same id again, with another stamp sent and another attribute: the stamp stays, the attribute changes
        ObjectNode again = (ObjectNode) stamped.deepCopy();
        again.put("addedAt", "2002-02-02T00:00:00Z");
        again.put("attr1", "W1");
        Rec second = save(saved, complaintsSection(again));
        assertThat(item(second.record(), 0).get("addedAt").asText()).isEqualTo(firstStamp);
        assertThat(item(second.record(), 0).get("attr1").asText()).isEqualTo("W1");
    }

    @Test
    void anInactiveItemStaysOnTheListAndIsLeftOutOfDone() throws Exception {
        Rec rec = newRec();
        ObjectNode off = typedItem("Fever");
        off.put("active", false);
        Rec saved = save(rec, complaintsSection(off));
        assertThat(item(saved.record(), 0).get("active").asBoolean()).isFalse();
        assertThat(recordSection(saved.record(), "complaints").get("status").asText()).isEqualTo("REQUIRED");
        assertThat(texts(saved.record().get("stillNeeded"), null)).contains("complaints");
        ObjectNode on = (ObjectNode) item(saved.record(), 0).deepCopy();
        on.put("active", true);
        Rec turnedOn = save(saved, complaintsSection(on));
        assertThat(recordSection(turnedOn.record(), "complaints").get("status").asText()).isEqualTo("DONE");
        // an empty list clears the field and the section is not Done
        Rec emptied = save(turnedOn, complaintsSection());
        assertThat(recordSection(emptied.record(), "complaints").get("status").asText()).isEqualTo("REQUIRED");
    }

    @Test
    void aCoveredSiblingFieldAndTheOldArrayShapeOfAComponentFieldAreRefusedAndNothingIsStored() throws Exception {
        Rec rec = newRec();
        String[][] bad = {
                {"complaints", "{\"duration\":{\"value\":3,\"unit\":\"days\"}}", "duration"},
                {"complaints", "{\"severity\":\"mild\"}", "severity"},
                {"diagnosis", "{\"final_diagnosis\":[]}", "final_diagnosis"},
                {"diagnosis", "{\"type\":\"new\"}", "type"},
                {"investigations", "{\"radiology\":[]}", "radiology"},
                {"rx", "{\"dose_frequency\":\"1-0-1\"}", "dose_frequency"},
                {"rx", "{\"duration\":{\"value\":3,\"unit\":\"days\"}}", "duration"},
                {"rx", "{\"timing\":\"after_food\"}", "timing"},
                {"rx", "{\"instructions\":\"With water\"}", "instructions"},
                {"complaints", "{\"complaint\":[{\"itemKey\":null,\"text\":\"Fever\",\"newToList\":true}]}", "complaint"},
                {"rx", "{\"drug\":[{\"itemKey\":null,\"text\":\"Paracetamol\",\"newToList\":true}]}", "drug"}};
        for (String[] b : bad) {
            JsonNode problem = refuseSave(rec, 400, "validation", sectionValues(b[0], b[1]));
            assertFieldError(problem, "sections[0].values." + b[2]);
            assertThat(problem.toString()).doesNotContain("Fever").doesNotContain("Paracetamol").doesNotContain("With water");
        }
        assertThat(current(rec).get("revision").asLong()).isEqualTo(rec.revision());
    }

    @Test
    void anAdviceItemSentOnWhatsappAnswers400NeedsWhatsappServiceAndTheLanguageDefaultsToThePrintLanguageOfTheVisit() throws Exception {
        Rec rec = newRec();
        ObjectNode whatsapp = typedItem("Drink water");
        whatsapp.put("attr2", "WHATSAPP");
        JsonNode problem = refuseSave(rec, 400, "needs-whatsapp-service", oneField("advice", "advice", listValue("ADVICE", whatsapp)));
        assertThat(problem.toString()).doesNotContain("Drink water");
        ObjectNode both = typedItem("Drink water");
        both.put("attr2", "PRINT_WHATSAPP");
        refuseSave(rec, 400, "needs-whatsapp-service", oneField("advice", "advice", listValue("ADVICE", both)));
        // PRINT is the only way; the language of a new item is the visit's print language, else English
        Rec plain = save(rec, oneField("advice", "advice", listValue("ADVICE", typedItem("Rest well"))));
        JsonNode rest = valueOf(plain.record(), "advice", "advice").get("items").get(0);
        assertThat(rest.get("attr1").asText()).isEqualTo("EN");
        assertThat(rest.get("attr2").asText()).isEqualTo("PRINT");
        put(RECORDS + "/" + rec.id() + "/print-preferences", rec.token(), "{\"language\":\"MR\",\"paperSize\":null}", 200);
        Rec marathi = save(plain, oneField("advice", "advice", listValue("ADVICE", typedItem("Walk daily"))));
        assertThat(valueOf(marathi.record(), "advice", "advice").get("items").get(0).get("attr1").asText())
                .as("an item added after the language was chosen starts in that language").isEqualTo("MR");
        ObjectNode explicit = typedItem("Eat fruit");
        explicit.put("attr1", "HI");
        Rec hindi = save(marathi, oneField("advice", "advice", listValue("ADVICE", explicit)));
        assertThat(valueOf(hindi.record(), "advice", "advice").get("items").get(0).get("attr1").asText())
                .as("the item's own language wins").isEqualTo("HI");
    }

    // ================= SAH-48 · prescription rows =================

    @Test
    void aRowFromTheMasterTakesItsNameAndFormFromTheMasterAndATypedRowIsTypedAndNeverRanked() throws Exception {
        Rec rec = newRec();
        String paracetamol = medicineId(PLAIN_MEDICINE);
        ObjectNode master = rxRow(paracetamol, "A name the client made up", "1-0-1", 3, "AFTER_FOOD");
        ObjectNode typed = rxRow(null, "zinc syrup", "0-0-1", 7, "BEDTIME");
        typed.put("source", "SEARCH");
        Rec saved = save(rec, rxSection(master, typed));
        JsonNode rows = valueOf(saved.record(), "rx", "drug").get("rows");
        assertThat(rows.get(0).get("name").asText()).isEqualTo(PLAIN_MEDICINE);
        assertThat(rows.get(0).get("form").asText()).isEqualTo("Tablet");
        assertThat(rows.get(0).get("medicineId").asText()).isEqualTo(paracetamol);
        assertThat(rows.get(0).get("source").asText()).isEqualTo("SEARCH");
        assertThat(rows.get(1).get("medicineId").isNull()).isTrue();
        assertThat(rows.get(1).get("source").asText()).as("a typed medicine is TYPED whatever was sent").isEqualTo("TYPED");
        assertThat(rows.get(1).get("name").asText()).isEqualTo("zinc syrup");
        assertThat(rows.get(1).get("frequency").asText()).isEqualTo("0-0-1");
        assertThat(rows.get(1).get("days").asInt()).isEqualTo(7);
        assertThat(rows.get(1).get("when").asText()).isEqualTo("BEDTIME");
        assertThat(rows.get(0).get("addedByMembershipId").asText()).isEqualTo(rec.doctor().membershipId().toString());
        assertThat(recordSection(saved.record(), "rx").get("status").asText()).isEqualTo("DONE");
        assertThat(usageRows(rec.clinic())).as("saving a draft never counts a use (that is SAH-51)").isZero();
    }

    @Test
    void theSameMedicineNameTwiceIgnoringCaseIs400DuplicateItemAndAnythingOutsideTheListsIs400Validation() throws Exception {
        Rec rec = newRec();
        refuseSave(rec, 400, "duplicate-item", rxSection(typedRx("Zinc syrup"), typedRx("ZINC SYRUP")));
        String paracetamol = medicineId(PLAIN_MEDICINE);
        refuseSave(rec, 400, "duplicate-item", rxSection(rxRow(paracetamol, PLAIN_MEDICINE, "1-0-1", 3, "AFTER_FOOD"), typedRx(PLAIN_MEDICINE.toLowerCase())));
        ObjectNode[] bad = {
                rxRow(null, "Zinc syrup", "2-2-2", 3, "AFTER_FOOD"), rxRow(null, "Zinc syrup", "1-0-1", 0, "AFTER_FOOD"),
                rxRow(null, "Zinc syrup", "1-0-1", 366, "AFTER_FOOD"), rxRow(null, "Zinc syrup", "1-0-1", 3, "WITH_MILK"),
                rxRow(null, "", "1-0-1", 3, "AFTER_FOOD"), rxRow(null, "n".repeat(161), "1-0-1", 3, "AFTER_FOOD"),
                rxRow(newId(), "Unknown", "1-0-1", 3, "AFTER_FOOD")};
        for (ObjectNode row : bad) {
            assertFieldError(refuseSave(rec, 400, "validation", rxSection(row)), "sections[0].values.drug");
        }
        ObjectNode fractional = typedRx("Zinc syrup");
        fractional.put("days", 1.5);
        refuseSave(rec, 400, "validation", rxSection(fractional));
        ObjectNode textDays = typedRx("Zinc syrup");
        textDays.put("days", "3");
        refuseSave(rec, 400, "validation", rxSection(textDays));
        ObjectNode extra = typedRx("Zinc syrup");
        extra.put("zxqnotallowed", true);
        assertThat(refuseSave(rec, 400, "validation", rxSection(extra)).toString()).doesNotContain("zxqnotallowed");
        // 31 rows are too many, 30 are accepted
        ObjectNode[] thirtyOne = new ObjectNode[31];
        for (int i = 0; i < 31; i++) {
            thirtyOne[i] = typedRx("Medicine number " + i);
        }
        refuseSave(rec, 400, "validation", rxSection(thirtyOne));
        ObjectNode[] thirty = new ObjectNode[30];
        System.arraycopy(thirtyOne, 0, thirty, 0, 30);
        Rec saved30 = save(rec, rxSection(thirty));
        assertThat(valueOf(saved30.record(), "rx", "drug").get("rows")).hasSize(30);
        // the edge values are accepted: 1 and 365 days
        Rec edge = save(saved30, rxSection(rxRow(null, "Syrup one", "1-1-1", 1, "MORNING_EMPTY_STOMACH"), rxRow(null, "Syrup two", "1-0-0", 365, "BEFORE_FOOD")));
        assertThat(valueOf(edge.record(), "rx", "drug").get("rows").get(1).get("days").asInt()).isEqualTo(365);
    }

    @Test
    void aMedicineRowKeepsItsFirstStampOnLaterSaves() throws Exception {
        Rec rec = newRec();
        Rec saved = save(rec, rxSection(typedRx("Zinc syrup")));
        JsonNode row = valueOf(saved.record(), "rx", "drug").get("rows").get(0);
        ObjectNode changed = (ObjectNode) row.deepCopy();
        changed.put("days", 10);
        changed.put("addedAt", "2001-01-01T00:00:00Z");
        Rec again = save(saved, rxSection(changed));
        JsonNode after = valueOf(again.record(), "rx", "drug").get("rows").get(0);
        assertThat(after.get("addedAt").asText()).isEqualTo(row.get("addedAt").asText());
        assertThat(after.get("days").asInt()).isEqualTo(10);
    }

    // ================= SAH-48-2 · drug-class allergy check =================

    @Test
    void aSulfaAllergyAndASulfaDrugGiveAnAlertThatBlocksCompletingAndPrintingUntilItIsReviewed() throws Exception {
        Rec rec = newRec();
        String sulfa = medicineId(SULFA_MEDICINE);
        ObjectNode row = rxRow(sulfa, SULFA_MEDICINE, "1-0-1", 5, "AFTER_FOOD");
        Rec saved = save(rec, allergySection("SULFA"), rxSection(row));
        JsonNode alerts = saved.record().get("allergyAlerts");
        assertThat(alerts).hasSize(1);
        JsonNode alert = alerts.get(0);
        assertThat(alert.get("sectionKey").asText()).isEqualTo("rx");
        assertThat(alert.get("fieldKey").asText()).isEqualTo("drug");
        assertThat(alert.get("rowId").asText()).isEqualTo(row.get("id").asText());
        assertThat(alert.get("medicineKey").asText()).isEqualTo(sulfa);
        assertThat(alert.get("medicineName").asText()).isEqualTo(SULFA_MEDICINE);
        assertThat(alert.get("allergyClass").asText()).isEqualTo("SULFA");
        assertThat(alert.get("allergyClassLabel").asText()).isEqualTo("Sulfa drugs");
        assertThat(alert.get("reviewed").asBoolean()).isFalse();
        assertThat(alert.get("reviewedAt").isNull()).isTrue();
        assertThat(saved.record().get("completeBlockers").get(0).asText()).isEqualTo("ALLERGY_ALERT");
        assertThat(saved.record().get("readyToComplete").asBoolean()).isFalse();
        // even with every required section Done the footer is not "Ready to complete" while an alert is open
        // Vitals is a required General Medicine section since V2026_12_14_1010 (SAH-75), filled with the vitals log editor
        ObjectNode vitalsLog = (ObjectNode) objectMapper.readTree("{\"component\":\"VITALS_LOG\",\"schemaVersion\":1,\"entries\":[{\"id\":\"5b1f1f0e-0a00-4c00-8000-00000000000b\","
                + "\"bpSystolic\":120,\"bpDiastolic\":80,\"pulse\":72,\"spo2\":98}]}");
        Rec ready = save(saved, oneField("vitals", "specialty", vitalsLog), complaintsSection(typedItem("Fever")),
                oneField("diagnosis", "provisional_diagnosis", listValue("DIAGNOSIS", typedItem("Viral fever"))),
                sectionValues("rxformat", "{\"header\":{\"text\":\"Header\"}}"));
        assertThat(texts(ready.record().get("stillNeeded"), null)).isEmpty();
        assertThat(ready.record().get("readyToComplete").asBoolean()).isFalse();
        // the case paper says Print is blocked
        assertThat(get(RECORDS + "/" + rec.id() + "/case-paper", rec.token()).get("printBlocked").asText()).isEqualTo("ALLERGY_ALERT");
        // reviewing the alert releases both
        ObjectNode reviewed = (ObjectNode) valueOf(ready.record(), "rx", "drug").get("rows").get(0).deepCopy();
        reviewed.putObject("allergyReview").put("allergyClass", "SULFA").put("confirmed", true);
        Rec after = save(ready, rxSection(reviewed));
        JsonNode done = after.record().get("allergyAlerts").get(0);
        assertThat(done.get("reviewed").asBoolean()).isTrue();
        assertThat(done.get("reviewedAt").asText()).isNotBlank();
        assertThat(done.get("reviewedByName").asText()).isEqualTo("Dr Test Doctor");
        assertThat(after.record().get("completeBlockers")).isEmpty();
        assertThat(after.record().get("readyToComplete").asBoolean()).isTrue();
        assertThat(get(RECORDS + "/" + rec.id() + "/case-paper", rec.token()).get("printBlocked").isNull()).isTrue();
    }

    @Test
    void reviewingAnAlertWritesOneAuditEntryWithIdsAndTheClassOnlyAndSavingAgainWritesNoSecondOne() throws Exception {
        Rec rec = newRec();
        String sulfa = medicineId(SULFA_MEDICINE);
        ObjectNode row = rxRow(sulfa, SULFA_MEDICINE, "1-0-1", 5, "AFTER_FOOD");
        row.putObject("allergyReview").put("allergyClass", "SULFA").put("confirmed", true);
        // the class is added first: a review sent in the same save as the first allergy class is cleared (DEC-051 D1)
        Rec withAllergy = save(rec, allergySection("SULFA"));
        Rec saved = save(withAllergy, rxSection(row));
        assertThat(saved.record().get("allergyAlerts").get(0).get("reviewed").asBoolean()).isTrue();
        List<List<String>> audits = auditRows(rec.clinic(), "EMR_ALLERGY_ALERT_REVIEWED");
        assertThat(audits).hasSize(1);
        JsonNode after = objectMapper.readTree(audits.get(0).get(2));
        assertThat(after.get("rowId").asText()).isEqualTo(row.get("id").asText());
        assertThat(after.get("medicineKey").asText()).isEqualTo(sulfa);
        assertThat(after.get("allergyClass").asText()).isEqualTo("SULFA");
        assertThat(audits.get(0).get(3)).isEqualTo(rec.doctor().userId().toString());
        assertThat(audits.get(0).get(1) + audits.get(0).get(2)).doesNotContain("Co-trimoxazole").doesNotContain("Ramesh");
        // the same review sent again keeps its stamp and writes nothing
        JsonNode stored = valueOf(saved.record(), "rx", "drug").get("rows").get(0);
        Rec again = save(saved, rxSection((ObjectNode) stored.deepCopy()));
        assertThat(auditRows(rec.clinic(), "EMR_ALLERGY_ALERT_REVIEWED")).hasSize(1);
        assertThat(valueOf(again.record(), "rx", "drug").get("rows").get(0).get("allergyReview").get("reviewedAt").asText())
                .isEqualTo(stored.get("allergyReview").get("reviewedAt").asText());
    }

    @Test
    void aReviewForAnotherClassOrWithConfirmedFalseDoesNotClearTheAlert() throws Exception {
        Rec rec = newRec();
        String sulfa = medicineId(SULFA_MEDICINE);
        ObjectNode wrongClass = rxRow(sulfa, SULFA_MEDICINE, "1-0-1", 5, "AFTER_FOOD");
        wrongClass.putObject("allergyReview").put("allergyClass", "PENICILLIN").put("confirmed", true);
        Rec saved = save(rec, allergySection("SULFA"), rxSection(wrongClass));
        assertThat(saved.record().get("allergyAlerts").get(0).get("reviewed").asBoolean()).isFalse();
        ObjectNode notConfirmed = rxRow(sulfa, SULFA_MEDICINE, "1-0-1", 5, "AFTER_FOOD");
        notConfirmed.putObject("allergyReview").put("allergyClass", "SULFA").put("confirmed", false);
        Rec second = save(saved, rxSection(notConfirmed));
        assertThat(second.record().get("allergyAlerts").get(0).get("reviewed").asBoolean()).isFalse();
        assertThat(auditCount(rec.clinic(), "EMR_ALLERGY_ALERT_REVIEWED")).isEqualTo(0);
    }

    @Test
    void aChangeOfTheVisitsAllergyClassesClearsEveryReviewAndARemovedAndReAddedRowIsAlertedAgain() throws Exception {
        Rec rec = newRec();
        String sulfa = medicineId(SULFA_MEDICINE);
        ObjectNode row = rxRow(sulfa, SULFA_MEDICINE, "1-0-1", 5, "AFTER_FOOD");
        row.putObject("allergyReview").put("allergyClass", "SULFA").put("confirmed", true);
        Rec withAllergy = save(rec, allergySection("SULFA")); // a review in the same save as the first class would be cleared (DEC-051 D1)
        Rec reviewed = save(withAllergy, rxSection(row));
        assertThat(reviewed.record().get("allergyAlerts").get(0).get("reviewed").asBoolean()).isTrue();
        // another class is added to the allergies: the review is cleared and the alert comes back
        Rec changed = save(reviewed, allergySection("SULFA", "PENICILLIN"));
        assertThat(changed.record().get("allergyAlerts").get(0).get("reviewed").asBoolean()).isFalse();
        assertThat(valueOf(changed.record(), "rx", "drug").get("rows").get(0).get("allergyReview").isNull()).isTrue();
        assertThat(changed.record().get("readyToComplete").asBoolean()).isFalse();
        // review again, then remove the row and add the drug again with a new id: the alert is back
        ObjectNode again = (ObjectNode) valueOf(changed.record(), "rx", "drug").get("rows").get(0).deepCopy();
        again.putObject("allergyReview").put("allergyClass", "SULFA").put("confirmed", true);
        Rec second = save(changed, rxSection(again));
        assertThat(second.record().get("allergyAlerts").get(0).get("reviewed").asBoolean()).isTrue();
        Rec removed = save(second, rxSection());
        assertThat(removed.record().get("allergyAlerts")).isEmpty();
        Rec readded = save(removed, rxSection(rxRow(sulfa, SULFA_MEDICINE, "1-0-1", 5, "AFTER_FOOD")));
        assertThat(readded.record().get("allergyAlerts").get(0).get("reviewed").asBoolean()).isFalse();
        // removing the allergy class takes the alert away
        Rec none = save(readded, allergySection());
        assertThat(none.record().get("allergyAlerts")).isEmpty();
        assertThat(none.record().get("completeBlockers")).isEmpty();
    }

    @Test
    void aPenicillinAllergyAlertsOnAmoxicillinOnlyAndTypedAllergiesOrTypedMedicinesNeverTriggerTheCheck() throws Exception {
        Rec rec = newRec();
        String amoxicillin = medicineId(PENICILLIN_MEDICINE);
        String sulfa = medicineId(SULFA_MEDICINE);
        // a sulfa allergy does not alert on a penicillin drug
        Rec sulfaOnly = save(rec, allergySection("SULFA"), rxSection(rxRow(amoxicillin, PENICILLIN_MEDICINE, "1-1-1", 5, "AFTER_FOOD")));
        assertThat(sulfaOnly.record().get("allergyAlerts")).isEmpty();
        // a penicillin allergy alerts on it
        Rec penicillin = save(sulfaOnly, allergySection("PENICILLIN"));
        assertThat(penicillin.record().get("allergyAlerts")).hasSize(1);
        assertThat(penicillin.record().get("allergyAlerts").get(0).get("allergyClass").asText()).isEqualTo("PENICILLIN");
        assertThat(penicillin.record().get("allergyAlerts").get(0).get("allergyClassLabel").asText()).isEqualTo("Penicillin");
        // a typed allergy text, even one that says sulfa, never triggers the check
        Rec typedAllergy = save(penicillin, allergySection("typed:Sulfa drugs"),
                rxSection(rxRow(sulfa, SULFA_MEDICINE, "1-0-1", 5, "AFTER_FOOD")));
        assertThat(typedAllergy.record().get("allergyAlerts")).isEmpty();
        // a typed medicine is never checked (ADR-022 H6)
        Rec typedMedicine = save(typedAllergy, allergySection("SULFA"), rxSection(typedRx("Co-trimoxazole forte syrup")));
        assertThat(typedMedicine.record().get("allergyAlerts")).isEmpty();
        // a medicine with no class never alerts
        Rec plain = save(typedMedicine, rxSection(rxRow(medicineId(PLAIN_MEDICINE), PLAIN_MEDICINE, "1-0-1", 3, "AFTER_FOOD")));
        assertThat(plain.record().get("allergyAlerts")).isEmpty();
    }

    // ================= SAH-57 · body map =================

    @Test
    void aBodyMapPinIsStampedByTheServerAndTheListOfValuesIsKept() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        Rec rec = lesionRecord(clinic, doctor);
        assertThat(layoutKeys(rec.record())).contains("lesion_map");
        ObjectNode forged = pin(1, "FALL", false);
        forged.put("recordedAt", "2001-01-01T00:00:00Z");
        forged.put("recordedByMembershipId", newId());
        Rec saved = save(rec, lesionDone(bodyMap(false, forged)));
        JsonNode stored = bodyMapOf(saved).get("pins").get(0);
        assertThat(stored.get("no").asInt()).isEqualTo(1);
        assertThat(stored.get("region").asText()).isEqualTo("chest");
        assertThat(stored.get("sizeCm").asDouble()).isEqualTo(2.5);
        assertThat(stored.get("recordedAt").asText()).doesNotStartWith("2001");
        assertThat(stored.get("recordedByMembershipId").asText()).isEqualTo(doctor.membershipId().toString());
        assertThat(stored.get("recordedByName").asText()).isEqualTo("Dr Test Doctor");
        assertThat(bodyMapOf(saved).get("nextPinNo").asInt()).isEqualTo(2);
        assertThat(recordSection(saved.record(), "lesion_map").get("status").asText()).isEqualTo("DONE");
        // the stamp stays when the pin is changed
        ObjectNode changed = (ObjectNode) stored.deepCopy();
        changed.put("note", "Seen at 10 am");
        changed.put("recordedAt", "2002-02-02T00:00:00Z");
        Rec again = save(saved, oneField("lesion_map", "body_map", bodyMap(false, changed)));
        assertThat(bodyMapOf(again).get("pins").get(0).get("recordedAt").asText()).isEqualTo(stored.get("recordedAt").asText());
    }

    @Test
    void aNewPinWithTheCauseRoadAccidentAssaultOrBurnIsMedicoLegalWhateverWasSentAndEveryChangeIsAuditedWithoutClinicalText() throws Exception {
        Clinic clinic = newClinic();
        Rec rec = lesionRecord(clinic, addDoctor(clinic, "General physician"));
        ObjectNode accident = pin(1, "ROAD_ACCIDENT", false);
        accident.put("note", "Secret clinical story ZXQ");
        Rec saved = save(rec, oneField("lesion_map", "body_map", bodyMap(false, accident, pin(2, "ASSAULT", false),
                pin(3, "BURN", false), pin(4, "FALL", false))));
        JsonNode pins = bodyMapOf(saved).get("pins");
        assertThat(pins.get(0).get("mlc").asBoolean()).as("road accident").isTrue();
        assertThat(pins.get(1).get("mlc").asBoolean()).as("assault").isTrue();
        assertThat(pins.get(2).get("mlc").asBoolean()).as("burn").isTrue();
        assertThat(pins.get(3).get("mlc").asBoolean()).as("a fall is not medico-legal by itself").isFalse();
        assertThat(saved.record().get("header").get("mlc").asBoolean()).as("the record header tag").isTrue();
        List<List<String>> audits = auditRows(rec.clinic(), "EMR_MLC_FLAG_CHANGED");
        assertThat(audits).as("one audit entry per pin whose flag changed").hasSize(3);
        for (List<String> row : audits) {
            JsonNode after = objectMapper.readTree(row.get(2));
            assertThat(after.get("mlc").asBoolean()).isTrue();
            assertThat(after.get("pinNo").asInt()).isBetween(1, 3);
            assertThat(after.size()).as("pin number and the flag only").isEqualTo(2);
            assertThat(row.get(1) + row.get(2)).doesNotContain("ZXQ").doesNotContain("WOUND").doesNotContain("ROAD_ACCIDENT").doesNotContain("chest");
        }
    }

    @Test
    void theDoctorMaySetOrClearTheFlagByHandAndAMovedCauseKeepsTheFlagUntilItIsClearedEveryChangeIsAudited() throws Exception {
        Clinic clinic = newClinic();
        Rec rec = lesionRecord(clinic, addDoctor(clinic, "General physician"));
        // by hand: a fall marked MLC stays MLC
        Rec first = save(rec, oneField("lesion_map", "body_map", bodyMap(false, pin(1, "FALL", true))));
        assertThat(bodyMapOf(first).get("pins").get(0).get("mlc").asBoolean()).isTrue();
        assertThat(auditCount(rec.clinic(), "EMR_MLC_FLAG_CHANGED")).isEqualTo(1);
        // saving the same pin again changes nothing and writes nothing
        Rec same = save(first, oneField("lesion_map", "body_map", bodyMap(false, (ObjectNode) bodyMapOf(first).get("pins").get(0).deepCopy())));
        assertThat(auditCount(rec.clinic(), "EMR_MLC_FLAG_CHANGED")).isEqualTo(1);
        // a pin whose cause changes to an MLC cause becomes MLC; one whose cause moves away keeps the flag
        Rec second = save(same, oneField("lesion_map", "body_map", bodyMap(false, pin(1, "FALL", true), pin(2, "SPORTS", false))));
        ObjectNode toAssault = (ObjectNode) bodyMapOf(second).get("pins").get(1).deepCopy();
        toAssault.put("cause", "ASSAULT");
        toAssault.put("mlc", false);
        ObjectNode awayFromAccident = (ObjectNode) bodyMapOf(second).get("pins").get(0).deepCopy();
        awayFromAccident.put("cause", "ANIMAL_BITE");
        Rec third = save(second, oneField("lesion_map", "body_map", bodyMap(false, awayFromAccident, toAssault)));
        assertThat(bodyMapOf(third).get("pins").get(0).get("mlc").asBoolean()).as("flag kept").isTrue();
        assertThat(bodyMapOf(third).get("pins").get(1).get("mlc").asBoolean()).as("cause changed to assault").isTrue();
        assertThat(auditCount(rec.clinic(), "EMR_MLC_FLAG_CHANGED")).isEqualTo(2);
        // the doctor clears one by hand
        ObjectNode cleared = (ObjectNode) bodyMapOf(third).get("pins").get(0).deepCopy();
        cleared.put("mlc", false);
        Rec fourth = save(third, oneField("lesion_map", "body_map", bodyMap(false, cleared, (ObjectNode) bodyMapOf(third).get("pins").get(1).deepCopy())));
        assertThat(bodyMapOf(fourth).get("pins").get(0).get("mlc").asBoolean()).isFalse();
        assertThat(auditCount(rec.clinic(), "EMR_MLC_FLAG_CHANGED")).isEqualTo(3);
        List<List<String>> audits = auditRows(rec.clinic(), "EMR_MLC_FLAG_CHANGED");
        assertThat(objectMapper.readTree(audits.get(2).get(2)).get("mlc").asBoolean()).isFalse();
        // removing the remaining MLC pin is audited as a change to off, and the header tag goes
        Rec fifth = save(fourth, oneField("lesion_map", "body_map", bodyMap(false, (ObjectNode) bodyMapOf(fourth).get("pins").get(0).deepCopy())));
        assertThat(bodyMapOf(fifth).get("pins")).hasSize(1);
        assertThat(auditCount(rec.clinic(), "EMR_MLC_FLAG_CHANGED")).isEqualTo(4);
        assertThat(fifth.record().get("header").get("mlc").asBoolean()).isFalse();
    }

    @Test
    void aPinNumberIsNeverReusedAfterARemovalAndANewPinMustCarryAtLeastTheNextNumber() throws Exception {
        Clinic clinic = newClinic();
        Rec rec = lesionRecord(clinic, addDoctor(clinic, "General physician"));
        Rec two = save(rec, oneField("lesion_map", "body_map", bodyMap(false, pin(1, "FALL", false), pin(2, "FALL", false))));
        assertThat(bodyMapOf(two).get("nextPinNo").asInt()).isEqualTo(3);
        // remove pin 2: the next number stays 3
        Rec one = save(two, oneField("lesion_map", "body_map", bodyMap(false, (ObjectNode) bodyMapOf(two).get("pins").get(0).deepCopy())));
        assertThat(bodyMapOf(one).get("pins")).hasSize(1);
        assertThat(bodyMapOf(one).get("nextPinNo").asInt()).isEqualTo(3);
        // a new pin that carries the number 2 again is refused, and so is a number below it
        JsonNode problem = refuseSave(one, 400, "validation", oneField("lesion_map", "body_map",
                bodyMap(false, (ObjectNode) bodyMapOf(one).get("pins").get(0).deepCopy(), pin(2, "FALL", false))));
        assertFieldError(problem, "sections[0].values.body_map");
        refuseSave(one, 400, "validation", oneField("lesion_map", "body_map",
                bodyMap(false, (ObjectNode) bodyMapOf(one).get("pins").get(0).deepCopy(), pin(2, "SPORTS", false))));
        // the number 3 is the next one; a higher number is allowed and moves the counter past it
        Rec three = save(one, oneField("lesion_map", "body_map", bodyMap(false,
                (ObjectNode) bodyMapOf(one).get("pins").get(0).deepCopy(), pin(5, "FALL", false))));
        assertThat(bodyMapOf(three).get("nextPinNo").asInt()).isEqualTo(6);
        // the client cannot move the counter back by sending one
        ObjectNode back = bodyMap(false, (ObjectNode) bodyMapOf(three).get("pins").get(0).deepCopy(),
                (ObjectNode) bodyMapOf(three).get("pins").get(1).deepCopy());
        back.put("nextPinNo", 1);
        Rec ignored = save(three, oneField("lesion_map", "body_map", back));
        assertThat(bodyMapOf(ignored).get("nextPinNo").asInt()).isEqualTo(6);
        // a number twice in one save is refused
        refuseSave(ignored, 400, "validation", oneField("lesion_map", "body_map", bodyMap(false, pin(7, "FALL", false), pin(7, "FALL", false))));
        // numbers 0 and 1000 are outside 1 to 999
        refuseSave(ignored, 400, "validation", oneField("lesion_map", "body_map", bodyMap(false, pin(0, "FALL", false))));
        refuseSave(ignored, 400, "validation", oneField("lesion_map", "body_map", bodyMap(false, pin(1000, "FALL", false))));
    }

    @Test
    void thePinValuesAreCheckedAgainstTheFixedListsAndAtMostSixtyPinsAreAccepted() throws Exception {
        Clinic clinic = newClinic();
        Rec rec = lesionRecord(clinic, addDoctor(clinic, "General physician"));
        ObjectNode region = pin(1, "FALL", false);
        region.put("region", "elbow");
        ObjectNode finding = pin(1, "FALL", false);
        finding.put("finding", "TUMOUR");
        ObjectNode severity = pin(1, "FALL", false);
        severity.put("severity", "CRITICAL");
        ObjectNode side = pin(1, "FALL", false);
        side.put("side", "LEFT");
        ObjectNode size = pin(1, "FALL", false);
        size.put("sizeCm", 0.7);
        ObjectNode tooBig = pin(1, "FALL", false);
        tooBig.put("sizeCm", 50.5);
        ObjectNode negative = pin(1, "FALL", false);
        negative.put("sizeCm", -1);
        ObjectNode outside = pin(1, "FALL", false);
        outside.put("x", 1.5);
        ObjectNode noMlc = pin(1, "FALL", false);
        noMlc.remove("mlc");
        ObjectNode longNote = pin(1, "FALL", false);
        longNote.put("note", "n".repeat(501));
        ObjectNode longWhere = pin(1, "FALL", false);
        longWhere.put("where", "w".repeat(81));
        ObjectNode markup = pin(1, "FALL", false);
        markup.put("note", "<b>bold</b>");
        ObjectNode extra = pin(1, "FALL", false);
        extra.put("zxqnotallowed", 1);
        for (ObjectNode bad : new ObjectNode[] {region, finding, severity, side, size, tooBig, negative, outside, noMlc,
                longNote, longWhere, markup, extra}) {
            JsonNode problem = refuseSave(rec, 400, "validation", oneField("lesion_map", "body_map", bodyMap(false, bad)));
            assertThat(problem.toString()).doesNotContain("TUMOUR").doesNotContain("zxqnotallowed").doesNotContain("elbow");
        }
        // the edge values are accepted
        ObjectNode edgeLow = pin(1, "FALL", false);
        edgeLow.put("sizeCm", 0);
        edgeLow.put("x", 0);
        ObjectNode edgeHigh = pin(2, "FALL", false);
        edgeHigh.put("sizeCm", 50);
        edgeHigh.put("x", 1);
        edgeHigh.put("note", "n".repeat(500));
        edgeHigh.put("where", "w".repeat(80));
        Rec ok = save(rec, oneField("lesion_map", "body_map", bodyMap(false, edgeLow, edgeHigh)));
        assertThat(bodyMapOf(ok).get("pins")).hasSize(2);
        // 60 pins are accepted and 61 are refused
        ObjectNode[] sixty = new ObjectNode[60];
        for (int i = 0; i < 60; i++) {
            sixty[i] = pin(i + 1, "FALL", false);
        }
        Rec full = save(ok, oneField("lesion_map", "body_map", bodyMap(false, sixty)));
        assertThat(bodyMapOf(full).get("pins")).hasSize(60);
        ObjectNode[] sixtyOne = new ObjectNode[61];
        for (int i = 0; i < 61; i++) {
            sixtyOne[i] = pin(i + 1, "FALL", false);
        }
        refuseSave(full, 400, "validation", oneField("lesion_map", "body_map", bodyMap(false, sixtyOne)));
    }

    @Test
    void aRequiredBodyMapIsDoneWithAPinOrWithNoLocalFindingsConfirmedAndNotWithAnEmptyMap() throws Exception {
        Clinic clinic = newClinic();
        Rec rec = lesionRecord(clinic, addDoctor(clinic, "General physician"));
        assertThat(recordSection(rec.record(), "lesion_map").get("status").asText()).isEqualTo("REQUIRED");
        Rec empty = save(rec, oneField("lesion_map", "body_map", bodyMap(false)));
        assertThat(recordSection(empty.record(), "lesion_map").get("status").asText()).isEqualTo("REQUIRED");
        Rec none = save(empty, lesionDone(bodyMap(true)));
        assertThat(recordSection(none.record(), "lesion_map").get("status").asText()).isEqualTo("DONE");
        assertThat(bodyMapOf(none).get("noFindingsConfirmed").asBoolean()).isTrue();
        Rec pinned = save(none, lesionDone(bodyMap(false, pin(1, "FALL", false))));
        assertThat(recordSection(pinned.record(), "lesion_map").get("status").asText()).isEqualTo("DONE");
        // the old phase 2 text object is refused as a body map value (it is only read from old records)
        refuseSave(pinned, 400, "validation", sectionValues("lesion_map", "{\"body_map\":{\"text\":\"old text\"}}"));
        // no sex, skin tone, view or zoom is stored: an unknown property is refused
        ObjectNode withView = bodyMap(false, pin(1, "FALL", false));
        withView.put("view", "BACK");
        refuseSave(pinned, 400, "validation", oneField("lesion_map", "body_map", withView));
    }

    @Test
    void aSavedBodyMapNeverAddsAPhotoOrAMessageAndTheAuditCarriesNoRegionFindingCauseOrNote() throws Exception {
        Clinic clinic = newClinic();
        Rec rec = lesionRecord(clinic, addDoctor(clinic, "General physician"));
        ObjectNode accident = pin(1, "ROAD_ACCIDENT", false);
        accident.put("note", "Secret clinical story ZXQ");
        save(rec, oneField("lesion_map", "body_map", bodyMap(false, accident)));
        long auditText = count(rec.clinic(), "select count(*) from platform.audit_event where (coalesce(before_json::text, '') "
                + "|| coalesce(after_json::text, '')) like '%ZXQ%'");
        assertThat(auditText).isZero();
        assertThat(count(rec.clinic(), "select count(*) from platform.audit_event where action like '%MLC%' and "
                + "(after_json::text like '%WOUND%' or after_json::text like '%chest%' or after_json::text like '%ROAD%')")).isZero();
        UUID record = UUID.fromString(rec.id());
        assertThat(count(rec.clinic(), "select count(*) from opd.emr_record where id = ? and sections::text like '%ZXQ%'", record))
                .as("the note is stored with the record and nowhere else").isEqualTo(1);
    }

    // ================= who may write =================

    @Test
    void everyRoleButADoctorAVisitingDoctorOrAnOwnerWithDoctorDetailsIsRefusedOnTheComponentSavesToo() throws Exception {
        Rec rec = newRec();
        for (String role : NOT_DOCTOR_ROLES) {
            Staff staff = addNonDoctor(rec.clinic(), role);
            problemOf(HttpMethod.PUT, RECORDS + "/" + rec.id() + "/draft", staff.token(rec.clinic()),
                    draftBody(rec.revision(), "ASSESSMENT", null, rxSection(typedRx("Zinc syrup"))).toString(), 403, "forbidden");
            problemOf(HttpMethod.PUT, RECORDS + "/" + rec.id() + "/draft", staff.tokenClaiming(rec.clinic(), "DOCTOR"),
                    draftBody(rec.revision(), "ASSESSMENT", null, rxSection(typedRx("Zinc syrup"))).toString(), 403, "forbidden");
        }
        assertThat(current(rec).get("revision").asLong()).isEqualTo(rec.revision());
    }
}
