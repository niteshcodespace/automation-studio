package com.automationstudio.engine.selenium.worker;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Path;
import java.util.UUID;

public final class SeleniumWorkerMain {
    public static void main(String[] args) {
        if (args.length != 1) System.exit(2);
        try { serve(UUID.fromString(args[0]), new DataInputStream(System.in), new DataOutputStream(System.out)); }
        catch (Exception failure) { System.exit(2); }
    }
    static void serve(UUID expected, DataInputStream input, DataOutputStream output) throws Exception {
        serve(expected, input, output, Path.of("/work/runtime"));
    }
    static void serve(UUID expected, DataInputStream input, DataOutputStream output, Path runtimeBase)
            throws Exception {
        WorkerProtocol.Message hello = WorkerProtocol.read(input);
        require(hello, "HELLO", expected);
        try (WorkerWorkspace ignored = WorkerWorkspace.create(runtimeBase, expected);
                WorkerProcessSupervisor supervisor = new WorkerProcessSupervisor(ProcessHandle.current())) {
            WorkerIdentity.current(expected);
            WorkerProtocol.write(output, new WorkerProtocol.Message("READY", expected));
            WorkerProtocol.Message shutdown = WorkerProtocol.read(input);
            require(shutdown, "SHUTDOWN", expected);
            WorkerProtocol.write(output, new WorkerProtocol.Message("BYE", expected));
        }
    }
    private static void require(WorkerProtocol.Message message, String type, UUID expected)
            throws WorkerProtocolException {
        if (!type.equals(message.type())) throw new WorkerProtocolException("STATE_INVALID");
        if (!expected.equals(message.executionId())) throw new WorkerProtocolException("IDENTITY_MISMATCH");
    }
    private SeleniumWorkerMain() {}
}
