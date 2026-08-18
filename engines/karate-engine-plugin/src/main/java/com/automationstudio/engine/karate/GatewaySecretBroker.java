package com.automationstudio.engine.karate;

import com.automationstudio.engine.sdk.ExecutionSecretAccess;
import com.automationstudio.engine.sdk.ResolvedSecret;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Provider-owned, execution-local broker. Logical references and secret values never enter worker IPC. */
final class GatewaySecretBroker {
    private static final int REQUEST=1,STOP=2,OK=0,FAILED=1,MAX=65_536;
    private final ExecutionSecretAccess access;private final KarateSuiteConfiguration configuration;
    GatewaySecretBroker(ExecutionSecretAccess access,KarateSuiteConfiguration configuration){this.access=Objects.requireNonNull(access);this.configuration=Objects.requireNonNull(configuration);}
    void serve(Process gateway)throws IOException{try(var in=new DataInputStream(new BufferedInputStream(gateway.getInputStream()));var out=new DataOutputStream(new BufferedOutputStream(gateway.getOutputStream()))){var auth=configuration.authentication();text(out,auth.type().name());text(out,auth.placement()==null?"":auth.placement());out.flush();while(true){int operation;try{operation=in.readInt();}catch(EOFException e){return;}if(operation==STOP)return;if(operation!=REQUEST)throw new IOException();materialize(out);}}}
    void materialize(DataOutputStream out)throws IOException{var auth=configuration.authentication();List<ResolvedSecret> opened=new ArrayList<>();List<char[]> values=new ArrayList<>();try{if(auth.type()!=KarateSuiteConfiguration.Authentication.Type.NONE){values.add(resolve(auth.secretRef(),opened));if(auth.type()==KarateSuiteConfiguration.Authentication.Type.BASIC)values.add(0,resolve(auth.usernameSecretRef(),opened));}out.writeInt(OK);out.writeInt(values.size());for(char[] value:values)text(out,new String(value));out.flush();}catch(RuntimeException e){out.writeInt(FAILED);text(out,"SECRET_RESOLUTION_FAILED");out.flush();}finally{values.forEach(value->Arrays.fill(value,'\0'));for(int i=opened.size()-1;i>=0;i--)opened.get(i).close();}}
    private char[] resolve(String alias,List<ResolvedSecret> opened){String logical=configuration.secretReferences().get(alias);ResolvedSecret secret=access.resolve(logical);if(secret==null)throw new IllegalStateException();opened.add(secret);final char[][] value={null};secret.withValue(chars->{if(chars.length==0||chars.length>MAX)throw new IllegalArgumentException();for(char c:chars)if(c<0x20||c==0x7f)throw new IllegalArgumentException();value[0]=Arrays.copyOf(chars,chars.length);});return value[0];}
    private static void text(DataOutputStream out,String value)throws IOException{byte[] bytes=value.getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX)throw new IOException();out.writeInt(bytes.length);out.write(bytes);}
}
