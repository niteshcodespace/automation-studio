package com.automationstudio.engine.karate.gateway;

import java.net.*;
import java.util.*;

/** Exact-origin authorization with fail-closed special-address validation. */
final class GatewayTargetPolicy {
    interface Resolver { InetAddress[] resolve(String host) throws UnknownHostException; }
    enum AddressPolicy { GLOBAL_ONLY, LOOPBACK_ONLY_TEST }
    private final Origin admitted; private final AddressPolicy addressPolicy; private final Resolver resolver;
    GatewayTargetPolicy(String baseUrl, AddressPolicy addressPolicy) { this(baseUrl, addressPolicy, InetAddress::getAllByName); }
    GatewayTargetPolicy(String baseUrl, AddressPolicy addressPolicy, Resolver resolver) {
        this.admitted=Origin.from(parse(baseUrl)); this.addressPolicy=Objects.requireNonNull(addressPolicy); this.resolver=Objects.requireNonNull(resolver);
    }
    Authorized authorize(String raw) {
        URI uri=parse(raw); Origin origin=Origin.from(uri); if(!origin.equals(admitted)) fail("GATEWAY_TARGET_DENIED");
        try {
            InetAddress[] answers=resolver.resolve(origin.host());
            if(answers.length==0) fail("GATEWAY_DNS_DENIED");
            boolean valid=addressPolicy==AddressPolicy.GLOBAL_ONLY?Arrays.stream(answers).allMatch(GatewayTargetPolicy::global):Arrays.stream(answers).allMatch(InetAddress::isLoopbackAddress);
            if(!valid) fail("GATEWAY_ADDRESS_DENIED");
            return new Authorized(uri,origin.host(),List.copyOf(Arrays.asList(answers)));
        } catch(UnknownHostException e) { throw new GatewayFailure("GATEWAY_DNS_DENIED"); }
    }
    private static URI parse(String raw) {
        try {
            URI u=new URI(raw).normalize(); String scheme=u.getScheme()==null?"":u.getScheme().toLowerCase(Locale.ROOT);
            if(!Set.of("http","https").contains(scheme)||u.getHost()==null||u.getRawUserInfo()!=null||u.getRawFragment()!=null||u.getRawAuthority()==null||u.getRawAuthority().contains("%")||u.getPort()==0||u.getPort()>65535) fail("GATEWAY_TARGET_INVALID");
            String host=IDN.toASCII(u.getHost(),IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if(host.endsWith(".")||ambiguousNumeric(host)) fail("GATEWAY_TARGET_INVALID");
            String path=u.getRawPath()==null||u.getRawPath().isEmpty()?"/":u.getRawPath();
            return new URI(scheme,null,host,u.getPort(),path,u.getRawQuery(),null);
        } catch(URISyntaxException|IllegalArgumentException e) { if(e instanceof GatewayFailure g) throw g; throw new GatewayFailure("GATEWAY_TARGET_INVALID"); }
    }
    private static boolean ambiguousNumeric(String host) {
        if(host.matches("[0-9]+")||host.matches("(?i)0x[0-9a-f]+"))return true;
        if(host.matches("[0-9.]+")){String[] p=host.split("\\.",-1);if(p.length!=4)return true;for(String s:p)try{if(s.isEmpty()||(s.length()>1&&s.startsWith("0"))||Integer.parseInt(s)>255)return true;}catch(NumberFormatException e){return true;}}
        return false;
    }
    static boolean global(InetAddress a) {
        if(a.isAnyLocalAddress()||a.isLoopbackAddress()||a.isLinkLocalAddress()||a.isSiteLocalAddress()||a.isMulticastAddress())return false;
        byte[] b=a.getAddress();
        if(a instanceof Inet4Address){int x=b[0]&255,y=b[1]&255,z=b[2]&255;return !(x==0||x==10||x==127||(x==100&&y>=64&&y<=127)||(x==169&&y==254)||(x==172&&y>=16&&y<=31)||(x==192&&y==0)||(x==192&&y==31&&z==196)||(x==192&&y==52&&z==193)||(x==192&&y==88&&z==99)||(x==192&&y==168)||(x==198&&(y==18||y==19))||(x==198&&y==51&&z==100)||(x==203&&y==0&&z==113)||x>=224);}
        if(a instanceof Inet6Address){int x=b[0]&255;return (x&0xe0)==0x20&&!prefix(b,new int[]{0x20,1,0x0d,0xb8},32)&&!prefix(b,new int[]{0x20,1,0,2},48)&&!prefix(b,new int[]{0x20,1,0},23);}
        return false;
    }
    private static boolean prefix(byte[] a,int[] p,int bits){for(int i=0;i<bits/8;i++)if((a[i]&255)!=p[i])return false;return true;}
    private static void fail(String code){throw new GatewayFailure(code);}
    record Origin(String scheme,String host,int port){static Origin from(URI u){return new Origin(u.getScheme().toLowerCase(Locale.ROOT),u.getHost().toLowerCase(Locale.ROOT),u.getPort()<0?(u.getScheme().equalsIgnoreCase("https")?443:80):u.getPort());}}
    record Authorized(URI uri,String host,List<InetAddress> addresses){}
    static final class GatewayFailure extends RuntimeException { private final String code; GatewayFailure(String code){super(code);this.code=code;} String code(){return code;} }
}
