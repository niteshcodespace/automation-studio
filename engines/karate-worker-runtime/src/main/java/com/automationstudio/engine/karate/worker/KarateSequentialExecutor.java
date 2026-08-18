package com.automationstudio.engine.karate.worker;

import com.intuit.karate.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Executes the admitted selection using Karate's bounded scenario scheduler. */
final class KarateSequentialExecutor {
    record ScenarioExecution(String identity,String name,int line,int exampleIndex,boolean failed){}
    record Result(String outcome,int features,int scenarios,int passed,int failed,String diagnostic,List<ScenarioExecution> scenarioResults){}
    Result execute(Path root,List<String> features,List<String> include,List<String> exclude,Map<String,String> variables,String gateway,UUID correlation,int parallelism){
        if(parallelism<1||parallelism>8||features.isEmpty()||features.size()>256||features.stream().anyMatch(p->!valid(p)||!Files.isRegularFile(root.resolve(p).normalize())))throw new IllegalArgumentException("INVALID_SELECTION");
        AtomicReference<String> gatewayFailure=new AtomicReference<>();
        try{
            List<String> paths=features.stream().map(p->"file:"+root.resolve(p).normalize()).toList();List<String> tags=new ArrayList<>(include);exclude.forEach(t->tags.add("~"+t));
            Set<String> expectedIdentities=ConcurrentHashMap.newKeySet();Map<String,ScenarioExecution> actualByIdentity=new ConcurrentHashMap<>();AtomicBoolean duplicateExpectedIdentity=new AtomicBoolean(),duplicateActualIdentity=new AtomicBoolean();AtomicInteger featureCount=new AtomicInteger();
            RuntimeHook variablesHook=new RuntimeHook(){@Override public boolean beforeFeature(com.intuit.karate.core.FeatureRuntime runtime){featureCount.incrementAndGet();return true;}@Override public boolean beforeScenario(com.intuit.karate.core.ScenarioRuntime runtime){if(!expectedIdentities.add(runtime.scenario.getUniqueId()))duplicateExpectedIdentity.set(true);runtime.engine.setVariables(new TreeMap<>(variables));return true;}@Override public void afterScenario(com.intuit.karate.core.ScenarioRuntime runtime){var scenario=runtime.result.getScenario();var execution=new ScenarioExecution(scenario.getUniqueId(),scenario.getName(),scenario.getLine(),scenario.getExampleIndex(),runtime.result.isFailed());if(actualByIdentity.putIfAbsent(execution.identity(),execution)!=null)duplicateActualIdentity.set(true);}};
            Path runtime=root.getParent().resolve("runtime");Files.createDirectories(runtime);
            var builder=Runner.path(paths).tags(tags).workingDir(root.toFile()).buildDir(runtime.toString()).reportDir(runtime.resolve("reports").toString()).outputHtmlReport(false).outputCucumberJson(false).outputJunitXml(false).backupReportDir(false).hook(variablesHook).clientFactory(engine->new GatewayHttpClient(gateway,correlation,gatewayFailure)).threads(parallelism);
            new Suite(builder).run();List<ScenarioExecution> scenarioResults=actualByIdentity.values().stream().sorted(Comparator.comparing(ScenarioExecution::identity)).toList();int failed=(int)scenarioResults.stream().filter(ScenarioExecution::failed).count(),passed=scenarioResults.size()-failed;
            if(duplicateExpectedIdentity.get()||duplicateActualIdentity.get()||!expectedIdentities.equals(actualByIdentity.keySet()))return new Result("ERROR",featureCount.get(),scenarioResults.size(),passed,failed,"KARATE_RESULT_IDENTITY_INVALID",scenarioResults);
            String gatewayCode=gatewayFailure.get();if(gatewayCode!=null)return new Result("ERROR",featureCount.get(),scenarioResults.size(),passed,failed,gatewayCode,scenarioResults);
            return new Result(failed==0?"SUCCEEDED":"FAILED",featureCount.get(),scenarioResults.size(),passed,failed,failed==0?"NONE":"KARATE_ASSERTION_FAILED",scenarioResults);
        }catch(Exception e){String gatewayCode=gatewayFailure.get();return new Result("ERROR",0,0,0,0,gatewayCode==null?"KARATE_RUNTIME_ERROR":gatewayCode,List.of());}
    }
    private static boolean valid(String p){if(p==null||p.isBlank()||p.startsWith("/")||p.indexOf('\\')>=0)return false;for(String s:p.split("/",-1))if(s.isBlank()||s.equals(".")||s.equals(".."))return false;return p.endsWith(".feature");}
}
