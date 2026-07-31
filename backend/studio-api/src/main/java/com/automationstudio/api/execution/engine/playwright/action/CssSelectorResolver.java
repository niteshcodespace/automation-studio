package com.automationstudio.api.execution.engine.playwright.action;

import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightSelector;

public final class CssSelectorResolver implements SelectorResolver {
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
                || hasBoundaryWhitespace(value)
                || value.codePoints().anyMatch(Character::isISOControl)
                || unsupportedStrategy(value)
                || !balanced(value)) {
            throw invalid();
        }
        return value;
    }

    private boolean unsupportedStrategy(String value) {
        return value.startsWith("//")
                || value.startsWith("..")
                || hasSelectorEnginePrefix(value)
                || hasSelectorChain(value);
    }

    private boolean hasSelectorEnginePrefix(String value) {
        int index = 0;
        while (index < value.length() && value.charAt(index) == '*') {
            index++;
        }
        int identifierStart = index;
        if (index >= value.length() || !isEngineIdentifierStart(value.charAt(index))) {
            return false;
        }
        index++;
        while (index < value.length() && isEngineIdentifierPart(value.charAt(index))) {
            index++;
        }
        return index > identifierStart && index < value.length() && value.charAt(index) == '=';
    }

    private boolean isEngineIdentifierStart(char value) {
        return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z';
    }

    private boolean isEngineIdentifierPart(char value) {
        return isEngineIdentifierStart(value)
                || value >= '0' && value <= '9'
                || value == '-'
                || value == '_'
                || value == ':';
    }

    private boolean hasBoundaryWhitespace(String value) {
        int first = value.codePointAt(0);
        int last = value.codePointBefore(value.length());
        return Character.isWhitespace(first)
                || Character.isSpaceChar(first)
                || Character.isWhitespace(last)
                || Character.isSpaceChar(last);
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
