package com.automationstudio.api.exception;

import java.util.UUID;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resourceType, UUID id) {
        super(resourceType + " not found: " + id);
    }
}
