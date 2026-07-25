package com.automationstudio.api.repository;

import java.time.OffsetDateTime;

public interface ExecutionHeartbeatRepository {

    OffsetDateTime currentDatabaseTime();
}
