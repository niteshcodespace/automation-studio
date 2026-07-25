package com.automationstudio.api.http;

import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.exception.PreconditionRequiredException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class IfMatchHeaderParser {

    private static final Pattern QUOTED_VERSION = Pattern.compile("\"(0|[1-9][0-9]*)\"");

    public long parseRequired(String ifMatch) {
        if (ifMatch == null) {
            throw new PreconditionRequiredException("If-Match header is required");
        }
        Matcher matcher = QUOTED_VERSION.matcher(ifMatch);
        if (!matcher.matches()) {
            throw new InvalidRequestException(
                    "If-Match must contain exactly one quoted nonnegative decimal version");
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw new InvalidRequestException("If-Match version exceeds the supported range");
        }
    }
}
