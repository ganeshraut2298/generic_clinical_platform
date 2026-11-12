package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.opd.application.emr.EmrMasterRepository.MasterItemRow;
import com.sahaj.opd.application.emr.EmrMasterRepository.MasterPageRow;
import com.sahaj.opd.application.emr.EmrMasterRepository.QuickOptionRow;
import com.sahaj.opd.application.emr.EmrViews.MasterPage;
import com.sahaj.opd.application.emr.EmrViews.QuickOptionList;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/** SAH-43-1, SAH-43-2: master search rules and the signed-in doctor's own ranking. */
class EmrMasterServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();

    private EmrMasterRepository repository;
    private EmrAccess access;
    private EmrMasterService service;
    private ClinicUser user;

    @BeforeEach
    void setUp() {
        repository = mock(EmrMasterRepository.class);
        access = mock(EmrAccess.class);
        service = new EmrMasterService(repository, access);
        user = new ClinicUser(USER, TENANT, "DOCTOR");
        when(repository.search(any(), any(), any(), anyInt(), anyInt())).thenReturn(new MasterPageRow(List.of(), 0));
        when(access.requireConsultationWrite(user)).thenReturn(new TeamMember(DOCTOR, "Test Doctor", "DOCTOR", true, 0L));
    }

    // ---- search ----

    @Test
    void searchEscapesWildcardsAndPagesTwentyAtATime() {
        UUID id = UUID.randomUUID();
        when(repository.search(EmrMaster.COMPLAINTS, "50\\%%", "paeds", 20, 40))
                .thenReturn(new MasterPageRow(List.of(new MasterItemRow(id, "Fever", "paeds")), 61));

        MasterPage page = service.search("complaints", " 50% ", "paeds", 2);

        assertThat(page.page()).isEqualTo(2);
        assertThat(page.pageSize()).isEqualTo(20);
        assertThat(page.total()).isEqualTo(61);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(id);
            assertThat(item.specialtyHint()).isEqualTo("paeds");
        });
    }

    @Test
    void anEmptyOrAllSpaceSearchAsksForTheFirstPageInMasterOrder() {
        service.search("advice", "   ", null, null);
        service.search("investigations", null, null, null);

        verify(repository).search(EmrMaster.ADVICE, "%", null, 20, 0);
        verify(repository).search(EmrMaster.INVESTIGATIONS, "%", null, 20, 0);
    }

    @Test
    void anAnswerWithNoMatchIsAnEmptyList() {
        MasterPage page = service.search("complaints", "zzzz", null, 0);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
    }

    @Test
    void onlyTheThreeNewMastersAreSearchedHere() {
        for (String master : new String[] {"diagnoses", "medicines", "labs", "", "Complaints"}) {
            assertThatThrownBy(() -> service.search(master, "a", null, 0))
                    .isInstanceOfSatisfying(EmrProblemException.class, ex -> {
                        assertThat(ex.status()).isEqualTo(400);
                        assertThat(ex.slug()).isEqualTo("bad-request");
                    });
        }
        verify(repository, never()).search(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void aTooLongTextASpecialtyOrAPageOutOfRangeIsAValidationError() {
        assertThatThrownBy(() -> service.search("complaints", "a".repeat(101), null, 0))
                .isInstanceOfSatisfying(InvalidInputException.class, ex -> assertThat(ex.field()).isEqualTo("q"));
        assertThatThrownBy(() -> service.search("complaints", "a", "ayur", 0))
                .isInstanceOfSatisfying(InvalidInputException.class, ex -> assertThat(ex.field()).isEqualTo("specialty"));
        assertThatThrownBy(() -> service.search("complaints", "a", null, 501))
                .isInstanceOfSatisfying(InvalidInputException.class, ex -> assertThat(ex.field()).isEqualTo("page"));
        assertThatThrownBy(() -> service.search("complaints", "a", null, -1))
                .isInstanceOf(InvalidInputException.class);
    }

    // ---- quick options ----

    @Test
    void theRankingIsAlwaysTheCallersOwnMembership() {
        Instant used = Instant.parse("2026-11-10T06:05:00Z");
        when(repository.ranked(TENANT, DOCTOR, EmrMaster.DIAGNOSES, "gp", 10)).thenReturn(List.of(
                new QuickOptionRow("J06.9", "Acute upper respiratory infection, unspecified", "J06.9", null, null, 5,
                        used),
                new QuickOptionRow("A09", "Infectious gastroenteritis and colitis, unspecified", "A09", null, null, 0,
                        null)));

        QuickOptionList list = service.quickOptions(user, "diagnoses", null, "gp");

        assertThat(list.master()).isEqualTo("diagnoses");
        assertThat(list.items()).extracting(EmrViews.QuickOption::itemKey).containsExactly("J06.9", "A09");
        assertThat(list.items().get(0).useCount()).isEqualTo(5);
        assertThat(list.items().get(0).lastUsedAt()).isEqualTo(used);
        assertThat(list.items().get(1).lastUsedAt()).isNull();
    }

    @Test
    void theLimitDefaultsToTenAndIsOneToFifty() {
        service.quickOptions(user, "medicines", null, null);
        verify(repository).ranked(TENANT, DOCTOR, EmrMaster.MEDICINES, null, 10);

        service.quickOptions(user, "medicines", 50, null);
        verify(repository).ranked(TENANT, DOCTOR, EmrMaster.MEDICINES, null, 50);

        assertThatThrownBy(() -> service.quickOptions(user, "medicines", 0, null))
                .isInstanceOfSatisfying(InvalidInputException.class, ex -> assertThat(ex.field()).isEqualTo("limit"));
        assertThatThrownBy(() -> service.quickOptions(user, "medicines", 51, null))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void allFiveMastersAreAccepted() {
        for (String master : new String[] {"complaints", "diagnoses", "medicines", "investigations", "advice"}) {
            assertThat(service.quickOptions(user, master, 5, null).master()).isEqualTo(master);
        }
    }

    @Test
    void anUnknownMasterIsBadRequestAndAnotherRoleIsRefusedBeforeAnythingElse() {
        assertThatThrownBy(() -> service.quickOptions(user, "labs", null, null))
                .isInstanceOfSatisfying(EmrProblemException.class, ex -> assertThat(ex.slug()).isEqualTo("bad-request"));

        ClinicUser nurse = new ClinicUser(USER, TENANT, "NURSE");
        doThrow(new AccessDeniedException("no")).when(access).requireConsultationWrite(nurse);
        assertThatThrownBy(() -> service.quickOptions(nurse, "labs", 999, "x"))
                .isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).ranked(any(), any(), any(), any(), anyInt());
    }
}
