package com.wish.rd.exec.repair;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import com.wish.rd.exec.repair.model.RepairAssetType;

class RepairAssetTypeTest {

    @Test
    void shouldExposeRequirementDeliveryExperienceAssetTypes() {
        assertNotNull(RepairAssetType.PRODUCT_SPEC);
        assertNotNull(RepairAssetType.TECHNICAL_DESIGN);
        assertNotNull(RepairAssetType.QA_REPORT);
        assertNotNull(RepairAssetType.REQUIREMENT_REVIEW);
        assertNotNull(RepairAssetType.LESSON_LEARNED);
        assertNotNull(RepairAssetType.DELIVERY_REPORT);
    }
}
