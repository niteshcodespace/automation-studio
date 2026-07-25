package com.automationstudio.api.service;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ClaimTokenGenerator {

    public UUID nextToken() {
        return UUID.randomUUID();
    }
}
