package com.automationstudio.engine.selenium;

import com.automationstudio.engine.sdk.ExecutionControl;
import java.util.UUID;
import java.util.function.Consumer;

final class DockerSeleniumContainmentRuntime implements SeleniumContainmentRuntime {
    private final String image;
    private final SeleniumContainmentLimits limits;
    private final SeleniumContainmentCommandRunner runner;
    private final Consumer<String> createdIdentityHook;
    private final Consumer<ContainmentDeadline> terminationObserver;

    DockerSeleniumContainmentRuntime(String image, SeleniumContainmentLimits limits,
            SeleniumContainmentCommandRunner runner) {
        this(image,limits,runner,ignored -> {},ignored -> {});
    }
    DockerSeleniumContainmentRuntime(String image,SeleniumContainmentLimits limits,
            SeleniumContainmentCommandRunner runner,Consumer<String> createdIdentityHook) {
        this(image,limits,runner,createdIdentityHook,ignored -> {});
    }
    DockerSeleniumContainmentRuntime(String image,SeleniumContainmentLimits limits,
            SeleniumContainmentCommandRunner runner,Consumer<String> createdIdentityHook,
            Consumer<ContainmentDeadline> terminationObserver) {
        this.image=image;this.limits=limits;this.runner=runner;this.createdIdentityHook=createdIdentityHook;this.terminationObserver=terminationObserver;
    }

    @Override public SeleniumAbsenceReport qualify(UUID id, ExecutionControl control) {
        if (id == null || control == null || !control.isBounded())
            throw new SeleniumEngineException("EXECUTION_CONTROL_REQUIRED", "Selenium containment failed");
        var resources = new SeleniumContainmentResources(id, runner, limits, System::nanoTime, terminationObserver);
        control.registerTeardown(resources::teardown);
        try {
            var created = runner.run(SeleniumWorkerCommand.create(id, image, limits),
                    limits.commandTimeout(), limits.maxOutputBytes());
            if (created.exitCode() != 0)
                throw failure("WORKER_CREATE_FAILED");
            String containerId = created.output();
            if (!containerId.matches("[a-f0-9]{64}")) {
                var recovery = runner.run(SeleniumWorkerCommand.inspectName(SeleniumWorkerCommand.workerName(id)),
                        limits.commandTimeout(), limits.maxOutputBytes());
                String suffix = "|" + SeleniumWorkerCommand.label(id);
                if (recovery.exitCode() != 0 || !recovery.output().endsWith(suffix)
                        || !recovery.output().substring(0, recovery.output().length()-suffix.length()).matches("[a-f0-9]{64}"))
                    throw failure("WORKER_IDENTITY_FAILED");
                containerId = recovery.output().substring(0, recovery.output().length()-suffix.length());
            }
            var identity = new SeleniumRuntimeIdentity(id, SeleniumWorkerCommand.workerName(id),
                    SeleniumWorkerCommand.label(id), containerId, SeleniumRuntimeIdentity.Stage.CREATED);
            resources.acquiredWorker(identity,() -> createdIdentityHook.accept(identity.containerId()));
            var inspected = runner.run(SeleniumWorkerCommand.inspectName(identity.workerName()),
                    limits.commandTimeout(), limits.maxOutputBytes());
            if (inspected.exitCode() != 0 || !(identity.containerId()+"|"+identity.executionLabel()).equals(inspected.output()))
                throw failure("WORKER_IDENTITY_FAILED");
            var attach = runner.attach(SeleniumWorkerCommand.attach(identity.workerName()));
            resources.acquiredAttach(attach);
            resources.stage(SeleniumRuntimeIdentity.Stage.STARTED);
            attach.awaitReady(id, limits.handshakeTimeout());
            resources.stage(SeleniumRuntimeIdentity.Stage.READY);
            resources.startupComplete();
            return resources.cleanup();
        } catch (RuntimeException failure) {
            resources.startupComplete();
            resources.cleanup();
            if (failure instanceof SeleniumEngineException engineFailure) throw engineFailure;
            throw failure("WORKER_STARTUP_FAILED");
        } finally { resources.startupComplete(); }
    }
    private static SeleniumEngineException failure(String code) {
        return new SeleniumEngineException(code, "Selenium containment failed");
    }
}
