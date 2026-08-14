package com.automationstudio.engine.karate;
import java.util.*;import java.util.regex.Pattern;
final class DockerGatewayCommand{
 private static final Pattern DIGEST=Pattern.compile("^(?:[a-z0-9./_-]+(?::[a-zA-Z0-9._-]+)?@)?sha256:[a-f0-9]{64}$");
 static List<String> networkCreate(String n,String id){validNetwork(n);return List.of("docker","network","create","--internal","--label","automation-studio.execution="+id,n);}
 static List<String> networkRemove(String n){validNetwork(n);return List.of("docker","network","rm",n);}
 static List<String> create(String name,String image,UUID id,String base,long deadline,WorkerLimits l,boolean testNonGlobal){if(!name.matches("^as-karate-gateway-[a-f0-9]{32}$")||!DIGEST.matcher(image).matches())throw new IllegalArgumentException("Invalid gateway identity");return List.of("docker","create","--name",name,"--label","automation-studio.execution="+id.toString().replace("-",""),"--network","bridge","--read-only","--user","10002:10002","--cap-drop","ALL","--security-opt","no-new-privileges:true","--security-opt","seccomp=builtin","--pids-limit","64","--memory","268435456","--memory-swap","268435456","--cpus","1.0","--tmpfs","/work:rw,noexec,nosuid,nodev,uid=10002,gid=10002,mode=0700,size=16777216","--env","LANG=C.UTF-8",image,id.toString(),base,String.valueOf(deadline),testNonGlobal?"test-loopback":"production");}
 static List<String> connect(String network,String gateway){validNetwork(network);return List.of("docker","network","connect","--alias",gateway,network,gateway);}
 static List<String> start(String name){return List.of("docker","start",name);}
 static List<String> stop(String name,int seconds){return List.of("docker","stop","--time",String.valueOf(seconds),name);}
 static List<String> kill(String name){return List.of("docker","kill",name);}
 static List<String> remove(String name){return List.of("docker","rm","--force",name);}
 static List<String> inspect(String name){return List.of("docker","container","inspect",name);}
 private static void validNetwork(String n){if(!n.matches("^as-karate-net-[a-f0-9]{32}$"))throw new IllegalArgumentException("Invalid network identity");}
 private DockerGatewayCommand(){}
}
