package com.automationstudio.api.execution.artifact.storage;

import java.io.IOException;
import java.io.OutputStream;

@FunctionalInterface
public interface ArtifactStorageContent {

    void writeTo(OutputStream output) throws IOException;
}
