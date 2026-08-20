package com.paicli.spec;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecDraftSessionTest {
    private final ChangeSpecDocument document = new ChangeSpecDocument(null, "", "digest");

    @Test
    void confirmsFirstDraft() throws Exception {
        AtomicInteger generations = new AtomicInteger();
        SpecDraftSession session = new SpecDraftSession(
                request -> {
                    generations.incrementAndGet();
                    return SpecDraftSession.DraftGeneration.unmeasured(document);
                },
                draft -> SpecDraftSession.ReviewDecision.confirm());

        SpecDraftSession.Result result = session.run("修复问题");

        assertEquals(SpecDraftSession.Status.CONFIRMED, result.status());
        assertEquals(document, result.document());
        assertEquals("修复问题", result.confirmedRequest());
        assertEquals(1, generations.get());
    }

    @Test
    void regeneratesWithSupplement() throws Exception {
        List<String> generatedRequests = new ArrayList<>();
        AtomicInteger reviews = new AtomicInteger();
        SpecDraftSession session = new SpecDraftSession(
                request -> {
                    generatedRequests.add(request);
                    return SpecDraftSession.DraftGeneration.unmeasured(document);
                },
                draft -> reviews.getAndIncrement() == 0
                        ? SpecDraftSession.ReviewDecision.supplement("不得修改公共接口")
                        : SpecDraftSession.ReviewDecision.confirm());

        SpecDraftSession.Result result = session.run("修复问题");

        assertEquals(SpecDraftSession.Status.CONFIRMED, result.status());
        assertEquals(2, generatedRequests.size());
        assertTrue(generatedRequests.get(1).contains("修复问题"));
        assertTrue(generatedRequests.get(1).contains("不得修改公共接口"));
        assertTrue(result.confirmedRequest().contains("不得修改公共接口"));
    }

    @Test
    void cancelReturnsNoDocument() throws Exception {
        SpecDraftSession session = new SpecDraftSession(
                request -> SpecDraftSession.DraftGeneration.unmeasured(document),
                draft -> SpecDraftSession.ReviewDecision.cancel());

        SpecDraftSession.Result result = session.run("修复问题");

        assertEquals(SpecDraftSession.Status.CANCELED, result.status());
        assertNull(result.document());
        assertNull(result.confirmedRequest());
    }
}
