package com.automationstudio.engine.selenium;

import java.util.UUID;

record SeleniumAbsenceReport(UUID executionId, boolean containerAbsent, boolean immutableIdAbsent,
        boolean attachClosed, boolean workerJvmAbsent, boolean ephemeralStorageAbsent,
        boolean ipcClosed, Code code) {
    enum Code { ABSENT, IDENTITY_MISMATCH, CONTAINER_PRESENT, CLEANUP_FAILED }
    SeleniumAbsenceReport {
        if (executionId == null || code == null) throw new IllegalArgumentException("Invalid absence report");
    }
    boolean absenceProved() { return containerAbsent && immutableIdAbsent && attachClosed
            && workerJvmAbsent && ephemeralStorageAbsent && ipcClosed && code == Code.ABSENT; }
}
