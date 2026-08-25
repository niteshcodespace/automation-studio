package com.automationstudio.engine.selenium.worker;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class WorkerProcessSupervisorTest {
    @Test void recordsExactCurrentIdentityAndStopsIdempotentlyWithoutScanning() {
        WorkerProcessSupervisor supervisor = new WorkerProcessSupervisor(ProcessHandle.current());
        assertEquals(ProcessHandle.current().pid(), supervisor.worker().pid());
        assertTrue(supervisor.child().isEmpty());
        supervisor.close(); supervisor.close(); assertTrue(supervisor.closed());
    }
}
