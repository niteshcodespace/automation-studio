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
        Matcher matcher = TOKEN.matcher(input);
        StringBuilder expanded = new StringBuilder();
        int position = 0;
        while (matcher.find()) {
            if (input.substring(position, matcher.start()).contains("${")) {
                throw invalid("INTERPOLATION_INVALID");
            }
            expanded.append(input, position, matcher.start());
            String value = variables.get(matcher.group(1));
            if (value == null) throw invalid("VARIABLE_UNRESOLVED");
            expanded.append(value);
            if (expanded.length() > MAX_EXPANDED_LENGTH) throw invalid("INTERPOLATION_LIMIT_EXCEEDED");
            position = matcher.end();
        }
        expanded.append(input, position, input.length());
        if (expanded.indexOf("${") >= 0 || expanded.length() > MAX_EXPANDED_LENGTH) {
            throw invalid("INTERPOLATION_INVALID");
        }
        return expanded.toString();
    }

    private PlaywrightActionException invalid(String code) {
        return new PlaywrightActionException(code, "Variable interpolation is invalid");
    }
}
