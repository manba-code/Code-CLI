package com.paicli.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SpecReviewInputParserTest {

    @Test
    void blankInputConfirmsDraft() {
        SpecReviewInputParser.Decision decision = SpecReviewInputParser.parse("  ");

        assertEquals(SpecReviewInputParser.DecisionType.CONFIRM, decision.type());
        assertNull(decision.supplement());
    }

    @Test
    void cancelInputCancelsDraft() {
        SpecReviewInputParser.Decision decision = SpecReviewInputParser.parse("/cancel");

        assertEquals(SpecReviewInputParser.DecisionType.CANCEL, decision.type());
        assertNull(decision.supplement());
    }

    @Test
    void normalTextBecomesSupplement() {
        SpecReviewInputParser.Decision decision = SpecReviewInputParser.parse("不得修改公共接口");

        assertEquals(SpecReviewInputParser.DecisionType.SUPPLEMENT, decision.type());
        assertEquals("不得修改公共接口", decision.supplement());
    }
}
