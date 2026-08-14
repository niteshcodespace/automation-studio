package com.automationstudio.engine.karate.worker;

import com.intuit.karate.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

/** Executes the admitted selection with an effective scenario concurrency of exactly one. */
final class KarateSequentialExecutor {
    record Result(String outcome,int features,int scenarios,int passed,int failed,String diagnostic){}
    Result execute(Path root,List<String> features,List<String> include,List<String> exclude,Map<String,String> variables,String gateway,UUID correlation){
        if(features.isEmpty()||features.size()>256||features.stream().anyMatch(p->!valid(p)||!Files.isRegularFile(root.resolve(p).normalize())))throw new IllegalArgumentException("INVALID_SELECTION");
        AtomicReference<String> gatewayFailure=new AtomicReference<>();
        try{
            List<String> paths=features.stream().map(p->"file:"+root.resolve(p).normalize()).toList();List<String> tags=new ArrayList<>(include);exclude.forEach(t->tags.add("~"+t));
            AtomicInteger featureCount=new AtomicInteger(),scenarioCount=new AtomicInteger(),passed=new AtomicInteger(),failed=new AtomicInteger();
            RuntimeHook variablesHook=new RuntimeHook(){@Override public boolean beforeFeature(com.intuit.karate.core.FeatureRuntime runtime){featureCount.incrementAndGet();return true;}@Override public boolean beforeScenario(com.intuit.karate.core.ScenarioRuntime runtime){runtime.engine.setVariables(new TreeMap<>(variables));return true;}@Override public void afterScenario(com.intuit.karate.core.ScenarioRuntime runtime){scenarioCount.incrementAndGet();if(runtime.isFailed())failed.incrementAndGet();else passed.incrementAndGet();}};
            Path runtime=root.getParent().resolve("runtime");Files.createDirectories(runtime);
            var builder=Runner.path(paths).tags(tags).workingDir(root.toFile()).buildDir(runtime.toString()).reportDir(runtime.resolve("reports").toString()).outputHtmlReport(false).outputCucumberJson(false).outputJunitXml(false).backupReportDir(false).hook(variablesHook).clientFactory(engine->new GatewayHttpClient(gateway,correlation,gatewayFailure)).threads(1);
            new Suite(builder).run();
            String gatewayCode=gatewayFailure.get();if(gatewayCode!=null)return new Result("ERROR",featureCount.get(),scenarioCount.get(),passed.get(),failed.get(),gatewayCode);
            return new Result(failed.get()==0?"SUCCEEDED":"FAILED",featureCount.get(),scenarioCount.get(),passed.get(),failed.get(),failed.get()==0?"NONE":"KARATE_ASSERTION_FAILED");
        }catch(Exception e){String gatewayCode=gatewayFailure.get();return new Result("ERROR",0,0,0,0,gatewayCode==null?"KARATE_RUNTIME_ERROR":gatewayCode);}
    }
    private static boolean valid(String p){if(p==null||p.isBlank()||p.startsWith("/")||p.indexOf('\\')>=0)return false;for(String s:p.split("/",-1))if(s.isBlank()||s.equals(".")||s.equals(".."))return false;return p.endsWith(".feature");}
}
