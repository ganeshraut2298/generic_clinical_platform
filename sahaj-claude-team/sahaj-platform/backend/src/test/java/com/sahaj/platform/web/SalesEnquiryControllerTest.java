package com.sahaj.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.platform.application.SalesEnquiryService;
import com.sahaj.platform.domain.BestTime;
import com.sahaj.platform.domain.EnquirySource;
import com.sahaj.platform.domain.NewSalesEnquiry;
import com.sahaj.platform.web.security.SecurityConfig;
import com.sahaj.platform.support.LogCapture;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(controllers = SalesEnquiryController.class)
@Import(SecurityConfig.class)
class SalesEnquiryControllerTest {

    private static final String URL = "/api/v1/platform/public/sales-enquiries";
    private static final String MOBILE = "9123456789";
    private static final AtomicInteger NEXT_CLIENT = new AtomicInteger(10);

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SalesEnquiryService service;

    /** Each test uses its own client address, because the limiter lives as long as the context. */
    private static RequestPostProcessor fromNewClient() {
        String address = "198.51.100." + NEXT_CLIENT.getAndIncrement();
        return fromClient(address);
    }

    private static RequestPostProcessor fromClient(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static String body(String name, String mobile) {
        return """
                {"name": "%s", "clinicCity": "Sunrise Clinic, Pune", "mobile": "%s", "bestTime": "MORNING", "source": "CONTACT_SALES"}
                """.formatted(name, mobile);
    }

    private ResultActions submit(String json, RequestPostProcessor client) throws Exception {
        return mockMvc.perform(post(URL).with(client).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void savesAValidEnquiryAndReturns201WithTheId() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.create(any())).thenReturn(id);

        submit(body("Asha Patil", MOBILE), fromNewClient())
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(id.toString()));

        ArgumentCaptor<NewSalesEnquiry> saved = ArgumentCaptor.forClass(NewSalesEnquiry.class);
        verify(service).create(saved.capture());
        assertThat(saved.getValue().name()).isEqualTo("Asha Patil");
        assertThat(saved.getValue().clinicCity()).isEqualTo("Sunrise Clinic, Pune");
        assertThat(saved.getValue().mobile()).isEqualTo(MOBILE);
        assertThat(saved.getValue().bestTime()).isEqualTo(BestTime.MORNING);
        assertThat(saved.getValue().source()).isEqualTo(EnquirySource.CONTACT_SALES);
    }

    @Test
    void acceptsAnEnquiryWithOnlyTheRequiredFields() throws Exception {
        when(service.create(any())).thenReturn(UUID.randomUUID());

        submit("""
                {"name": "Asha", "mobile": "6123456789", "source": "BOOK_DEMO"}
                """, fromNewClient())
                .andExpect(status().isCreated());
    }

    @Test
    void rejectsAMissingNameWithAFieldError() throws Exception {
        submit(body("   ", MOBILE), fromNewClient())
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Enter your name"));
        verify(service, never()).create(any());
    }

