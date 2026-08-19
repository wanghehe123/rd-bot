package com.wish.rd.exec.repair.pi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.pi.model.PiProtocolFailureReceipt;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiProtocolFailureReceiptTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void matchesSharedCanonicalHashAndExactFactsFixtures() throws Exception {
        for (JsonNode fixture : fixtures().path("valid")) {
            PiProtocolFailureReceipt receipt = PiProtocolFailureReceipt.decodeAndVerify(
                    fixture.path("canonical").textValue(),
                    fixture.path("hash").textValue()
            );
            assertEquals(fixture.path("input").path("kind").textValue(), receipt.kind().name());
            assertEquals(fixture.path("canonical").textValue(), receipt.canonicalJson());
            assertEquals(fixture.path("hash").textValue(), receipt.canonicalHash());
        }
    }

    @Test
    void rejectsSharedContradictionsAndHashMismatch() throws Exception {
        for (JsonNode fixture : fixtures().path("invalid")) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> PiProtocolFailureReceipt.decodeAndVerify(
                            fixture.path("json").textValue(),
                            fixture.path("hash").textValue()
                    ),
                    fixture.path("name").textValue()
            );
            assertTrue(
                    error.getMessage().toLowerCase().contains(fixture.path("error").textValue()),
                    () -> fixture.path("name").textValue() + ": " + error.getMessage()
            );
        }
    }

    @Test
    void rejectsIdentityMismatchAfterCanonicalVerification() throws Exception {
        JsonNode fixture = fixtures().path("valid").get(0);
        PiProtocolFailureReceipt receipt = PiProtocolFailureReceipt.decodeAndVerify(
                fixture.path("canonical").textValue(), fixture.path("hash").textValue()
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> receipt.requireIdentity("other-task", "stage-1", "QA_AGENT", 1)
        );
        assertTrue(error.getMessage().contains("identity mismatch"));
    }

    private static JsonNode fixtures() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path fixture = root.resolve("test-fixtures/protocol/pi-protocol-failure-receipt-v1-fixtures.json");
        if (!Files.exists(fixture)) {
            fixture = root.getParent().resolve(
                    "test-fixtures/protocol/pi-protocol-failure-receipt-v1-fixtures.json"
            );
        }
        return OBJECT_MAPPER.readTree(Files.readString(fixture));
    }
}
