package org.example.coral.model;

public record PolicyDecision(Verdict verdict, String reason, boolean requiresConfirmation) {

    public static PolicyDecision allow(String reason) {
        return new PolicyDecision(Verdict.ALLOW, reason, false);
    }

    public static PolicyDecision deny(String reason) {
        return new PolicyDecision(Verdict.DENY, reason, false);
    }

    public static PolicyDecision confirm(String reason) {
        return new PolicyDecision(Verdict.CONFIRM, reason, true);
    }
}
