package com.example.TestAPI.DTO.Error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        int status,
        String error,
        String errorCode,
        String timestamp,
        String path
) {
    public static ErrorResponse of(int status, String error, String errorCode) {
        return new ErrorResponse(status, error, errorCode, LocalDateTime.now().toString(), null);
    }

    public static ErrorResponse of(int status, String error, String errorCode, String path) {
        return new ErrorResponse(status, error, errorCode, LocalDateTime.now().toString(), path);
    }
}
