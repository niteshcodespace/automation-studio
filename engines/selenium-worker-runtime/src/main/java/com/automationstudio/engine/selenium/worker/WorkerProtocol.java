package com.automationstudio.engine.selenium.worker;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

final class WorkerProtocol {
    static final int MAX_FRAME_BYTES = 256;
    private static final Set<String> TYPES = Set.of("HELLO", "READY", "SHUTDOWN", "BYE");

    record Message(String type, UUID executionId) {
        Message {
            if (!TYPES.contains(type) || executionId == null) throw new IllegalArgumentException("Invalid message");
        }
    }

    static Message read(DataInputStream input) throws IOException, WorkerProtocolException {
        int length;
        try { length = input.readInt(); } catch (EOFException failure) { throw failure; }
        if (length < 1 || length > MAX_FRAME_BYTES) throw new WorkerProtocolException("FRAME_SIZE_INVALID");
        byte[] content = input.readNBytes(length);
        if (content.length != length) throw new WorkerProtocolException("FRAME_MALFORMED");
        String[] fields = new String(content, StandardCharsets.UTF_8).split("\\|", -1);
        if (fields.length != 2 || !TYPES.contains(fields[0])) throw new WorkerProtocolException("FRAME_MALFORMED");
        try { return new Message(fields[0], UUID.fromString(fields[1])); }
        catch (IllegalArgumentException failure) { throw new WorkerProtocolException("FRAME_MALFORMED"); }
    }

    static void write(DataOutputStream output, Message message) throws IOException {
        byte[] content = (message.type() + "|" + message.executionId()).getBytes(StandardCharsets.UTF_8);
        if (content.length > MAX_FRAME_BYTES) throw new IOException("Frame unavailable");
        output.writeInt(content.length); output.write(content); output.flush();
    }

    private WorkerProtocol() {}
}
