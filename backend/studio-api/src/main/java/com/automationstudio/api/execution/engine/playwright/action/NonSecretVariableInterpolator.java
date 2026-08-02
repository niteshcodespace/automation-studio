package com.automationstudio.api.execution.engine.playwright.action;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NonSecretVariableInterpolator {
    public static final int MAX_VARIABLES = 100;
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_EXPANDED_LENGTH = 4_096;
    private static final Pattern TOKEN = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_.-]{0,63})}");
    private final Map<String, String> variables;

    public NonSecretVariableInterpolator(Map<String, String> variables) {
        Objects.requireNonNull(variables, "Variables are required");
        if (variables.size() > MAX_VARIABLES) throw invalid("VARIABLE_LIMIT_EXCEEDED");
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}") || value == null
                    || value.length() > MAX_EXPANDED_LENGTH
                    || value.contains("${")
                    || value.codePoints().anyMatch(Character::isISOControl)) {
                throw invalid("VARIABLE_INVALID");
            }
        }
        this.variables = Map.copyOf(variables);
    }

    public String interpolate(String input) {
        if (input == null) throw invalid("INTERPOLATION_INVALID");
        if (input.length() > MAX_EXPANDED_LENGTH) {
            throw invalid("INTERPOLATION_LIMIT_EXCEEDED");
        }
        Matcher matcher = TOKEN.matcher(input);
        StringBuilder expanded = new StringBuilder(input.length());
        int position = 0;
        while (matcher.find()) {
            int malformed = input.indexOf("${", position);
            if (malformed >= 0 && malformed < matcher.start()) {
                throw invalid("INTERPOLATION_INVALID");
            }
            append(expanded, input, position, matcher.start());
            String value = variables.get(matcher.group(1));
            if (value == null) throw invalid("VARIABLE_UNRESOLVED");
            append(expanded, value, 0, value.length());
            position = matcher.end();
        }
        if (input.indexOf("${", position) >= 0) {
            throw invalid("INTERPOLATION_INVALID");
        }
        append(expanded, input, position, input.length());
        return expanded.toString();
    }

    private void append(StringBuilder target, String source, int start, int end) {
        int segmentLength = end - start;
        if (segmentLength > MAX_EXPANDED_LENGTH - target.length()) {
            throw invalid("INTERPOLATION_LIMIT_EXCEEDED");
        }
        target.append(source, start, end);
    }

    private PlaywrightActionException invalid(String code) {
        return new PlaywrightActionException(code, "Variable interpolation is invalid");
    }
}
