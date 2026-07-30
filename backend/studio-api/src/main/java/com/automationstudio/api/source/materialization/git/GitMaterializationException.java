package com.automationstudio.api.source.materialization.git;

import com.automationstudio.api.source.materialization.SourceMaterializationException;

public class GitMaterializationException extends SourceMaterializationException {

    public GitMaterializationException(
            String code,
            String message,
            Throwable cause) {
        super(code, message, cause);
    }
}