    @Test
    void rejectsMobileNumbersThatAreNotTenDigitsStartingWithSixToNine() throws Exception {
        for (String bad : new String[] {"5123456789", "98765", "91234567890", "91234abcde", "+919123456789", " 9123456789"}) {
            submit(body("Asha", bad), fromNewClient())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("mobile"))
                    .andExpect(jsonPath("$.fieldErrors[0].message").value("Enter a valid 10-digit mobile number"));
        }
        verify(service, never()).create(any());
    }

    @Test
    void reportsEveryMissingRequiredFieldTogether() throws Exception {
        submit("{}", fromNewClient())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", containsInAnyOrder("name", "mobile", "source")));
    }

    @Test
    void rejectsTooLongText() throws Exception {
        String longName = "a".repeat(121);
        submit(body(longName, MOBILE), fromNewClient())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("name")));
    }

    @Test
    void rejectsControlCharactersInTheNameWithAFieldError() throws Exception {
        submit(body("Asha\u0007Patil", MOBILE), fromNewClient())
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("name")));
        verify(service, never()).create(any());
    }

    @Test
    void rejectsControlCharactersInTheClinicCityWithAFieldError() throws Exception {
        submit("""
                {"name": "Asha", "clinicCity": "Pune\u0007", "mobile": "%s", "source": "CONTACT_SALES"}
                """.formatted(MOBILE), fromNewClient())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("clinicCity")));
    }

    @Test
    void rejectsABodyLargerThanFourKilobytesWith413() throws Exception {
        String longCity = "a".repeat(5 * 1024);
        mockMvc.perform(post(URL)
                        .with(fromNewClient())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Asha", MOBILE).replace("Sunrise Clinic, Pune", longCity)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void rejectsAnUnknownOptionWithoutEchoingIt() throws Exception {
        submit("""
                {"name": "Asha", "mobile": "9123456789", "source": "CONTACT_SALES", "bestTime": "MIDNIGHT"}
                """, fromNewClient())
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("bestTime"))
                .andExpect(content().string(not(containsString("MIDNIGHT"))));
    }

    @Test
    void rejectsBrokenJsonWithAProblem() throws Exception {
        submit("{\"name\": \"Asha\", \"mobile\": ", fromNewClient())
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/malformed-request"));
    }

    @Test
    void rejectsAnEmptyBody() throws Exception {
        mockMvc.perform(post(URL).with(fromNewClient()).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void rejectsAnUnsupportedContentTypeWithAProblem() throws Exception {
        mockMvc.perform(post(URL).with(fromNewClient()).contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    void rejectsOtherMethodsWithAProblem() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void ignoresATenantIdInTheBodyHeaderOrQuery() throws Exception {
        when(service.create(any())).thenReturn(UUID.randomUUID());
        String other = UUID.randomUUID().toString();

        mockMvc.perform(post(URL + "?tenantId=" + other)
                        .with(fromNewClient())
                        .header("X-Tenant-Id", other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Asha", "mobile": "9123456789", "source": "CONTACT_SALES", "tenantId": "%s"}
                                """.formatted(other)))
                .andExpect(status().isCreated());
    }

    @Test
    void answers429AfterFiveRequestsFromTheSameAddressInAnHour() throws Exception {
        when(service.create(any())).thenReturn(UUID.randomUUID());
        RequestPostProcessor client = fromNewClient();

        for (int i = 0; i < 5; i++) {
            submit(body("Asha", MOBILE), client).andExpect(status().isCreated());
        }
        submit(body("Asha", MOBILE), client)
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/rate-limit"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
    }

    @Test
    void doesNotSaveARequestThatIsOverTheLimit() throws Exception {
        when(service.create(any())).thenReturn(UUID.randomUUID());
        RequestPostProcessor client = fromNewClient();
        for (int i = 0; i < 6; i++) {
            submit(body("Asha", MOBILE), client);
        }
        verify(service, org.mockito.Mockito.times(5)).create(any());
    }

    @Test
    void countsInvalidRequestsTowardsTheLimit() throws Exception {
        RequestPostProcessor client = fromNewClient();
        for (int i = 0; i < 5; i++) {
            submit("{}", client).andExpect(status().isBadRequest());
        }
        submit(body("Asha", MOBILE), client).andExpect(status().isTooManyRequests());
    }

    @Test
    void aForgedForwardedHeaderCannotBypassTheLimit() throws Exception {
        when(service.create(any())).thenReturn(UUID.randomUUID());
        RequestPostProcessor client = fromNewClient();

        for (int i = 0; i < 5; i++) {
            submit(body("Asha", MOBILE), client).andExpect(status().isCreated());
        }
        // The client now rotates every forwarding header it can think of; the connection address is unchanged.
        for (int i = 0; i < 20; i++) {
            mockMvc.perform(post(URL)
                            .with(client)
                            .header("X-Forwarded-For", "203.0.113." + i + ", 10.0.0." + i)
                            .header("Forwarded", "for=203.0.113." + i)
                            .header("X-Real-IP", "203.0.113." + i)
                            .header("X-Client-IP", "203.0.113." + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("Asha", MOBILE)))
                    .andExpect(status().isTooManyRequests());
        }
    }

    @Test
    void aPercentEncodedPathStillCountsTowardsTheSameLimit() throws Exception {
        when(service.create(any())).thenReturn(UUID.randomUUID());
        RequestPostProcessor client = fromNewClient();

        for (int i = 0; i < 5; i++) {
            submit(body("Asha", MOBILE), client).andExpect(status().isCreated());
        }
        // %73 decodes to 's'; the path still resolves to SalesEnquiryController.create.
        mockMvc.perform(post(java.net.URI.create("/api/v1/platform/public/%73ales-enquiries"))
                        .with(client)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Asha", MOBILE)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void aPathWithAnEncodedHyphenStillCountsTowardsTheSameLimit() throws Exception {
        when(service.create(any())).thenReturn(UUID.randomUUID());
        RequestPostProcessor client = fromNewClient();

        for (int i = 0; i < 5; i++) {
            submit(body("Asha", MOBILE), client).andExpect(status().isCreated());
        }
        // %2D decodes to '-'; the path still resolves to SalesEnquiryController.create.
        mockMvc.perform(post(java.net.URI.create("/api/v1/platform/public/sales%2Denquiries"))
                        .with(client)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Asha", MOBILE)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void aSecondAddressHasItsOwnAllowance() throws Exception {
        when(service.create(any())).thenReturn(UUID.randomUUID());
        RequestPostProcessor first = fromNewClient();
        for (int i = 0; i < 6; i++) {
            submit(body("Asha", MOBILE), first);
        }
        submit(body("Asha", MOBILE), fromNewClient()).andExpect(status().isCreated());
    }

    @Test
    void everyResponseCarriesARequestId() throws Exception {
        when(service.create(any())).thenReturn(UUID.randomUUID());
        submit(body("Asha", MOBILE), fromNewClient())
                .andExpect(header().exists("X-Request-Id"));
        submit("{}", fromNewClient())
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void theMobileNumberNeverReachesTheLogOrAnErrorResponse() throws Exception {
        when(service.create(any())).thenReturn(UUID.randomUUID());
        try (LogCapture logs = new LogCapture()) {
            RequestPostProcessor client = fromNewClient();
            // valid, invalid name, invalid mobile that is still a number, broken JSON, wrong option, and the 429
            submit(body("Asha", MOBILE), client);
            submit(body(" ", MOBILE), client)
                    .andExpect(content().string(not(containsString(MOBILE))));
            submit(body("Asha", "5123456789"), client)
                    .andExpect(content().string(not(containsString("5123456789"))));
            submit("{\"name\": \"Asha\", \"mobile\": \"" + MOBILE + "\", ", client)
                    .andExpect(content().string(not(containsString(MOBILE))));
            submit("{\"name\":\"Asha\",\"mobile\":\"" + MOBILE + "\",\"source\":\"CONTACT_SALES\",\"bestTime\":\"X\"}", client)
                    .andExpect(content().string(not(containsString(MOBILE))));
            submit(body("Asha", MOBILE), client);
            submit(body("Asha", MOBILE), client)
                    .andExpect(status().isTooManyRequests())
                    .andExpect(content().string(not(containsString(MOBILE))));

            assertThat(logs.allText()).doesNotContain(MOBILE).doesNotContain("5123456789");
        }
    }

    @Test
    void theRequestObjectDoesNotPrintTheMobileNumber() {
        SalesEnquiryRequest request = new SalesEnquiryRequest("Asha", "Pune", MOBILE, BestTime.MORNING, EnquirySource.BOOK_DEMO);
        assertThat(request.toString()).doesNotContain(MOBILE).doesNotContain("Asha");
        assertThat(new NewSalesEnquiry("Asha", "Pune", MOBILE, null, EnquirySource.BOOK_DEMO).toString())
                .doesNotContain(MOBILE).doesNotContain("Asha");
    }
}
