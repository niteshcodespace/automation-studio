package com.automationstudio.api.execution.engine.playwright.action;

import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightSelector;
import java.util.Locale;
import java.util.regex.Pattern;

public final class CssSelectorResolver implements SelectorResolver {
    private static final Pattern SELECTOR_ENGINE =
            Pattern.compile("[a-z][a-z0-9-]*=", Pattern.CASE_INSENSITIVE);

    @Override
    public String resolve(PlaywrightSelector selector) {
        if (selector == null) {
            throw invalid();
        }
        String value = selector.value();
        if (value == null
                || value.isBlank()
                || value.length() > PlaywrightSelector.MAX_LENGTH
                || !value.equals(value.trim())
                || value.codePoints().anyMatch(Character::isISOControl)
                || unsupportedStrategy(value)
                || !balanced(value)) {
            throw invalid();
        }
        return value;
    }

    private boolean unsupportedStrategy(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return value.startsWith("//")
                || value.startsWith("..")
                || SELECTOR_ENGINE.matcher(lower).lookingAt()
                || hasSelectorChain(value)
                || lower.startsWith("xpath=")
                || lower.startsWith("text=")
                || lower.startsWith("role=")
                || lower.startsWith("javascript=")
                || lower.startsWith("css=");
    }

    private boolean hasSelectorChain(String value) {
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < value.length() - 1; index++) {
            char current = value.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (quote != 0) {
                if (current == quote) quote = 0;
            } else if (current == '\'' || current == '"') {
                quote = current;
            } else if (current == '>' && value.charAt(index + 1) == '>') {
                return true;
            }
        }
        return false;
    }

    private boolean balanced(String value) {
        int brackets = 0;
        int parentheses = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (quote != 0) {
                if (current == quote) quote = 0;
            } else if (current == '\'' || current == '"') {
                quote = current;
            } else if (current == '[') {
                brackets++;
            } else if (current == ']' && --brackets < 0) {
                return false;
            } else if (current == '(') {
                parentheses++;
            } else if (current == ')' && --parentheses < 0) {
                return false;
            }
        }
        return quote == 0 && brackets == 0 && parentheses == 0 && !escaped;
    }

    private PlaywrightActionException invalid() {
        return new PlaywrightActionException("SELECTOR_INVALID", "Manifest selector is invalid");
    }
}
