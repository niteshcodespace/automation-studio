package com.automationstudio.api.exception;

public class PreconditionRequiredException extends RuntimeException {

    public PreconditionRequiredException(String message) {
        super(message);
    }
}
