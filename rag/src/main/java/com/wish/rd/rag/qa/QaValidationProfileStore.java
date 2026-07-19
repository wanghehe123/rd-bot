package com.wish.rd.rag.qa;

import com.wish.rd.rag.qa.model.QaValidationProfile;

import java.util.Optional;

/** Persistence port for task and project QA validation profiles. */
public interface QaValidationProfileStore {
    QaValidationProfile save(QaValidationProfile profile);

    Optional<QaValidationProfile> find(String scopeType, String scopeId);
}
