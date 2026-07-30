package com.automationstudio.api.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.exception.InvalidRequestException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SourceConfigurationValidatorTest {

    private static final String SHA =
            "ABCDEF0123456789ABCDEF0123456789ABCDEF01";
    private final SourceConfigurationValidator validator =
            new SourceConfigurationValidator();

    @Test
    void normalizesCredentialFreeHttpsSourceAndPortableLocation() {
        ExecutionSourceReference source = validator.validate(
                SourceType.GIT_HTTPS,
                "HTTPS://GitHub.COM/acme/automation.git",
                SHA,
                "tests/ui");

        assertThat(source.sourceType()).isEqualTo(SourceType.GIT_HTTPS);
        assertThat(source.repository())
                .isEqualTo("https://github.com/acme/automation.git");
        assertThat(source.revision()).isEqualTo(SHA.toLowerCase());
        assertThat(source.sourceLocation()).isEqualTo("tests/ui");
        assertThat(source.toSnapshot()).containsEntry("sourceLocation", "tests/ui");
    }

    @Test
    void acceptsAbsentOptionalSourceLocation() {
        assertThat(validator.normalizeSourceLocation(null)).isNull();
    }

    @ParameterizedTest
    @MethodSource("invalidRepositories")
    void rejectsInvalidRepositoryIdentity(String repository) {
        assertThatThrownBy(() -> validator.normalizeRepository(repository))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageNotContaining(repository == null ? "never" : repository);
    }

    static Stream<String> invalidRepositories() {
        return Stream.of(
                "http://example.test/repo.git",
                "ssh://git@example.test/repo.git",
                "git@example.test:repo.git",
                "https://user:secret@example.test/repo.git",
                "https://example.test/repo.git?token=secret",
                "https://example.test/repo.git#main",
                " https://example.test/repo.git",
                "https://example.test/repo.git ",
                "https:///repo.git",
                "https://",
                "https://example.test/repo\u0001.git",
                "https://example.test/" + "a".repeat(1000));
    }

    @ParameterizedTest
    @MethodSource("invalidRevisions")
    void rejectsNonExactRevision(String revision) {
        assertThatThrownBy(() -> validator.normalizeRevision(revision))
                .isInstanceOf(InvalidRequestException.class);
    }

    static Stream<String> invalidRevisions() {
        return Stream.of(
                "abcdef0",
                "main",
                "HEAD",
                "refs/heads/main",
                "g".repeat(40),
                SHA + "0",
                " " + SHA,
                SHA + " ");
    }

    @ParameterizedTest
    @MethodSource("invalidLocations")
    void rejectsUnsafeSourceLocation(String location) {
        assertThatThrownBy(() -> validator.normalizeSourceLocation(location))
                .isInstanceOf(InvalidRequestException.class);
    }

    static Stream<Arguments> invalidLocations() {
        return Stream.of(
                Arguments.of(""),
                Arguments.of("   "),
                Arguments.of("/tests"),
                Arguments.of("\\\\server\\share"),
                Arguments.of("C:/tests"),
                Arguments.of("tests\\ui"),
                Arguments.of("../tests"),
                Arguments.of("tests/../ui"),
                Arguments.of("tests/./ui"),
                Arguments.of("tests//ui"),
                Arguments.of("~/.tests"),
                Arguments.of("a/".repeat(20) + "b"),
                Arguments.of("a".repeat(501)));
    }
}
