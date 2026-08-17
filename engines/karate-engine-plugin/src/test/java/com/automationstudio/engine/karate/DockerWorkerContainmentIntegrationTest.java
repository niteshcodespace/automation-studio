package com.automationstudio.engine.karate;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named="automation.karate.container.tests",matches="true")
class DockerWorkerContainmentIntegrationTest {
    @Test void confinesWorkerLocalJavaAndProcessAuthority() throws Exception {
        String image=required("automation.karate.worker.image"),gatewayImage=required("automation.karate.gateway.image");UUID id=UUID.randomUUID();String suffix=id.toString().replace("-","");
        String network="as-karate-net-"+suffix,worker="as-karate-"+suffix,gateway="as-karate-gateway-"+suffix;Process attached=null,gatewayAttached=null;
        try {
            run(List.of("docker","network","create","--internal","--label","automation-studio.execution="+suffix,network));
            run(DockerGatewayCommand.create(gateway,gatewayImage,id,"http://localhost:1",System.currentTimeMillis()+120_000,1,WorkerLimits.defaults(),GatewayAddressPolicy.LOOPBACK_ONLY_TEST));
            run(DockerGatewayCommand.connect(network,gateway));run(DockerGatewayCommand.start(gateway));
            gatewayAttached=new ProcessBuilder(DockerGatewayCommand.attach(gateway)).redirectError(ProcessBuilder.Redirect.INHERIT).start();Process gatewayBroker=gatewayAttached;Thread.ofVirtual().start(()->serveNone(gatewayBroker));
            run(DockerWorkerCommand.create(worker,network,image,WorkerLimits.defaults()));
            String inspect=run(List.of("docker","inspect","-f","{{.Config.User}}|{{.Config.OpenStdin}}|{{.HostConfig.ReadonlyRootfs}}|{{.HostConfig.PidMode}}|{{.HostConfig.PidsLimit}}|{{.HostConfig.Privileged}}|{{json .HostConfig.CapDrop}}|{{json .HostConfig.SecurityOpt}}|{{json .Mounts}}",worker));
            assertTrue(inspect.contains("10001:10001|true|true||128|false|[\"ALL\"]"));assertTrue(inspect.contains("no-new-privileges:true"));assertTrue(inspect.contains("seccomp=builtin"));assertTrue(inspect.endsWith("|[]"));
            attached=new ProcessBuilder(DockerWorkerCommand.attach(worker)).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            byte[] source=feature(suffix).getBytes(StandardCharsets.UTF_8);OutputStream out=attached.getOutputStream();InputStream in=attached.getInputStream();
            send(out,WorkerProtocol.Message.of("HELLO",id));expect(in,"READY",id);
            assertEquals("2",run(List.of("docker","network","inspect","-f","{{len .Containers}}",network)));
            send(out,new WorkerProtocol.Message("SOURCE",id,Map.of("path","features/a.feature","size",String.valueOf(source.length),"digest",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source)),"content",Base64.getEncoder().encodeToString(source))));expect(in,"ACCEPTED_SOURCE",id);
            send(out,WorkerProtocol.Message.of("COMPLETE",id));expect(in,"COMPLETED_FOUNDATION_PROOF",id);
            send(out,new WorkerProtocol.Message("EXECUTE",id,Map.of("features",encoded(List.of("features/a.feature")),"includeTags",encoded(List.of()),"excludeTags",encoded(List.of()),"variables",encoded(List.of()),"gateway","http://as-karate-gateway-"+suffix+":8080/dispatch","parallelism","1")));
            WorkerProtocol.Message result=expect(in,"EXECUTION_RESULT",id);assertEquals("SUCCEEDED",result.fields().get("outcome"),result.fields().toString());
            send(out,WorkerProtocol.Message.of("SHUTDOWN",id));expect(in,"BYE",id);out.close();assertTrue(attached.waitFor(15,TimeUnit.SECONDS));assertEquals(0,attached.exitValue());
        } finally {
            if(attached!=null&&attached.isAlive())attached.destroyForcibly();if(gatewayAttached!=null&&gatewayAttached.isAlive())gatewayAttached.destroyForcibly();bestEffort(List.of("docker","rm","-f",worker));bestEffort(List.of("docker","rm","-f",gateway));bestEffort(List.of("docker","network","rm",network));
        }
        assertNotEquals(0,exit(List.of("docker","container","inspect",worker)));assertNotEquals(0,exit(List.of("docker","container","inspect",gateway)));assertNotEquals(0,exit(List.of("docker","network","inspect",network)));
    }
    private static String feature(String suffix){return """
            Feature: worker containment
            Scenario: runtime-local authority cannot escape
            * def Class = Java.type('java.lang.Class')
            * def Files = Java.type('java.nio.file.Files')
            * def Paths = Java.type('java.nio.file.Paths')
            * def System = Java.type('java.lang.System')
            * def ProcessHandle = Java.type('java.lang.ProcessHandle')
            * def Socket = Java.type('java.net.Socket')
            * def InetSocketAddress = Java.type('java.net.InetSocketAddress')
            * def platformAbsent = function(){ try { Class.forName('com.automationstudio.engine.sdk.ExecutionEnginePlugin'); return false } catch(e) { return true } }
            * def springAbsent = function(){ try { Class.forName('org.springframework.context.ApplicationContext'); return false } catch(e) { return true } }
            * def mutationDenied = function(){ try { Files.writeString(Paths.get('/work/source/features/a.feature'), 'replaced'); return false } catch(e) { return true } }
            * def deletionDenied = function(){ try { Files.delete(Paths.get('/work/source/features/a.feature')); return false } catch(e) { return true } }
            * def replacementDenied = function(){ try { Files.move(Paths.get('/work/source/features/a.feature'), Paths.get('/work/source/features/replaced.feature')); return false } catch(e) { return true } }
            * def gatewayReachable = function(){ var socket = new Socket(); try { socket.connect(new InetSocketAddress('as-karate-gateway-CORRELATION', 8080), 2000); return true } catch(e) { return false } finally { socket.close() } }
            * def directEgressDenied = function(){ var socket = new Socket(); try { socket.connect(new InetSocketAddress('1.1.1.1', 80), 1000); return false } catch(e) { return true } finally { socket.close() } }
            * match platformAbsent() == true
            * match springAbsent() == true
            * match mutationDenied() == true
            * match deletionDenied() == true
            * match replacementDenied() == true
            * match gatewayReachable() == true
            * match directEgressDenied() == true
            * eval Files.writeString(Paths.get('/work/runtime/probe'), 'ok')
            * match Files.readString(Paths.get('/work/runtime/probe')) == 'ok'
            * match Files.exists(Paths.get('/var/run/docker.sock')) == false
            * match Files.exists(Paths.get('/workspace')) == false
            * match System.getenv('HOME') == '/home/worker'
            * match System.getenv('AWS_ACCESS_KEY_ID') == null
            * match System.getenv('GOOGLE_APPLICATION_CREDENTIALS') == null
            * match System.getenv('DATABASE_URL') == null
            * match System.getenv('HTTP_PROXY') == null
            * match System.getenv('HTTPS_PROXY') == null
            * match ProcessHandle.current().pid() == 1
            * def identity = karate.exec('id')
            * match identity contains 'uid=10001'
            """.replace("CORRELATION",suffix);}
    private static void send(OutputStream out,WorkerProtocol.Message message)throws Exception{WorkerProtocol.write(out,message,1_048_576);}
    private static WorkerProtocol.Message expect(InputStream in,String type,UUID id)throws Exception{var value=WorkerProtocol.read(in,1_048_576);assertEquals(type,value.type());assertEquals(id,value.correlationId());return value;}
    private static String encoded(List<String> values)throws Exception{var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeInt(values.size());for(String value:values){byte[] b=value.getBytes(StandardCharsets.UTF_8);out.writeInt(b.length);out.write(b);}}return Base64.getEncoder().encodeToString(bytes.toByteArray());}
    private static String required(String name){String value=System.getProperty(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" is required");return value;}
    private static String run(List<String> command)throws Exception{Process p=new ProcessBuilder(command).redirectErrorStream(true).start();String output;try(var in=p.getInputStream()){output=new String(in.readAllBytes(),StandardCharsets.UTF_8).trim();}assertTrue(p.waitFor(Duration.ofSeconds(30).toMillis(),TimeUnit.MILLISECONDS),command.toString());assertEquals(0,p.exitValue(),output);return output;}
    private static int exit(List<String> command)throws Exception{Process p=new ProcessBuilder(command).redirectErrorStream(true).start();try(var in=p.getInputStream()){in.readAllBytes();}assertTrue(p.waitFor(15,TimeUnit.SECONDS));return p.exitValue();}
    private static void bestEffort(List<String> command){try{exit(command);}catch(Exception ignored){}}
    private static void serveNone(Process gateway){try(var in=new DataInputStream(gateway.getInputStream());var out=new DataOutputStream(gateway.getOutputStream())){brokerText(out,"NONE");brokerText(out,"");out.flush();while(in.readInt()==1){out.writeInt(0);out.writeInt(0);out.flush();}}catch(IOException ignored){}}
    private static void brokerText(DataOutputStream out,String value)throws IOException{byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
}
