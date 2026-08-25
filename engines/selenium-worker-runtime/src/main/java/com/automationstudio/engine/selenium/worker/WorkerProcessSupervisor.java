package com.automationstudio.engine.selenium.worker;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

final class WorkerProcessSupervisor implements AutoCloseable {
    record OwnedProcess(long pid, Instant startIdentity) {
        OwnedProcess { if (pid < 1 || startIdentity == null) throw new IllegalArgumentException("Invalid process identity"); }
    }
    private final AtomicBoolean closed = new AtomicBoolean();
    private final OwnedProcess worker;
    WorkerProcessSupervisor(ProcessHandle handle) {
        worker = new OwnedProcess(handle.pid(), handle.info().startInstant().orElse(Instant.EPOCH));
    }
    OwnedProcess worker() { return worker; }
    Optional<OwnedProcess> child() { return Optional.empty(); }
    @Override public void close() { closed.compareAndSet(false, true); }
    boolean closed() { return closed.get(); }
}
