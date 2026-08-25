package com.automationstudio.engine.selenium.worker;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SeleniumWorkerMainTest {
    @TempDir java.nio.file.Path temporary;
    @Test void emitsDeterministicReadyAndBye() throws Exception {
        UUID id = UUID.randomUUID(); ByteArrayOutputStream request = new ByteArrayOutputStream();
        DataOutputStream writer = new DataOutputStream(request);
        WorkerProtocol.write(writer, new WorkerProtocol.Message("HELLO", id));
        WorkerProtocol.write(writer, new WorkerProtocol.Message("SHUTDOWN", id));
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        SeleniumWorkerMain.serve(id, new DataInputStream(new ByteArrayInputStream(request.toByteArray())),
                new DataOutputStream(response), temporary);
        DataInputStream reader = new DataInputStream(new ByteArrayInputStream(response.toByteArray()));
        assertEquals(new WorkerProtocol.Message("READY", id), WorkerProtocol.read(reader));
        assertEquals(new WorkerProtocol.Message("BYE", id), WorkerProtocol.read(reader));
    }
    @Test void rejectsShutdownBeforeHelloAndWrongIdentityWithoutEchoingValues() throws Exception {
        UUID expected = UUID.randomUUID(), wrong = UUID.randomUUID();
        assertEquals("STATE_INVALID", invokeFailure(expected, "SHUTDOWN", expected).code());
        WorkerProtocolException failure = invokeFailure(expected, "HELLO", wrong);
        assertEquals("IDENTITY_MISMATCH", failure.code());
        assertFalse(failure.getMessage().contains(wrong.toString()));
    }
    private static WorkerProtocolException invokeFailure(UUID expected, String type, UUID sent) throws Exception {
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        WorkerProtocol.write(new DataOutputStream(request), new WorkerProtocol.Message(type, sent));
        return assertThrows(WorkerProtocolException.class, () -> SeleniumWorkerMain.serve(expected,
                new DataInputStream(new ByteArrayInputStream(request.toByteArray())),
                new DataOutputStream(new ByteArrayOutputStream())));
    }
}
