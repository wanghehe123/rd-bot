package com.wish.rd.engine.requirement.review;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.review.model.AiReviewDecision;
import com.wish.rd.engine.requirement.review.model.AiReviewResult;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiReviewResultValidatorTest {

    private final AiReviewResultValidator validator = new AiReviewResultValidator();

    @Test
    void acceptsStrictOkResultWithKnownSourceReferences() {
        AiReviewResult result = validator.validate("""
                {
                  "decision":"OK",
                  "score":92,
                  "summary":"delivery matches acceptance criteria",
                  "retryFromRole":"",
                  "dimensions":[
                    {"name":"qa_acceptance","score":95,"reason":"tests passed","sourceIds":["qa-result"]}
                  ],
                  "findings":[]
                }
                """, Set.of("qa-result"));

        assertEquals(AiReviewDecision.OK, result.decision());
        assertEquals(92, result.score());
        assertEquals(null, result.retryFromRole());
    }

    @Test
    void acceptsNotOkOnlyWithFindingAndRetryRole() {
        AiReviewResult result = validator.validate("""
                {
                  "decision":"NOT_OK",
                  "score":48,
                  "summary":"coding evidence conflicts with QA",
                  "retryFromRole":"CODING_AGENT",
                  "dimensions":[],
                  "findings":[
                    {"severity":"HIGH","title":"missing state update","detail":"callback does not update status",
                     "sourceIds":["code-result"],"suggestion":"fix callback and rerun QA"}
                  ]
                }
                """, Set.of("code-result"));

        assertEquals(AiReviewDecision.NOT_OK, result.decision());
        assertEquals(AgentRole.CODING_AGENT, result.retryFromRole());
    }

    @Test
    void rejectsTrailingTokensAndInventedReferences() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate("""
                {"decision":"OK","score":90,"summary":"ok","retryFromRole":"","dimensions":[],"findings":[]} trailing
                """, Set.of()));

        assertThrows(IllegalArgumentException.class, () -> validator.validate("""
                {"decision":"OK","score":90,"summary":"ok","retryFromRole":"","dimensions":[
                  {"name":"qa_acceptance","score":90,"reason":"ok","sourceIds":["invented"]}
                ],"findings":[]}
                """, Set.of("real-source")));
    }

    @Test
    void rejectsOutOfRangeScoresAndNotOkWithoutStrongFinding() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate("""
                {"decision":"OK","score":101,"summary":"ok","retryFromRole":"","dimensions":[],"findings":[]}
                """, Set.of()));

        assertThrows(IllegalArgumentException.class, () -> validator.validate("""
                {"decision":"NOT_OK","score":40,"summary":"bad","retryFromRole":"QA_AGENT","dimensions":[],
                 "findings":[{"severity":"LOW","title":"minor","detail":"minor","sourceIds":[],"suggestion":"fix"}]}
                """, Set.of()));
    }
}
