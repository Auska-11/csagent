package com.smartcs.smart_cs_agent.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ResponseQualityEvalCaseFileService tests.
 *
 * The service writes to a temporary JSON file, so these tests never modify the real
 * src/test/resources/eval/response_quality_eval_cases.json file.
 */
class ResponseQualityEvalCaseFileServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldMergeCasesAndSkipDuplicateIds() {
        ResponseQualityEvalCaseFileService service = newService();

        EvalCaseMergeResult first = service.merge(List.of(new ResponseQualityEvalCase(
                "feedback_case_001",
                "reply without return timeline",
                List.of("return timeline"),
                List.of("Tool"),
                false
        )));

        EvalCaseMergeResult second = service.merge(List.of(
                new ResponseQualityEvalCase(
                        "feedback_case_001",
                        "duplicate reply",
                        List.of("return timeline"),
                        List.of("Tool"),
                        false
                ),
                new ResponseQualityEvalCase(
                        "feedback_case_002",
                        "promise refund",
                        List.of(),
                        List.of("guaranteed refund"),
                        false
                )
        ));

        assertEquals(0, first.getBeforeCount());
        assertEquals(1, first.getMergedCount());
        assertEquals(1, first.getAfterCount());
        assertEquals(1, second.getBeforeCount());
        assertEquals(1, second.getMergedCount());
        assertEquals(2, second.getAfterCount());
        assertEquals(List.of("feedback_case_001"), second.getSkippedDuplicateIds());
    }

    @Test
    void shouldRejectPositiveCaseWithoutRequiredKeywords() {
        ResponseQualityEvalCaseFileService service = newService();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.merge(List.of(new ResponseQualityEvalCase(
                        "positive_without_required",
                        "reply",
                        List.of(),
                        List.of("Tool"),
                        true
                )))
        );

        assertEquals("Positive eval case must provide requiredKeywords: positive_without_required", exception.getMessage());
    }

    @Test
    void shouldRejectNegativeCaseWithoutAnyAssertion() {
        ResponseQualityEvalCaseFileService service = newService();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.merge(List.of(new ResponseQualityEvalCase(
                        "negative_without_assertion",
                        "reply",
                        List.of(),
                        List.of(),
                        false
                )))
        );

        assertEquals("Negative eval case must provide requiredKeywords or forbiddenKeywords: negative_without_assertion", exception.getMessage());
    }

    private ResponseQualityEvalCaseFileService newService() {
        return new ResponseQualityEvalCaseFileService(
                new ObjectMapper(),
                tempDir.resolve("response_quality_eval_cases.json")
        );
    }
}
