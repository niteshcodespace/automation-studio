package com.automationstudio.engine.sdk;

import java.util.UUID;

public interface WorkspaceAccess {

    UUID executionId();

    WorkspaceId workspaceId();

    PreparedSourceAccess openPreparedSource();
}
