package com.example.inventory.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SecurityProblems.Denied")
class SecurityProblemsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("writes a complete RFC 9457 body with the request path as instance")
    void deniedWritesAProblem() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/users");
        request.setRequestURI("/api/v1/users");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SecurityProblems.Denied()
                .handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/problem+json");

        JsonNode body = JSON.readTree(response.getContentAsString());

        assertThat(body.has("type")).isTrue();
        assertThat(body.has("title")).isTrue();
        assertThat(body.has("status")).isTrue();
        assertThat(body.has("detail")).isTrue();
        assertThat(body.has("instance")).isTrue();

        assertThat(body.get("type").asString())
                .isEqualTo("https://api.example.com/problems/insufficient-role");
        assertThat(body.get("title").asString()).isEqualTo("Forbidden");
        assertThat(body.get("status").asInt()).isEqualTo(403);
        assertThat(body.get("instance").asString()).isEqualTo("/api/v1/users");

        assertThat(body.get("detail").asString())
                .isEqualTo("Your role does not permit this action");
    }

    @Test
    @DisplayName("writes nothing to an already-committed response")
    void deniedLeavesACommittedResponseAlone() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users");
        request.setRequestURI("/api/v1/users");
        MockHttpServletResponse response = new MockHttpServletResponse();

        response.getWriter().write("already sent");
        response.flushBuffer();

        new SecurityProblems.Denied()
                .handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getContentAsString()).isEqualTo("already sent");
    }
}
