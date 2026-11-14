package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sahaj.opd.application.emr.EmrLibraryRepository.StarterLayoutRow;
import com.sahaj.opd.application.emr.EmrLibraryRepository.StarterRow;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.EmrModel.LibrarySection;
import com.sahaj.opd.application.emr.EmrViews.StarterDetail;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmrLibraryServiceTest {

    private final EmrLibraryRepository library = mock(EmrLibraryRepository.class);
    private EmrLibraryService service;

    @BeforeEach
    void setUp() {
        List<LayoutSection> physio = List.of(
                EmrTestData.section("vitals", "COMMON", "ASSESSMENT", true, true, false),
                EmrTestData.section("rom", "SPECIALTY", "ASSESSMENT", true, false, false),
                EmrTestData.section("gait", "SPECIALTY", "ASSESSMENT", true, false, false),
                EmrTestData.section("consent", "COMPLIANCE", "TREATMENT", false, false, false));
        StarterRow row = new StarterRow("physio", "Physiotherapy", "PH", "#00796B",
                "Assessment-first layout for physiotherapy clinics.", true, false, 5, 2);
        when(library.starters()).thenReturn(List.of(row));
        when(library.starter("physio")).thenReturn(Optional.of(row));
        when(library.currentStarterLayout("physio")).thenReturn(Optional.of(
                new StarterLayoutRow("physio", 2, EmrTestData.JSON.writeLayout(physio))));
        service = new EmrLibraryService(library, new EmrLayouts(library, mock(EmrDepartmentRepository.class),
                EmrTestData.MAPPER));
    }

    @Test
    void theStarterCardCountsSpecialtySectionsAndNamesWhatIsOffAtStart() {
        EmrViews.StarterSummary card = service.starters().get(0);

        assertThat(card.starterKey()).isEqualTo("physio");
        assertThat(card.sectionCount()).isEqualTo(4);
        assertThat(card.specialtySectionCount()).isEqualTo(2);
        assertThat(card.offAtStart()).containsExactly("CONSENT");
        assertThat(card.description()).isEqualTo("Assessment-first layout for physiotherapy clinics.");
    }

    @Test
    void aStarterShowsEverySectionHiddenOnesTooWithItsLayoutVersion() {
        StarterDetail detail = service.starter("physio");

        assertThat(detail.layoutVersion()).isEqualTo(2);
        assertThat(detail.sections()).extracting(LayoutSection::key).containsExactly("vitals", "rom", "gait", "consent");
        assertThat(detail.sections().get(3).shown()).isFalse();
    }

    @Test
    void theRetiredTemplateKeysAreNotStartersAndAMalformedKeyIsBadRequest() {
        assertThatThrownBy(() -> service.starter("gp")).isInstanceOfSatisfying(EmrProblemException.class, ex -> {
            assertThat(ex.status()).isEqualTo(404);
            assertThat(ex.slug()).isEqualTo("starter-not-found");
        });
        assertThatThrownBy(() -> service.starter("homeo")).isInstanceOfSatisfying(EmrProblemException.class,
                ex -> assertThat(ex.slug()).isEqualTo("starter-not-found"));
        assertThatThrownBy(() -> service.starter("Physio")).isInstanceOfSatisfying(EmrProblemException.class,
                ex -> assertThat(ex.slug()).isEqualTo("bad-request"));
    }

    @Test
    void aLibrarySectionIsFoundByKeyAndAnUnknownWellFormedKeyIs404() {
        LibrarySection vitals = new LibrarySection("vitals", "Vitals", "COMMON", "", false, List.of(), List.of("gm"));
        when(library.section("vitals")).thenReturn(Optional.of(vitals));
        when(library.section("nothing")).thenReturn(Optional.empty());

        assertThat(service.section("vitals")).isEqualTo(vitals);
        assertThatThrownBy(() -> service.section("nothing")).isInstanceOfSatisfying(EmrProblemException.class,
                ex -> assertThat(ex.slug()).isEqualTo("section-not-found"));
        assertThatThrownBy(() -> service.section("Bad-Key")).isInstanceOfSatisfying(EmrProblemException.class,
                ex -> assertThat(ex.slug()).isEqualTo("bad-request"));
    }
}
