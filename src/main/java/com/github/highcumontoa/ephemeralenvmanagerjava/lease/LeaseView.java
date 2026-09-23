package com.github.highcumontoa.ephemeralenvmanagerjava.lease;

import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseState;

public record LeaseView(String name, String owner, LeaseState state,
                        long createdAtMillis, long expiresAtMillis,
                        int leaseVersion, int releaseAttempts) {

    public static LeaseView of(Lease lease) {
        return new LeaseView(lease.getName(), lease.getOwner(), lease.getState(),
                lease.getCreatedAtMillis(), lease.getExpiresAtMillis(),
                lease.getLeaseVersion(), lease.getReleaseAttempts());
    }
}
