package com.automationstudio.api.service;

public enum HeartbeatFailure {
    LEASE_NOT_FOUND,
    OWNERSHIP_MISMATCH,
    STALE_GENERATION,
    EXPIRED_LEASE,
    EXECUTION_STATE_INELIGIBLE,
    OPTIMISTIC_LOCK_CONFLICT
}
