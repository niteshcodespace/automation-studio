package com.automationstudio.engine.selenium;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** Explicit Linux-only executable qualification for the production helper transport. */
class D2cLinuxQualificationTest {
    @Test void productionTransportScenario() throws Exception {
        assumeTrue(Boolean.getBoolean("as.d2c.linuxQualification"));
        String scenario=System.getProperty("as.d2c.scenario");
        String digest="sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(Path.of(D2cNamespaceHelperLauncher.PATH))));
        var launcher=D2cNamespaceHelperLauncher.production(digest);var binding=binding(scenario,digest);
        var deadline=ContainmentDeadline.after(Duration.ofMillis(scenario.equals("timeout")?150:2_000));
        if(scenario.equals("interrupt")){
            var result=new java.util.concurrent.atomic.AtomicBoolean(true);Thread thread=Thread.ofPlatform().start(()->result.set(launcher.inspectCanonicalRoute(binding,ContainmentDeadline.after(Duration.ofSeconds(10)))));
            Thread.sleep(100);thread.interrupt();thread.join(3_000);assertFalse(thread.isAlive());assertFalse(result.get());return;
        }
        assertFalse(launcher.inspectCanonicalRoute(binding,deadline),scenario);
    }

    private static D2cRouteAuthority.Binding binding(String scenario,String digest)throws Exception{
        long pid=ProcessHandle.current().pid(),start=start(pid);Files.readAttributes(Path.of("/proc/"+pid+"/ns/net"),BasicFileAttributes.class);
        long dev=((Number)Files.getAttribute(Path.of("/proc/"+pid+"/ns/net"),"unix:dev")).longValue();long ino=((Number)Files.getAttribute(Path.of("/proc/"+pid+"/ns/net"),"unix:ino")).longValue();
        if(scenario.equals("pid-race"))start++;if(scenario.equals("netns-race"))ino++;
        String worker=Files.readString(Path.of("/proc/"+pid+"/cgroup")).lines().map(D2cLinuxQualificationTest::id).filter(v->v!=null).findFirst().orElse("a".repeat(64));
        var address=java.util.Collections.list(NetworkInterface.getNetworkInterfaces()).stream().filter(value->!"lo".equals(value.getName())).flatMap(value->value.getInterfaceAddresses().stream())
                .filter(value->value.getAddress() instanceof Inet4Address).findFirst().orElseThrow();String gateway=address.getAddress().getHostAddress();
        return new D2cRouteAuthority.Binding(worker,pid,start,dev,ino,"b".repeat(64),address.getAddress().getHostAddress(),gateway,
                address.getNetworkPrefixLength()>0?address.getAddress().getHostAddress().equals("127.0.0.1")?"lo":NetworkInterface.getByInetAddress(address.getAddress()).getName():"eth0",1,
                digest,new DockerControlPlane.DockerDaemonIdentity("unix:///qualification","qualification","qualification"));
    }
    private static long start(long pid)throws Exception{String value=Files.readString(Path.of("/proc/"+pid+"/stat"));return Long.parseLong(value.substring(value.lastIndexOf(')')+2).split(" ")[19]);}
    private static String id(String value){var match=java.util.regex.Pattern.compile("[a-f0-9]{64}").matcher(value);return match.find()?match.group():null;}
}
