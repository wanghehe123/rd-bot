package com.wish.rd.engine.admin.observability.model;

import com.wish.rd.engine.scheduling.RequirementDeliveryMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurationHistogramTest {

    @Test
    void bucketsAreCumulativeMonotonicAndIgnoreNegativeSamples() {
        DurationHistogram histogram = DurationHistogram.fromMillis(List.of(40L, 1_200L, -5L, 3_600_000L));

        assertEquals(3L, histogram.count());
        assertEquals(3_601.24D, histogram.sumSeconds(), 0.001D);

        long previous = 0L;
        double previousLe = 0D;
        for (Map.Entry<Double, Long> bucket : histogram.cumulativeBuckets().entrySet()) {
            assertTrue(bucket.getValue() >= previous, "histogram counts must be cumulative");
            assertTrue(bucket.getKey() >= previousLe || Double.isInfinite(bucket.getKey()),
                    "le must be monotonic");
            previous = bucket.getValue();
            previousLe = bucket.getKey();
        }
        assertEquals(3L, histogram.cumulativeBuckets().get(Double.POSITIVE_INFINITY));
        assertEquals(RequirementDeliveryMetrics.DURATION_BUCKET_SECONDS.length + 1,
                histogram.cumulativeBuckets().size());
        assertEquals(1L, histogram.cumulativeBuckets().get(0.05D));
        assertEquals(1L, histogram.cumulativeBuckets().get(1.0D));
        assertEquals(2L, histogram.cumulativeBuckets().get(2.5D));
    }

    @Test
    void emptyWindowIsZeroCountNotAMissingSeriesShape() {
        DurationHistogram histogram = DurationHistogram.empty();
        assertEquals(0L, histogram.count());
        assertEquals(0D, histogram.sumSeconds(), 0D);
        assertEquals(0L, histogram.cumulativeBuckets().get(Double.POSITIVE_INFINITY));
    }
}
