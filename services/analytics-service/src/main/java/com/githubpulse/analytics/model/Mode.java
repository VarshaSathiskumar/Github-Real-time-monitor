package com.githubpulse.analytics.model;

/** Which data universe a request addresses. Demo data never mixes with live data. */
public enum Mode {
    LIVE("live"),
    DEMO("demo");

    private final String code;

    Mode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Mode parse(String code) {
        if (code == null || code.isBlank() || code.equalsIgnoreCase("live")) {
            return LIVE;
        }
        if (code.equalsIgnoreCase("demo")) {
            return DEMO;
        }
        throw new IllegalArgumentException("Unsupported mode '" + code + "', expected live or demo");
    }
}
