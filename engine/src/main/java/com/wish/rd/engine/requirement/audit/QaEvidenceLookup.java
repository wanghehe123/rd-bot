package com.wish.rd.engine.requirement.audit;

import java.util.Optional;

/**
 * Resolves a QA artifact path to a persisted {@code rd_qa_evidence_objects} row when the Host
 * already stored one. Missing hits keep the artifact path so the resolver can fail closed.
 */
@FunctionalInterface
public interface QaEvidenceLookup {

    /**
     * Looks up a persisted evidence object by the artifact path declared in the QA report.
     *
     * @param artifactPath path such as {@code qa-evidence/console/ac001.log}
     * @return persisted object identity when the Host already stored the row
     */
    Optional<PersistedObject> findByArtifactPath(String artifactPath);

    /**
     * One persisted QA evidence object.
     *
     * @param objectId durable object id used in {@code qa-evidence://objects/{id}}
     * @param sha256 content digest
     */
    record PersistedObject(String objectId, String sha256) {
        public PersistedObject {
            objectId = objectId == null ? "" : objectId.strip();
            sha256 = sha256 == null ? "" : sha256.strip();
        }
    }
}
