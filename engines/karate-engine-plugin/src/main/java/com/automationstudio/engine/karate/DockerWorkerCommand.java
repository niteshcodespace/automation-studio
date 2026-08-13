package com.automationstudio.engine.karate;

import java.util.*;
import java.util.regex.Pattern;

final class DockerWorkerCommand {
    private static final Pattern DIGEST=Pattern.compile("^(?:[a-z0-9./_-]+(?::[a-zA-Z0-9._-]+)?@)?sha256:[a-f0-9]{64}$");
    private DockerWorkerCommand(){}
    static List<String> create(String name,String image,WorkerLimits l){
        if(!name.matches("^as-karate-[a-f0-9]{32}$")||!DIGEST.matcher(image).matches())throw new IllegalArgumentException("Invalid worker identity");
        return List.of("docker","create","--name",name,"--label","automation-studio.execution="+name.substring(10),"--network","none","--read-only","--user","10001:10001","--cap-drop","ALL","--security-opt","no-new-privileges:true","--pids-limit",String.valueOf(l.pids()),"--memory",String.valueOf(l.memoryBytes()),"--memory-swap",String.valueOf(l.memoryBytes()),"--cpus",String.valueOf(l.cpus()),"--tmpfs","/work:rw,noexec,nosuid,nodev,size="+l.tmpfsBytes(),"--env","LANG=C.UTF-8","--entrypoint","/opt/java/openjdk/bin/java",image,"-Xms16m","-Xmx512m","-Djava.io.tmpdir=/work/tmp","-jar","/opt/worker/worker.jar");
    }
    static List<String> attach(String name){return List.of("docker","start","--attach","--interactive",name);}
    static List<String> stop(String name,int seconds){return List.of("docker","stop","--time",String.valueOf(seconds),name);}
    static List<String> kill(String name){return List.of("docker","kill",name);}
    static List<String> remove(String name){return List.of("docker","rm","--force",name);}
    static List<String> inspect(String name){return List.of("docker","container","inspect",name);}
}
