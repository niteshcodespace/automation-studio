package com.automationstudio.engine.sdk;

import java.io.InputStream;

public interface PreparedSourceAccess extends AutoCloseable {

    WorkspaceId workspaceId();

    InputStream open(String repositoryRelativePath);

    boolean isOpen();

    @Override
    void close();
}
