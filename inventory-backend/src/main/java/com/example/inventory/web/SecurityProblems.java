package com.example.inventory.web;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import tools.jackson.databind.ObjectMapper;

public final class SecurityProblems {

    private static final String PROBLEM_BASE = "https://api.example.com/problems/";
    private static final ObjectMapper JSON = new ObjectMapper();

    private SecurityProblems() {
    }

    private static void write(HttpServletResponse response, HttpServletRequest request,
                              HttpStatus status, String title, String detail, String problemType)
            throws IOException {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", URI.create(PROBLEM_BASE + problemType).toString());
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", request.getRequestURI());

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(JSON.writeValueAsString(body));
    }

    public static final class EntryPoint implements AuthenticationEntryPoint {

        private final AuthenticationEntryPoint delegate;

        public EntryPoint(AuthenticationEntryPoint delegate) {
            this.delegate = delegate;
        }

        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response,
                             AuthenticationException authException)
                throws IOException, jakarta.servlet.ServletException {
            delegate.commence(request, response, authException);

            if (response.isCommitted()) {
                return;
            }
            write(response, request, HttpStatus.UNAUTHORIZED,
                    "Authentication required",
                    "This endpoint requires a valid access token.",
                    "unauthenticated");
        }
    }

    public static final class Denied implements AccessDeniedHandler {

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response,
                           AccessDeniedException deniedException) throws IOException {
            if (response.isCommitted()) {
                return;
            }
            write(response, request, HttpStatus.FORBIDDEN,
                    "Forbidden",
                    "Your role does not permit this action",
                    "insufficient-role");
        }
    }
}
