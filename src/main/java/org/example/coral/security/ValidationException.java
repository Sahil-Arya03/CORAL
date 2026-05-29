package org.example.coral.security;

/** Thrown when a planned query fails any structural safety check. Validation failure is terminal. */
public class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
