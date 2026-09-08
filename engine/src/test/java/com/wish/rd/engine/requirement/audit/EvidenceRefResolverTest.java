package com.wish.rd.engine.requirement.audit;

import com.wish.rd.engine.requirement.audit.impl.InMemoryEvidenceRefResolver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceRefResolverTest {

    @Test
    void resolvesFiveSourceKindsAndRejectsDanglingUris() {
        InMemoryEvidenceRefResolver resolver = new InMemoryEvidenceRefResolver();
        EvidenceRef host = ref(EvidenceSourceKind.HOST_VERIFICATION, "host-verification://artifacts/1");
        EvidenceRef qa = ref(EvidenceSourceKind.QA_EVIDENCE, "qa-evidence://objects/1");
        EvidenceRef ledger = ref(EvidenceSourceKind.PUBLICATION_LEDGER, "publication://ledger/1");
        EvidenceRef fingerprint = ref(EvidenceSourceKind.WORKSPACE_FINGERPRINT, "workspace-fingerprint://task/qa");
        EvidenceRef assertion = ref(EvidenceSourceKind.HOST_ASSERTION, "host-assertion://bundle/1");
        resolver.put(host);
        resolver.put(qa);
        resolver.put(ledger);
        resolver.put(fingerprint);
        resolver.put(assertion);
        resolver.requireResolvable(host);
        resolver.requireResolvable(qa);
        resolver.requireResolvable(ledger);
        resolver.requireResolvable(fingerprint);
        resolver.requireResolvable(assertion);
        EvidenceRef dangling = ref(EvidenceSourceKind.QA_EVIDENCE, "qa-evidence://objects/missing");
        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> resolver.requireResolvable(dangling));
        assertTrue(failure.getMessage().contains("qa-evidence://objects/missing"));
    }

    private static EvidenceRef ref(EvidenceSourceKind kind, String uri) {
        return new EvidenceRef("audit-1", kind, uri, "sha256:" + "e".repeat(64));
    }
}
