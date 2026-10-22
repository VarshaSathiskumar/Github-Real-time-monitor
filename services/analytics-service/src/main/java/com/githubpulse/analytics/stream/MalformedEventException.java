package com.githubpulse.analytics.stream;

public class MalformedEventException extends RuntimeException {
    public MalformedEventException(String message) {
        super(message);
    }

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
