package com.automationstudio.engine.karate;

import com.automationstudio.engine.sdk.PreparedSourceAccess;
import com.automationstudio.engine.sdk.PreparedSourceEntry;
import com.automationstudio.engine.sdk.PreparedSourceEntryKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic, metadata-only discovery over the prepared-source boundary. */
public final class KarateFeatureDiscovery {

    public List<String> discover(PreparedSourceAccess source, KarateSuiteConfiguration configuration) {
        var features = new ArrayList<String>();
        var visited = new HashSet<String>();
        long[] aggregate = {0};
        walk(source, configuration, configuration.featureRoot(), 0, features, visited, aggregate);
        if (features.isEmpty()) throw failure("EMPTY_FEATURE_SELECTION", "Karate feature selection is empty");
        return List.copyOf(features);
    }

    /** All bounded regular files beneath the admitted root, for worker-local calls and read(). */
    public List<String> projectedFiles(PreparedSourceAccess source, KarateSuiteConfiguration configuration) {
        var files=new ArrayList<String>();var visited=new HashSet<String>();long[] aggregate={0};project(source,configuration,configuration.featureRoot(),0,files,visited,aggregate);return List.copyOf(files);
    }
    private void project(PreparedSourceAccess source,KarateSuiteConfiguration c,String directory,int depth,List<String> files,Set<String> visited,long[] aggregate){
        if(depth>c.maxDepth()||!visited.add(directory))throw failure("DISCOVERY_LIMIT_EXCEEDED","Karate source projection limit was exceeded");
        List<PreparedSourceEntry> entries;try{entries=source.list(directory,c.maxEntriesPerDirectory());}catch(RuntimeException e){throw failure("SOURCE_LISTING_FAILED","Prepared-source listing failed");}
        if(entries==null||entries.size()>c.maxEntriesPerDirectory())throw failure("DISCOVERY_LIMIT_EXCEEDED","Karate source projection limit was exceeded");String prefix=directory+"/";
        for(var entry:entries.stream().sorted().toList()){String path=entry.repositoryRelativePath(),remainder=path.startsWith(prefix)?path.substring(prefix.length()):"";if(remainder.isBlank()||remainder.contains("/"))throw failure("INVALID_SOURCE_ENTRY","Prepared-source listing returned an invalid entry");if(entry.kind()==PreparedSourceEntryKind.LINK)throw failure("SOURCE_LINK_DENIED","Prepared-source links are not permitted");if(entry.kind()==PreparedSourceEntryKind.UNSUPPORTED)throw failure("UNSUPPORTED_SOURCE_ENTRY","Prepared-source entry type is unsupported");if(entry.kind()==PreparedSourceEntryKind.DIRECTORY)project(source,c,path,depth+1,files,visited,aggregate);else{if(entry.sizeBytes()<0||entry.sizeBytes()>c.maxFeatureBytes())throw failure("SOURCE_SIZE_LIMIT_EXCEEDED","Karate source size limit was exceeded");aggregate[0]=Math.addExact(aggregate[0],entry.sizeBytes());if(aggregate[0]>c.maxAggregateBytes()||files.size()>=4096)throw failure("DISCOVERY_LIMIT_EXCEEDED","Karate source projection limit was exceeded");files.add(path);}}
    }

    private void walk(PreparedSourceAccess source, KarateSuiteConfiguration configuration,
            String directory, int depth, List<String> features, Set<String> visited, long[] aggregate) {
        if (depth > configuration.maxDepth() || !visited.add(directory)) {
            throw failure("DISCOVERY_LIMIT_EXCEEDED", "Karate feature discovery limit was exceeded");
        }
        List<PreparedSourceEntry> entries;
        try {
            entries = source.list(directory, configuration.maxEntriesPerDirectory());
        } catch (UnsupportedOperationException exception) {
            throw failure("SOURCE_LISTING_UNAVAILABLE", "Prepared-source listing is unavailable");
        } catch (RuntimeException exception) {
            throw failure("SOURCE_LISTING_FAILED", "Prepared-source listing failed");
        }
        if (entries == null || entries.size() > configuration.maxEntriesPerDirectory()) {
            throw failure("DISCOVERY_LIMIT_EXCEEDED", "Karate feature discovery limit was exceeded");
        }
        String prefix = directory + "/";
        for (PreparedSourceEntry entry : entries.stream().sorted().toList()) {
            String path = entry.repositoryRelativePath();
            String remainder = path.startsWith(prefix) ? path.substring(prefix.length()) : "";
            if (remainder.isBlank() || remainder.contains("/")) {
                throw failure("INVALID_SOURCE_ENTRY", "Prepared-source listing returned an invalid entry");
            }
            if (entry.kind() == PreparedSourceEntryKind.LINK) {
                throw failure("SOURCE_LINK_DENIED", "Prepared-source links are not permitted");
            }
            if (entry.kind() == PreparedSourceEntryKind.UNSUPPORTED) {
                throw failure("UNSUPPORTED_SOURCE_ENTRY", "Prepared-source entry type is unsupported");
            }
            if (entry.kind() == PreparedSourceEntryKind.DIRECTORY) {
                walk(source, configuration, path, depth + 1, features, visited, aggregate);
            } else if (path.endsWith(".feature")) {
                if (entry.sizeBytes() < 0 || entry.sizeBytes() > configuration.maxFeatureBytes()) {
                    throw failure("FEATURE_SIZE_LIMIT_EXCEEDED", "Karate feature size limit was exceeded");
                }
                aggregate[0] += entry.sizeBytes();
                if (aggregate[0] > configuration.maxAggregateBytes()
                        || features.size() >= configuration.maxFeatures()) {
                    throw failure("DISCOVERY_LIMIT_EXCEEDED", "Karate feature discovery limit was exceeded");
                }
                features.add(path);
            }
        }
    }

    private static KarateEngineException failure(String code, String message) {
        return new KarateEngineException(code, message);
    }
}
