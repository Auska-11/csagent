package com.smartcs.smart_cs_agent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.controller.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Security Filter 在进入 Controller 前失败时，也返回项目统一的 ApiErrorResponse。 */
@Component
public class SecurityErrorWriter {

    private final ObjectMapper objectMapper;

    public SecurityErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void writeUnauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        boolean suppliedCredentials = request.getHeader("Authorization") != null;
        write(
                response,
                HttpServletResponse.SC_UNAUTHORIZED,
                suppliedCredentials ? "AUTHENTICATION_FAILED" : "AUTHENTICATION_REQUIRED",
                suppliedCredentials ? "Authentication failed" : "Authentication is required"
        );
    }

    public void writeForbidden(HttpServletResponse response) throws IOException {
        write(response, HttpServletResponse.SC_FORBIDDEN, "ACCESS_DENIED", "Access is denied");
    }

    private void write(HttpServletResponse response, int status, String errorCode, String message)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsetsHolder.UTF_8);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(errorCode, message));
    }

    /** 避免为了一个固定字符串引入可变 Charset 对象。 */
    private static final class StandardCharsetsHolder {
        private static final String UTF_8 = "UTF-8";
    }
}
