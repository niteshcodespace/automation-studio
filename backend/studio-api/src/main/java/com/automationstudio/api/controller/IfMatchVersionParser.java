package com.automationstudio.api.controller;

import com.automationstudio.api.exception.InvalidRequestException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class IfMatchVersionParser {

    private static final Pattern QUOTED_VERSION = Pattern.compile("\"(0|[1-9][0-9]*)\"");

    private IfMatchVersionParser() {
    }

    static long parse(String ifMatch) {
        if (ifMatch == null) {
            throw new InvalidRequestException("If-Match header is required");
        }
        Matcher matcher = QUOTED_VERSION.matcher(ifMatch);
        if (!matcher.matches()) {
            throw new InvalidRequestException(
                    "If-Match must contain exactly one quoted nonnegative decimal version");
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw new InvalidRequestException(
                    "If-Match version exceeds the supported range");
        }
    }
}
