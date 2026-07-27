package com.automationstudio.api.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RunnerHealthProperties.class)
public class RunnerHealthConfiguration {
}
