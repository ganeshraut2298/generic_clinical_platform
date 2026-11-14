package com.sahaj.opd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.opd.application.DiagnosisRepository.DiagnosisRow;
import com.sahaj.platform.api.InvalidInputException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SAH-7 AC5 unchanged; SAH-43-1 adds an optional specialty (hint first) and a page of 20. */
class DiagnosisServiceTest {

    private final DiagnosisRepository repository = mock(DiagnosisRepository.class);
    private final DiagnosisService service = new DiagnosisService(repository);

    @Test
    void theSah7SearchIsTheFirstTwentyWithNoSpecialty() {
        when(repository.search("j06", null, 20, 0)).thenReturn(List.of(new DiagnosisRow("J06.9", "Acute URI", "gp")));

        assertThat(service.search("j06")).hasSize(1);
        assertThat(service.search("j06", null, null)).hasSize(1);
        verify(repository, org.mockito.Mockito.times(2)).search("j06", null, 20, 0);
    }

    @Test
    void aSpecialtyAndAPageAreHandedToTheSearch() {
        service.search("a", "dental", 3);

        verify(repository).search("a", "dental", 20, 60);
    }

    @Test
    void aStarterKeyIsMappedToTheSah7SpecialtyCode() {
        service.search("a", "gm", 0);

        verify(repository).search("a", "gp", 20, 0);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.search("a", "homeo", 0))
                .isInstanceOf(com.sahaj.platform.api.InvalidInputException.class);
    }

    @Test
    void aSpecialtyThatIsNotOneOfTheFourOrAPageOutOfRangeIsRefused() {
        assertThatThrownBy(() -> service.search("a", "ayur", 0)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.search("a", null, 501)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.search("a", null, -1)).isInstanceOf(InvalidInputException.class);
        verify(repository, never()).search(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }
}
