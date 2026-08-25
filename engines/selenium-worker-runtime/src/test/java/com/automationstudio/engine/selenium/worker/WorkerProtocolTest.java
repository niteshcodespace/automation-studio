package com.automationstudio.engine.selenium.worker;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkerProtocolTest {
    @Test void roundTripsOnlyFixedMessages() throws Exception {
        UUID id = UUID.randomUUID(); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        WorkerProtocol.write(new DataOutputStream(bytes), new WorkerProtocol.Message("HELLO", id));
        assertEquals(new WorkerProtocol.Message("HELLO", id),
                WorkerProtocol.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
    }
    @Test void rejectsMalformedUnknownFieldUnknownTypeAndOversizedFrames() throws Exception {
        assertEquals("FRAME_MALFORMED", failure(frame("RUN|" + UUID.randomUUID())).code());
        assertEquals("FRAME_MALFORMED", failure(frame("HELLO|" + UUID.randomUUID() + "|extra")).code());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new DataOutputStream(bytes).writeInt(WorkerProtocol.MAX_FRAME_BYTES + 1);
        assertEquals("FRAME_SIZE_INVALID", failure(bytes.toByteArray()).code());
        assertEquals("FRAME_MALFORMED", failure(frame("HELLO|not-a-uuid")).code());
    }
    private static WorkerProtocolException failure(byte[] bytes) {
        return assertThrows(WorkerProtocolException.class, () -> WorkerProtocol.read(
                new DataInputStream(new ByteArrayInputStream(bytes))));
    }
    private static byte[] frame(String value) throws IOException {
        byte[] content = value.getBytes(StandardCharsets.UTF_8); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes); output.writeInt(content.length); output.write(content); return bytes.toByteArray();
    }
}
