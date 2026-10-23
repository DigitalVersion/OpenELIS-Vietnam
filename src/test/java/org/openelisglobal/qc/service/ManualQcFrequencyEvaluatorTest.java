package org.openelisglobal.qc.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.LocalDateTime;
import org.junit.Test;
import org.openelisglobal.qc.form.QCControlLotForm;
import org.openelisglobal.qc.valueholder.QCFrequencyType;

/**
 * Frequency rules for analyzer manual QC (#3490), without a clock or database.
 */
public class ManualQcFrequencyEvaluatorTest {

    private static final LocalDateTime PASS_AT = LocalDateTime.of(2026, 10, 23, 6, 30);

    @Test
    public void daily_isCoveredUntilMidnightOfTheSameDay() {
        assertEquals(LocalDateTime.of(2026, 10, 24, 0, 0),
                ManualQcFrequencyEvaluator.dueAt(QCFrequencyType.DAILY, null, PASS_AT));
        assertFalse(ManualQcFrequencyEvaluator.isOverdue(QCFrequencyType.DAILY, null, PASS_AT,
                LocalDateTime.of(2026, 10, 23, 23, 59)));
        assertTrue(ManualQcFrequencyEvaluator.isOverdue(QCFrequencyType.DAILY, null, PASS_AT,
                LocalDateTime.of(2026, 10, 24, 0, 0)));
    }

    @Test
    public void daily_passLateYesterdayDoesNotCoverToday() {
        LocalDateTime lateYesterday = LocalDateTime.of(2026, 10, 22, 23, 50);
        assertTrue(ManualQcFrequencyEvaluator.isOverdue(QCFrequencyType.DAILY, null, lateYesterday,
                LocalDateTime.of(2026, 10, 23, 0, 5)));
    }

    @Test
    public void perShift_defaultsToEightHours() {
        assertEquals(PASS_AT.plusHours(8), ManualQcFrequencyEvaluator.dueAt(QCFrequencyType.PER_SHIFT, null, PASS_AT));
        assertEquals(PASS_AT.plusHours(12), ManualQcFrequencyEvaluator.dueAt(QCFrequencyType.PER_SHIFT, 12, PASS_AT));
    }

    @Test
    public void customHours_usesConfiguredWindow() {
        assertFalse(ManualQcFrequencyEvaluator.isOverdue(QCFrequencyType.CUSTOM_HOURS, 4, PASS_AT,
                PASS_AT.plusHours(3).plusMinutes(59)));
        assertTrue(
                ManualQcFrequencyEvaluator.isOverdue(QCFrequencyType.CUSTOM_HOURS, 4, PASS_AT, PASS_AT.plusHours(4)));
    }

    @Test
    public void customHours_withoutHoursIsNeverCovered() {
        assertTrue(ManualQcFrequencyEvaluator.isOverdue(QCFrequencyType.CUSTOM_HOURS, null, PASS_AT,
                PASS_AT.plusMinutes(1)));
    }

    @Test
    public void ruleWithNoPassIsOverdue_noRuleNeverIs() {
        assertTrue(ManualQcFrequencyEvaluator.isOverdue(QCFrequencyType.DAILY, null, null, PASS_AT));
        assertNull(ManualQcFrequencyEvaluator.dueAt(QCFrequencyType.DAILY, null, null));
        assertFalse(ManualQcFrequencyEvaluator.isOverdue(null, null, null, PASS_AT));
        assertNull(ManualQcFrequencyEvaluator.dueAt(null, 4, PASS_AT));
    }

    @Test
    public void controlLotForm_refusesIncompleteFrequency() {
        QCControlLotForm form = new QCControlLotForm();
        assertTrue("no rule at all is fine", form.isQcFrequencyComplete());
        form.setQcRequired(true);
        assertFalse("required without a frequency", form.isQcFrequencyComplete());
        form.setQcFrequencyType(QCFrequencyType.CUSTOM_HOURS);
        assertFalse("custom hours without hours", form.isQcFrequencyComplete());
        form.setQcFrequencyHours(6);
        assertTrue(form.isQcFrequencyComplete());
    }
}
