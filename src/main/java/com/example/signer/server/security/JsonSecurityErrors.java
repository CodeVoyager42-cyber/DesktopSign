package com.example.signer.server.security;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * The JSON bodies Spring Security answers with, in the API's usual
 * {@code {"code","message"}} shape. They never say why a token was refused
 * (missing, malformed, expired, wrong key): that is logged server-side only.
 */
final class JsonSecurityErrors {

    static final String AUTHENTICATION_REQUIRED = "AUTHENTICATION_REQUIRED";

    private JsonSecurityErrors() {
    }

    /** 401 with a Bearer challenge. */
    static AuthenticationEntryPoint entryPoint() {
        return (request, response, e) -> unauthorized(response);
    }

    /** 403 for an authenticated caller without the required permission. */
    static AccessDeniedHandler accessDenied() {
        return (request, response, e) -> forbidden(response, "FORBIDDEN",
                "You are not allowed to perform this action");
    }

    static void unauthorized(HttpServletResponse response) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer realm=\"signer-workflows\"");
        write(response, HttpServletResponse.SC_UNAUTHORIZED, AUTHENTICATION_REQUIRED,
                "Authentication required");
    }

    static void forbidden(HttpServletResponse response, String code, String message)
            throws IOException {
        write(response, HttpServletResponse.SC_FORBIDDEN, code, message);
    }

    private static void write(HttpServletResponse response, int status, String code,
                              String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":\"" + escape(code) + "\",\"message\":\""
                + escape(message) + "\"}");
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
