package com.automationstudio.engine.karate;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable, provider-local configuration accepted for structural preparation. */
public record KarateSuiteConfiguration(
        String featureRoot, List<String> includeTags, List<String> excludeTags,
        Map<String, String> variables, Map<String, String> secretReferences,
        Authentication authentication, int parallelism,
        int maxFeatures, int maxDepth, int maxEntriesPerDirectory,
        long maxFeatureBytes, long maxAggregateBytes) {

    Map<String,String> composeVariables(Map<String,String> executionVariables) {
        if(executionVariables==null)throw failure("INVALID_BINDINGS","Karate bindings are invalid");var result=new java.util.TreeMap<>(variables);
        for(var entry:executionVariables.entrySet()){if(entry.getKey()==null||!NAME.matcher(entry.getKey()).matches()||RESERVED.matcher(entry.getKey()).matches()||entry.getValue()==null||entry.getValue().isBlank()||entry.getValue().length()>1024||entry.getValue().indexOf('\0')>=0||result.putIfAbsent(entry.getKey(),entry.getValue())!=null)throw failure("INVALID_BINDINGS","Karate bindings are invalid");}
        if(result.size()>64||result.entrySet().stream().mapToLong(e->e.getKey().length()+e.getValue().length()).sum()>65536)throw failure("INVALID_BINDINGS","Karate bindings are invalid");return Map.copyOf(result);
    }

    private static final Set<String> FIELDS = Set.of("schemaVersion", "featureRoot", "includeTags",
            "excludeTags", "variables", "secretReferences", "authentication", "parallelism", "limits");
    private static final Set<String> LIMIT_FIELDS = Set.of("maxFeatures", "maxDepth",
            "maxEntriesPerDirectory", "maxFeatureBytes", "maxAggregateBytes");
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*");
    private static final Pattern TAG = Pattern.compile("@[A-Za-z0-9][A-Za-z0-9_.:-]{0,62}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");
    private static final Pattern RESERVED = Pattern.compile("(?i)(java|class|system|env|path|proxy|tls|process|command)([._-].*)?");

    public static KarateSuiteConfiguration parse(Map<String, Object> values) {
        if (values == null || !FIELDS.containsAll(values.keySet()) || !"1".equals(values.get("schemaVersion"))) {
            throw failure("INVALID_CONFIGURATION", "Karate suite configuration is invalid");
        }
        String root = string(values.get("featureRoot"), 256);
        if (!PATH.matcher(root).matches() || List.of(root.split("/", -1)).stream()
                .anyMatch(segment -> segment.equals(".") || segment.equals(".."))) {
            throw failure("INVALID_FEATURE_ROOT", "Karate feature root is invalid");
        }
        List<String> include = tags(values.get("includeTags"));
        List<String> exclude = tags(values.get("excludeTags"));
        if (include.stream().anyMatch(exclude::contains)) {
            throw failure("AMBIGUOUS_TAG_SELECTION", "Karate tag selection is ambiguous");
        }
        Map<String, String> variables = names(values.get("variables"), 64, 1024, true);
        Map<String, String> secrets = secretNames(values.get("secretReferences"));
        Authentication authentication = Authentication.parse(values.get("authentication"), secrets.keySet());
        int parallelism = parallelism(values.get("parallelism"));
        Map<String, Object> limits = objectMap(values.get("limits"));
        if (!LIMIT_FIELDS.containsAll(limits.keySet())) throw failure("INVALID_LIMITS", "Karate limits are invalid");
        int features = integer(limits, "maxFeatures", 256, 1, 256);
        int depth = integer(limits, "maxDepth", 16, 1, 32);
        int entries = integer(limits, "maxEntriesPerDirectory", 512, 1, 4096);
        long featureBytes = integer(limits, "maxFeatureBytes", 1_048_576, 1, 1_048_576);
        long aggregateBytes = integer(limits, "maxAggregateBytes", 33_554_432, 1, 33_554_432);
        if (aggregateBytes < featureBytes) throw failure("INVALID_LIMITS", "Karate limits are invalid");
        return new KarateSuiteConfiguration(root, include, exclude, variables, secrets, authentication, parallelism,
                features, depth, entries, featureBytes, aggregateBytes);
    }

    record Authentication(Type type,String secretRef,String usernameSecretRef,String placement){
        enum Type{NONE,BEARER,BASIC,API_KEY_HEADER,API_KEY_QUERY}
        private static final Set<String> FIELDS=Set.of("type","secretRef","usernameSecretRef","placement");
        private static final Pattern PLACEMENT=Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
        static Authentication parse(Object raw,Set<String> references){if(raw==null)return new Authentication(Type.NONE,null,null,null);Map<String,Object> map=objectMap(raw);if(!FIELDS.containsAll(map.keySet()))throw failure("AUTH_CONFIGURATION_INVALID","Karate authentication configuration is invalid");Type type;try{type=Type.valueOf(string(map.get("type"),32).toUpperCase(java.util.Locale.ROOT));}catch(RuntimeException e){throw failure("AUTH_CONFIGURATION_INVALID","Karate authentication configuration is invalid");}String secret=optional(map.get("secretRef")),username=optional(map.get("usernameSecretRef")),placement=optional(map.get("placement"));boolean valid=switch(type){case NONE->secret==null&&username==null&&placement==null;case BEARER->reference(secret,references)&&username==null&&placement==null;case BASIC->reference(secret,references)&&reference(username,references)&&placement==null&&!secret.equals(username);case API_KEY_HEADER,API_KEY_QUERY->reference(secret,references)&&username==null&&placement!=null&&PLACEMENT.matcher(placement).matches();};if(!valid||(type==Type.API_KEY_HEADER&&reservedHeader(placement)))throw failure("AUTH_CONFIGURATION_INVALID","Karate authentication configuration is invalid");return new Authentication(type,secret,username,placement);}
        private static boolean reference(String value,Set<String> references){return value!=null&&references.contains(value);}
        private static String optional(Object value){return value==null?null:string(value,256);}
        private static boolean reservedHeader(String value){return Set.of("host","connection","proxy-connection","proxy-authorization","cookie","transfer-encoding","upgrade","forwarded").contains(value.toLowerCase(java.util.Locale.ROOT));}
    }

    private static List<String> tags(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list) || list.size() > 32) throw failure("INVALID_TAGS", "Karate tags are invalid");
        List<String> tags = list.stream().map(item -> string(item, 64)).toList();
        if (tags.stream().anyMatch(tag -> !TAG.matcher(tag).matches()) || new HashSet<>(tags).size() != tags.size()) {
            throw failure("INVALID_TAGS", "Karate tags are invalid");
        }
        return tags;
    }

    private static Map<String, String> names(Object value, int maximum, int valueLength, boolean reserved) {
        if (value == null) return Map.of();
        Map<String, Object> map = objectMap(value);
        if (map.size() > maximum) throw failure("INVALID_BINDINGS", "Karate bindings are invalid");
        var result = new java.util.TreeMap<String, String>();
        map.forEach((name, raw) -> {
            if (!NAME.matcher(name).matches() || (reserved && RESERVED.matcher(name).matches())) {
                throw failure("INVALID_BINDINGS", "Karate bindings are invalid");
            }
            result.put(name, string(raw, valueLength));
        });
        return Map.copyOf(result);
    }

    private static Map<String,String> secretNames(Object value){if(value==null)return Map.of();Map<String,Object> map=objectMap(value);if(map.size()>32)throw failure("SECRET_REFERENCE_INVALID","Karate secret references are invalid");var result=new java.util.TreeMap<String,String>();map.forEach((name,raw)->{if(!NAME.matcher(name).matches()||RESERVED.matcher(name).matches())throw failure("SECRET_REFERENCE_INVALID","Karate secret references are invalid");try{result.put(name,string(raw,256));}catch(KarateEngineException e){throw failure("SECRET_REFERENCE_INVALID","Karate secret references are invalid");}});return Map.copyOf(result);}

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw failure("INVALID_CONFIGURATION", "Karate suite configuration is invalid");
        }
        return (Map<String, Object>) map;
    }

    private static int integer(Map<String, Object> map, String name, int fallback, int minimum, int maximum) {
        Object value = map.get(name);
        if (value == null) return fallback;
        if (!(value instanceof Number number) || number.longValue() != number.doubleValue()
                || number.longValue() < minimum || number.longValue() > maximum) {
            throw failure("INVALID_LIMITS", "Karate limits are invalid");
        }
        return number.intValue();
    }

    private static int parallelism(Object value) {
        if (value == null) return 1;
        BigInteger integer;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            integer = BigInteger.valueOf(((Number) value).longValue());
        } else if (value instanceof BigInteger bigInteger) {
            integer = bigInteger;
        } else {
            throw failure("INVALID_PARALLELISM", "Karate parallelism is invalid");
        }
        if (integer.compareTo(BigInteger.ONE) < 0 || integer.compareTo(BigInteger.valueOf(8)) > 0) {
            throw failure("INVALID_PARALLELISM", "Karate parallelism is invalid");
        }
        return integer.intValue();
    }

    private static String string(Object value, int maximum) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > maximum || text.indexOf('\0') >= 0) {
            throw failure("INVALID_CONFIGURATION", "Karate suite configuration is invalid");
        }
        return text;
    }

    static KarateEngineException failure(String code, String message) { return new KarateEngineException(code, message); }
}
