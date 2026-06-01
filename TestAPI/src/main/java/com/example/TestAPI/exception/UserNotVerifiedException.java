package com.example.TestAPI.exception;

public class UserNotVerifiedException extends RuntimeException {
    public UserNotVerifiedException() {
        super("Utilisateur non vérifié");
    }
}
