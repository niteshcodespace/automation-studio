package com.automationstudio.engine.karate;

import java.util.*;
import java.util.regex.Pattern;

final class DockerWorkerCommand {
    private static final Pattern DIGEST=Pattern.compile("^(?:[a-z0-9./_-]+(?::[a-zA-Z0-9._-]+)?@)?sha256:[a-f0-9]{64}$");
    private DockerWorkerCommand(){}
    static List<String> create(String name,String network,String image,WorkerLimits l){
        if(!name.matches("^as-karate-[a-f0-9]{32}$")||!network.matches("^as-karate-net-[a-f0-9]{32}$")||!DIGEST.matcher(image).matches())throw new IllegalArgumentException("Invalid worker identity");
        long source=l.tmpfsBytes()/2,runtime=l.tmpfsBytes()*3/8,tmp=l.tmpfsBytes()-source-runtime;
        return List.of("docker","create","--interactive","--name",name,"--label","automation-studio.execution="+name.substring(10),"--network",network,"--read-only","--user","10001:10001","--cap-drop","ALL","--security-opt","no-new-privileges:true","--security-opt","seccomp=builtin","--pids-limit",String.valueOf(l.pids()),"--memory",String.valueOf(l.memoryBytes()),"--memory-swap",String.valueOf(l.memoryBytes()),"--cpus",String.valueOf(l.cpus()),"--tmpfs",tmpfs("/work/source",source),"--tmpfs",tmpfs("/work/runtime",runtime),"--tmpfs",tmpfs("/work/tmp",tmp),"--env","LANG=C.UTF-8","--entrypoint","/opt/java/openjdk/bin/java",image,"-Xms16m","-Xmx512m","-Djava.io.tmpdir=/work/tmp","-jar","/opt/worker/worker.jar");
    }
    private static String tmpfs(String path,long bytes){return path+":rw,noexec,nosuid,nodev,uid=10001,gid=10001,mode=0700,size="+bytes;}
    static List<String> attach(String name){return List.of("docker","start","--attach","--interactive",name);}
    static List<String> stop(String name,int seconds){return List.of("docker","stop","--time",String.valueOf(seconds),name);}
    static List<String> kill(String name){return List.of("docker","kill",name);}
    static List<String> remove(String name){return List.of("docker","rm","--force",name);}
    static List<String> inspect(String name){return List.of("docker","container","inspect",name);}
}
