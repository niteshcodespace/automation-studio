package com.automationstudio.engine.selenium;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeleniumBrowserInertSecurityTest {
    private static final List<String> PROHIBITED = List.of(
            "org/openqa/selenium", "WebDriver", "RemoteWebDriver", "ChromeDriver",
            "DriverService", "SeleniumManager", "getRuntime",
            "java/net/Socket", "java/net/http", "com/automationstudio/api");

    @Test void productionBytecodeContainsNoRuntimeOrBackendReferences() throws IOException {
        Path classes = Path.of("target", "classes", "com", "automationstudio", "engine", "selenium");
        try (var files = Files.walk(classes)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                String constants = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                PROHIBITED.forEach(value -> assertFalse(constants.contains(value),
                        file + " must not reference " + value));
            }
        }
    }

    @Test void dockerCliExecutionIsArgvOnlyAndShellFree() throws IOException {
        String source = Files.readString(Path.of("src", "main", "java", "com", "automationstudio",
                "engine", "selenium", "SeleniumContainmentCommandRunner.java"));
        assertTrue(source.contains("new ProcessBuilder(command)"));
        for (String prohibited : List.of("cmd /c", "cmd.exe", "sh -c", "bash -c")) {
            assertFalse(source.contains(prohibited));
        }
    }

    @Test void modulePomDoesNotCreateBackendOrRegistrationDependency() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(pom.contains("<artifactId>selenium-java</artifactId>"));
        assertTrue(pom.contains("<selenium.version>4.46.0</selenium.version>"));
        for (String prohibited : List.of("studio-api", "spring-context", "spring-boot")) {
            assertFalse(pom.contains(prohibited));
        }
    }
}
