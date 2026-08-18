package com.automationstudio.engine.sdk;

import java.io.InputStream;
import java.util.List;

public interface PreparedSourceAccess extends AutoCloseable {

    WorkspaceId workspaceId();

    InputStream open(String repositoryRelativePath);

    /**
     * Lists one directory without recursion. The provider must return no more than
     * {@code maxEntries}, sort by logical path, and never follow links.
     *
     * <p>The default preserves compatibility for providers that have not advertised structural
     * discovery. An engine requiring listing must fail closed when it is unavailable.</p>
     */
    default List<PreparedSourceEntry> list(String repositoryRelativeDirectory, int maxEntries) {
        throw new UnsupportedOperationException("Prepared source listing is unavailable");
    }

    boolean isOpen();

    @Override
    void close();
}
