package com.sahaj.platform.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Helpers shared by the full-app sweep tests: raw calls, problem+json checks, real team members. */
abstract class SweepSupport extends OpdJourneySupport {

    /** Sends the request; {@code body} may be null. */
    protected ResultActions call(HttpMethod method, String path, RequestPostProcessor auth, String body) throws Exception {
        java.net.URI uri = java.net.URI.create(path); // already encoded: MockMvc must not encode it a second time
        MockHttpServletRequestBuilder b = switch (method.name()) {
            case "GET" -> get(uri);
            case "POST" -> post(uri);
            case "PUT" -> put(uri);
            case "PATCH" -> patch(uri);
            case "DELETE" -> delete(uri);
            default -> throw new IllegalArgumentException(method.name());
        };
        b.with(auth);
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(b);
    }

    protected JsonNode ok(HttpMethod method, String path, RequestPostProcessor auth, String body, int expected) throws Exception {
        MvcResult r = call(method, path, auth, body).andExpect(status().is(expected)).andReturn();
        String text = r.getResponse().getContentAsString();
        return text.isEmpty() ? null : objectMapper.readTree(text);
    }

    /** Expects the status and an RFC 9457 body. */
    protected JsonNode problem(ResultActions actions, int expected) throws Exception {
        MvcResult r = actions.andExpect(status().is(expected))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json")).andReturn();
        JsonNode n = objectMapper.readTree(r.getResponse().getContentAsString());
        if (n.get("status") == null || n.get("status").asInt() != expected || n.get("title") == null) {
            throw new AssertionError("Not a problem body: " + n);
        }
        return n;
    }

    protected record Member(UUID membershipId, UUID userId, String mobile, String role) {
    }

    /** Adds a team member through the real endpoint and reads the user id back from the database. */
    protected Member addMember(Clinic clinic, String role) throws Exception {
        String mobile = uniqueMobile();
        String doctor = role.contains("DOCTOR")
                ? ",\"doctor\":{\"specialty\":\"General physician\",\"feeRupees\":250,\"degree\":\"MBBS\",\"registrationNo\":\"R1\"}"
                : "";
        JsonNode created = ok(HttpMethod.POST, "/api/v1/platform/team", clinic.owner(),
                "{\"name\":\"Member " + role + "\",\"mobile\":\"" + mobile + "\",\"role\":\"" + role + "\"" + doctor + "}", 201);
        UUID membershipId = UUID.fromString(created.get("id").asText());
        return new Member(membershipId, null, mobile, role);
    }

    protected static String json(String s) {
        return s.replace('\'', '"');
    }

    /** A real OTP login for a mobile (LOGIN mode); returns the session of its only clinic. */
    protected Session login(String mobile) throws Exception {
        String code = requestOtpAndCaptureCode(mobile, "LOGIN");
        MvcResult result = verify(mobile, code, "LOGIN", null, null);
        return Session.from(result, tokenService);
    }

    protected static RequestPostProcessor bearer(String accessToken) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + accessToken);
            return request;
        };
    }
}
