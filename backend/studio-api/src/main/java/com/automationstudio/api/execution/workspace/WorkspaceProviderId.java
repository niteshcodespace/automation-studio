package com.automationstudio.api.execution.workspace;

import java.util.Locale;
import java.util.regex.Pattern;

public record WorkspaceProviderId(String value) {

    private static final int MAX_LENGTH = 64;
    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9-]*");

    public WorkspaceProviderId {
        if (value == null || value.isBlank()) {
            throw new WorkspaceContractException("Workspace provider ID must not be blank");
        }
        if (!value.equals(value.trim())) {
            throw new WorkspaceContractException(
                    "Workspace provider ID must not contain surrounding whitespace");
        }
        value = value.toLowerCase(Locale.ROOT);
        if (value.length() > MAX_LENGTH || !FORMAT.matcher(value).matches()) {
            throw new WorkspaceContractException("Workspace provider ID has an invalid format");
        }
    }
}
