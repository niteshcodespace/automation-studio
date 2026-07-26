package com.automationstudio.api.service;

import com.automationstudio.api.service.command.RenewExecutionLeaseCommand;
import com.automationstudio.api.service.result.RenewedExecutionLease;

public interface ExecutionHeartbeatService {

    RenewedExecutionLease renew(RenewExecutionLeaseCommand command);
}
