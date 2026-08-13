package com.wish.rd.bootstrap.openviking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.rag.knowledge.projection.model.ExternalIndexFailureClass;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 把 WP-0 冻结的真实错误信封翻译成领域分类。断言只使用
 * {@code bootstrap/src/test/resources/openviking/contracts/} 里采集到的字段。
 */
class OpenVikingErrorTranslatorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldTreatPreSendTransportFailureAsSafeToResubmitAndPostSendAsUnknown() {
        assertEquals(
                ExternalIndexFailureClass.RETRYABLE_NOT_SENT,
                OpenVikingErrorTranslator.classifyTransport(new ConnectException("connect timed out"), false));
        assertEquals(
                ExternalIndexFailureClass.UNKNOWN_REMOTE_RESULT,
                OpenVikingErrorTranslator.classifyTransport(new HttpTimeoutException("request timed out"), true));
        assertTrue(ExternalIndexFailureClass.RETRYABLE_NOT_SENT.safeToResubmit());
        assertFalse(ExternalIndexFailureClass.UNKNOWN_REMOTE_RESULT.safeToResubmit());
    }

    @Test
    void shouldBlockOnAuthenticationAndPermissionEnvelopes() throws Exception {
        assertEquals(
                ExternalIndexFailureClass.CONFIGURATION_BLOCKED,
                OpenVikingErrorTranslator.classify(401, fixture("unauthenticated.json")));
        assertEquals(
                ExternalIndexFailureClass.CONFIGURATION_BLOCKED,
                OpenVikingErrorTranslator.classify(401, fixture("invalid_api_key.json")));
        assertEquals(
                ExternalIndexFailureClass.CONFIGURATION_BLOCKED,
                OpenVikingErrorTranslator.classify(403, fixture("permission_denied_root_data.json")));
        assertEquals(
                ExternalIndexFailureClass.CONFIGURATION_BLOCKED,
                OpenVikingErrorTranslator.classify(403, fixture("permission_denied_private_network.json")));
    }

    @Test
    void shouldQuarantineContractAndDataErrors() throws Exception {
        assertEquals(
                ExternalIndexFailureClass.CONTRACT_OR_DATA_ERROR,
                OpenVikingErrorTranslator.classify(400, fixture("invalid_uri.json")));
        assertEquals(
                ExternalIndexFailureClass.CONTRACT_OR_DATA_ERROR,
                OpenVikingErrorTranslator.classify(400, fixture("add_resource_missing_source.json")));
    }

    @Test
    void shouldMarkPathBusyConflictRetryableButNotAlreadyExists() throws Exception {
        assertEquals(
                ExternalIndexFailureClass.RETRYABLE_BUSY,
                OpenVikingErrorTranslator.classify(409, fixture("conflict_busy.json")));
        assertEquals(
                ExternalIndexFailureClass.CONTRACT_OR_DATA_ERROR,
                OpenVikingErrorTranslator.classify(409, fixture("account_already_exists.json")));
    }

    @Test
    void shouldNotTreatServerErrorsOrThrottlingAsTerminal() throws Exception {
        assertEquals(
                ExternalIndexFailureClass.RETRYABLE,
                OpenVikingErrorTranslator.classify(429, OBJECT_MAPPER.createObjectNode()));
        assertEquals(
                ExternalIndexFailureClass.UNKNOWN_REMOTE_RESULT,
                OpenVikingErrorTranslator.classify(500, OBJECT_MAPPER.createObjectNode()));
        assertEquals(
                ExternalIndexFailureClass.UNKNOWN_REMOTE_RESULT,
                OpenVikingErrorTranslator.classify(503, OBJECT_MAPPER.createObjectNode()));
    }

    @Test
    void shouldTreatIdempotentDeleteAsSuccessAndUnexpectedNotFoundAsDataError() throws Exception {
        assertEquals(
                ExternalIndexFailureClass.NONE,
                OpenVikingErrorTranslator.classify(200, fixture("delete_idempotent.json")));
        // Task and stat callers own the meaning of 404 and must not route it here; this is the
        // write-path fallback for a target that does not exist.
        assertEquals(
                ExternalIndexFailureClass.CONTRACT_OR_DATA_ERROR,
                OpenVikingErrorTranslator.classify(404, fixture("stat_not_found.json")));
    }

    @Test
    void shouldRedactCredentialsFromTranslatedMessages() {
        String message = OpenVikingErrorTranslator.safeMessage(
                "rejected {\"user_key\":\"ov-live-secret\",\"admin_key\":\"ov-admin-secret\"}");
        assertFalse(message.contains("ov-live-secret"));
        assertFalse(message.contains("ov-admin-secret"));
        assertTrue(message.contains("REDACTED"));
    }

    private static JsonNode fixture(String name) throws Exception {
        try (InputStream input = OpenVikingErrorTranslatorTest.class.getResourceAsStream(
                "/openviking/contracts/" + name
        )) {
            if (input == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return OBJECT_MAPPER.readTree(input);
        }
    }
}
