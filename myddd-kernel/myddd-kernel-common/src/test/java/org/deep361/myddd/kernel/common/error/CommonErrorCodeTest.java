package org.deep361.myddd.kernel.common.error;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CommonErrorCodeTest {

    @Test
    void should_follow_code_format_domain_underscore_five_digits() {
        for (CommonErrorCode errorCode : CommonErrorCode.values()) {
            assertThat(errorCode.code()).matches("[A-Z]+_\\d{5}");
        }
    }

    @Test
    void should_keep_codes_unique() {
        Set<String> codes = Arrays.stream(CommonErrorCode.values())
                .map(CommonErrorCode::code)
                .collect(Collectors.toSet());
        assertThat(codes).hasSize(CommonErrorCode.values().length);
    }

    @Test
    void should_keep_message_keys_unique_and_namespaced() {
        Set<String> keys = Arrays.stream(CommonErrorCode.values())
                .map(CommonErrorCode::messageKey)
                .collect(Collectors.toSet());
        assertThat(keys).hasSize(CommonErrorCode.values().length);
        assertThat(keys).allMatch(key -> key.startsWith("myddd.error."));
    }

    @Test
    void should_have_default_message_present() {
        for (CommonErrorCode errorCode : CommonErrorCode.values()) {
            assertThat(errorCode.defaultMessage()).isNotBlank();
        }
    }

    @Test
    void should_map_semantic_http_status() {
        Map<CommonErrorCode, Integer> expectedStatuses = Map.ofEntries(
                Map.entry(CommonErrorCode.INVALID_PARAMETER, 400),
                Map.entry(CommonErrorCode.MALFORMED_REQUEST, 400),
                Map.entry(CommonErrorCode.ARGUMENT_INVALID, 400),
                Map.entry(CommonErrorCode.UNAUTHORIZED, 401),
                Map.entry(CommonErrorCode.FORBIDDEN, 403),
                Map.entry(CommonErrorCode.NOT_FOUND, 404),
                Map.entry(CommonErrorCode.METHOD_NOT_ALLOWED, 405),
                Map.entry(CommonErrorCode.CONFLICT, 409),
                Map.entry(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE, 415),
                Map.entry(CommonErrorCode.TOO_MANY_REQUESTS, 429),
                Map.entry(CommonErrorCode.SYSTEM_ERROR, 500),
                Map.entry(CommonErrorCode.SERVICE_DEGRADED, 503));
        assertThat(expectedStatuses).hasSize(CommonErrorCode.values().length);
        for (CommonErrorCode errorCode : CommonErrorCode.values()) {
            assertThat(errorCode.httpStatus())
                    .as("httpStatus of %s", errorCode)
                    .isEqualTo(expectedStatuses.get(errorCode));
        }
    }

    @Test
    void should_match_code_suffix_to_http_status() {
        for (CommonErrorCode errorCode : CommonErrorCode.values()) {
            String code = errorCode.code();
            String suffix = code.substring(code.indexOf('_') + 1);
            assertThat(suffix)
                    .as("5 digit suffix of %s", code)
                    .hasSize(5)
                    .startsWith(String.valueOf(errorCode.httpStatus()));
        }
    }

    @Test
    void should_format_default_message_with_args() {
        assertThat(CommonErrorCode.ARGUMENT_INVALID.formatDefaultMessage("pageSize"))
                .isEqualTo("参数 pageSize 不合法");
        assertThat(CommonErrorCode.ARGUMENT_INVALID.formatDefaultMessage())
                .isEqualTo("参数 {} 不合法");
    }
}
