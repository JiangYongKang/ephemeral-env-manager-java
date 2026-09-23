package com.github.highcumontoa.ephemeralenvmanagerjava.lease;

public record RenewRequest(String owner, long ttlMillis) {
}
