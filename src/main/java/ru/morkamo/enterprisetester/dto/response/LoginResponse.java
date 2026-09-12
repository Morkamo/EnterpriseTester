package ru.morkamo.enterprisetester.dto.response;

public record LoginResponse(
        boolean success,
        String message,
        String fullName,
        Long userId
) {
    public LoginResponse(boolean success, String message) {
        this(success, message, null, null);
    }
}
