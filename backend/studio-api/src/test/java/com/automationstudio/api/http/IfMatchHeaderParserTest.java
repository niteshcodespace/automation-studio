package com.automationstudio.api.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.exception.PreconditionRequiredException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class IfMatchHeaderParserTest {

    private final IfMatchHeaderParser parser = new IfMatchHeaderParser();

    @ParameterizedTest
    @ValueSource(strings = {"\"0\"", "\"3\"", "\"9223372036854775807\""})
    void acceptsExactlyOneQuotedNonnegativeLong(String value) {
        assertThat(parser.parseRequired(value))
                .isEqualTo(Long.parseLong(value.substring(1, value.length() - 1)));
    }

    @Test
    void missingHeaderRequiresPrecondition() {
        assertThatThrownBy(() -> parser.parseRequired(null))
                .isInstanceOf(PreconditionRequiredException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "", " ", "3", "\"-1\"", "W/\"3\"", "*", "\"2\",\"3\"",
            "\"3.0\"", "\"abc\"", "\"01\"", "\"9223372036854775808\""
    })
    void rejectsMalformedHeaders(String value) {
        if (value == null) {
            assertThatThrownBy(() -> parser.parseRequired(value))
                    .isInstanceOf(PreconditionRequiredException.class);
        } else {
            assertThatThrownBy(() -> parser.parseRequired(value))
                    .isInstanceOf(InvalidRequestException.class);
        }
    }
}
