package com.github.highcumontoa.ephemeralenvmanagerjava.lease;

public record CreateRequest(String name, String owner, long ttlMillis) {
}
