package com.automationstudio.engine.karate;

import java.time.Duration;

record WorkerLimits(Duration wallTime, Duration stopGrace, int maxFrameBytes, long maxAggregateInputBytes,
        long maxStdoutBytes, long maxStderrBytes, long memoryBytes, double cpus, int pids, long tmpfsBytes) {
    static WorkerLimits defaults(){return new WorkerLimits(Duration.ofMinutes(30),Duration.ofSeconds(2),1_048_576,40L*1024*1024,1_048_576,1_048_576,768L*1024*1024,1.0,128,64L*1024*1024);}
    WorkerLimits {if(wallTime==null||wallTime.isNegative()||wallTime.isZero()||stopGrace==null||stopGrace.isNegative()||maxFrameBytes<1024||maxAggregateInputBytes<maxFrameBytes||maxStdoutBytes<1024||maxStderrBytes<1024||memoryBytes<64L*1024*1024||cpus<=0||pids<2||tmpfsBytes<1024)throw new IllegalArgumentException("Invalid worker limits");}
}
