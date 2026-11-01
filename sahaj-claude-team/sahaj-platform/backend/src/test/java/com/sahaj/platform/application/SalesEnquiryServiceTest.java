package com.sahaj.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.platform.domain.BestTime;
import com.sahaj.platform.domain.EnquirySource;
import com.sahaj.platform.domain.NewSalesEnquiry;
import com.sahaj.platform.support.LogCapture;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SalesEnquiryServiceTest {

    private final SalesEnquiryRepository repository = mock(SalesEnquiryRepository.class);
    private final SalesEnquiryService service = new SalesEnquiryService(repository);

    @Test
    void trimsTextSavesAndReturnsTheId() {
        UUID id = UUID.randomUUID();
        when(repository.insert(org.mockito.ArgumentMatchers.any())).thenReturn(id);

        UUID result = service.create(new NewSalesEnquiry("  Asha Patil ", " Sunrise Clinic, Pune ", "9123456789",
                BestTime.AFTER_OPD, EnquirySource.BOOK_DEMO));

        assertThat(result).isEqualTo(id);
        ArgumentCaptor<NewSalesEnquiry> saved = ArgumentCaptor.forClass(NewSalesEnquiry.class);
        verify(repository).insert(saved.capture());
        assertThat(saved.getValue().name()).isEqualTo("Asha Patil");
        assertThat(saved.getValue().clinicCity()).isEqualTo("Sunrise Clinic, Pune");
        assertThat(saved.getValue().mobile()).isEqualTo("9123456789");
        assertThat(saved.getValue().bestTime()).isEqualTo(BestTime.AFTER_OPD);
        assertThat(saved.getValue().source()).isEqualTo(EnquirySource.BOOK_DEMO);
    }

    @Test
    void storesABlankClinicCityAsNothing() {
        service.create(new NewSalesEnquiry("Asha", "   ", "9123456789", null, EnquirySource.CONTACT_SALES));

        ArgumentCaptor<NewSalesEnquiry> saved = ArgumentCaptor.forClass(NewSalesEnquiry.class);
        verify(repository).insert(saved.capture());
        assertThat(saved.getValue().clinicCity()).isNull();
        assertThat(saved.getValue().bestTime()).isNull();
    }

    @Test
    void logsTheIdAndSourceButNeverTheMobileOrName() {
        when(repository.insert(org.mockito.ArgumentMatchers.any())).thenReturn(UUID.randomUUID());
        try (LogCapture logs = new LogCapture()) {
            service.create(new NewSalesEnquiry("Asha Patil", "Pune", "9123456789", null, EnquirySource.CONTACT_SALES));

            assertThat(logs.allText()).contains("Sales enquiry saved").contains("CONTACT_SALES")
                    .doesNotContain("9123456789").doesNotContain("Asha").doesNotContain("Pune");
        }
    }
}
