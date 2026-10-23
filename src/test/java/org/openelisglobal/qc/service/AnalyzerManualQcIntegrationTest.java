package org.openelisglobal.qc.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.hibernate.ObjectNotFoundException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openelisglobal.BaseWebContextSensitiveTest;
import org.openelisglobal.qc.dao.QCControlLotDAO;
import org.openelisglobal.qc.dao.QCResultDAO;
import org.openelisglobal.qc.dto.AnalyzerManualQcStatus;
import org.openelisglobal.qc.dto.AnalyzerManualQcStatus.State;
import org.openelisglobal.qc.form.AnalyzerManualQcForm;
import org.openelisglobal.qc.valueholder.QCControlLot;
import org.openelisglobal.qc.valueholder.QCEntryPoint;
import org.openelisglobal.qc.valueholder.QCFrequencyType;
import org.openelisglobal.qc.valueholder.QCQualitativeOutcome;
import org.openelisglobal.qc.valueholder.QCResult;
import org.openelisglobal.qc.valueholder.QCSource;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Analyzer manual QC (#3490) against a real PostgreSQL: the qc_result shape
 * rule, the per-lot frequency, the status the panel shows, the failure signal,
 * and that these runs stay out of the bench register.
 *
 * <p>
 * Fixture {@code bench-qc-fail-signal.xml} supplies test 6601 and system users
 * 1 and 7701; the analyzers and lots are seeded here.
 */
public class AnalyzerManualQcIntegrationTest extends BaseWebContextSensitiveTest {

    private static final String TEST_ID = "6601";
    private static final String ANALYZER_ID = "7911";
    private static final String OTHER_ANALYZER_ID = "7912";
    private static final String LOT_ID = "manual-qc-lot";
    private static final String OTHER_LOT_ID = "manual-qc-other-lot";
    private static final ZoneId ZONE = ZoneId.systemDefault();
    // In the past, so runs dated relative to it are never in the real future.
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 10, 0);

    @Autowired
    private AnalyzerManualQcService manualQcService;

    @Autowired
    private QCResultService qcResultService;

    @Autowired
    private QCResultDAO resultDAO;

    @Autowired
    private QCControlLotDAO controlLotDAO;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbcTemplate;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        jdbcTemplate = new JdbcTemplate(dataSource);
        executeDataSetWithStateManagement("testdata/bench-qc-fail-signal.xml");
        for (String[] a : new String[][] { { ANALYZER_ID, "Mindray BS-240" }, { OTHER_ANALYZER_ID, "Other" } }) {
            jdbcTemplate.update("INSERT INTO analyzer (id, name, is_active, last_updated) VALUES (?, ?, true, NOW())",
                    Long.parseLong(a[0]), a[1]);
        }
        insertLot(LOT_ID, ANALYZER_ID);
        insertLot(OTHER_LOT_ID, OTHER_ANALYZER_ID);
        pinClock(NOW);
    }

    private void insertLot(String lotId, String analyzerId) {
        jdbcTemplate.update("INSERT INTO qc_control_lot (id, fhir_uuid, product_name, lot_number, control_level,"
                + " test_id, instrument_id, calculation_method, activation_date, status, sys_user_id, last_updated)"
                + " VALUES (?, ?::uuid, 'Chem control', ?, 'NORMAL', ?, ?, 'MANUFACTURER_FIXED', NOW(), 'ACTIVE', 1,"
                + " NOW())", lotId, UUID.randomUUID().toString(), lotId.toUpperCase(), Long.parseLong(TEST_ID),
                Long.parseLong(analyzerId));
    }

    private void requireDaily(String lotId) {
        jdbcTemplate.update("UPDATE qc_control_lot SET qc_frequency_type = 'DAILY', qc_required = true WHERE id = ?",
                lotId);
    }

    private void pinClock(LocalDateTime at) throws Exception {
        Object target = manualQcService instanceof Advised advised ? advised.getTargetSource().getTarget()
                : manualQcService;
        ((AnalyzerManualQcServiceImpl) target).setClock(Clock.fixed(at.atZone(ZONE).toInstant(), ZONE));
    }

    @After
    public void removeSeeded() {
        String violations = "(SELECT id FROM qc_rule_violation WHERE instrument_id IN (?, ?))";
        Object[] ids = { Long.parseLong(ANALYZER_ID), Long.parseLong(OTHER_ANALYZER_ID) };
        jdbcTemplate.update("DELETE FROM qc_alert WHERE violation_id IN " + violations, ids);
        jdbcTemplate.update("DELETE FROM clinlims.nc_event WHERE trigger_source_id IN " + violations, ids);
        jdbcTemplate.update("DELETE FROM qc_rule_violation WHERE instrument_id IN (?, ?)", ids);
        jdbcTemplate.update("DELETE FROM qc_result WHERE instrument_id IN (?, ?)", ids);
        jdbcTemplate.update("DELETE FROM qc_control_lot WHERE id IN (?, ?)", LOT_ID, OTHER_LOT_ID);
        jdbcTemplate.update("DELETE FROM analyzer WHERE id IN (?, ?)", ids);
    }

    private static AnalyzerManualQcForm run(QCQualitativeOutcome outcome, LocalDateTime at, QCEntryPoint entryPoint) {
        return new AnalyzerManualQcForm(LOT_ID, outcome, null, "control read 4.9", at, null, entryPoint);
    }

    @Test
    public void passWithNoValueIsStoredAsAManualAnalyzerRow() {
        AnalyzerManualQcStatus.Run saved = manualQcService.record(ANALYZER_ID,
                run(QCQualitativeOutcome.PASS, NOW.minusHours(1), QCEntryPoint.ANALYZER_IMPORT), 1);

        QCResult row = resultDAO.get(saved.id()).orElseThrow();
        assertEquals(QCSource.MANUAL, row.getSource());
        assertEquals(ANALYZER_ID, row.getInstrumentId());
        assertEquals(TEST_ID, row.getTestId());
        assertEquals(LOT_ID, row.getControlLotId());
        assertNull(row.getResultValue());
        assertEquals(QCEntryPoint.ANALYZER_IMPORT, row.getEntryPoint());
        assertEquals("ACCEPTED", row.getResultStatus());
        assertEquals("control read 4.9", row.getExternalNotes());
        assertEquals("ANALYZER_IMPORT", jdbcTemplate.queryForObject("SELECT entry_point FROM qc_result WHERE id = ?",
                String.class, saved.id()));
    }

    @Test
    public void listEntryPointIsRecordedAndAValueIsKept() {
        AnalyzerManualQcStatus.Run saved = manualQcService.record(ANALYZER_ID, new AnalyzerManualQcForm(LOT_ID,
                QCQualitativeOutcome.PASS, new BigDecimal("4.9"), null, null, null, QCEntryPoint.ANALYZER_LIST), 1);
        QCResult row = resultDAO.get(saved.id()).orElseThrow();
        assertEquals(QCEntryPoint.ANALYZER_LIST, row.getEntryPoint());
        assertEquals(0, new BigDecimal("4.9").compareTo(row.getResultValue()));
    }

    @Test
    public void database_stillRefusesABenchManualRowWithNoValue() {
        try {
            jdbcTemplate.update(
                    "INSERT INTO qc_result (id, source, test_id, qualitative_outcome, run_date_time, result_status,"
                            + " sys_user_id, last_updated) VALUES (?, 'MANUAL', ?, 'PASS', NOW(), 'ACCEPTED', 1, NOW())",
                    UUID.randomUUID().toString(), Long.parseLong(TEST_ID));
            fail("a bench MANUAL row (no analyzer) must still carry a value");
        } catch (DataIntegrityViolationException e) {
            assertTrue(String.valueOf(e.getMessage()), String.valueOf(e.getMessage()).contains("chk_qc_result_source"));
        }
    }

    @Test
    public void database_refusesAnUnknownEntryPoint() {
        try {
            jdbcTemplate.update(
                    "INSERT INTO qc_result (id, source, test_id, instrument_id, qualitative_outcome,"
                            + " run_date_time, result_status, sys_user_id, last_updated, entry_point)"
                            + " VALUES (?, 'MANUAL', ?, ?, 'PASS', NOW(), 'ACCEPTED', 1, NOW(), 'SOMEWHERE')",
                    UUID.randomUUID().toString(), Long.parseLong(TEST_ID), Long.parseLong(ANALYZER_ID));
            fail("entry_point is limited to the two screens");
        } catch (DataIntegrityViolationException e) {
            assertTrue(String.valueOf(e.getMessage()), String.valueOf(e.getMessage()).contains("chk_qc_result_entry"));
        }
    }

    @Test
    public void lotOfAnotherAnalyzerIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> manualQcService.record(ANALYZER_ID, new AnalyzerManualQcForm(OTHER_LOT_ID,
                        QCQualitativeOutcome.PASS, null, null, null, null, QCEntryPoint.ANALYZER_IMPORT), 1));
    }

    @Test
    public void futureRunAndRdtOutcomeAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> manualQcService.record(ANALYZER_ID,
                run(QCQualitativeOutcome.PASS, LocalDateTime.now().plusHours(2), QCEntryPoint.ANALYZER_IMPORT), 1));
        assertThrows(IllegalArgumentException.class, () -> manualQcService.record(ANALYZER_ID,
                run(QCQualitativeOutcome.VALID, null, QCEntryPoint.ANALYZER_IMPORT), 1));
    }

    @Test
    public void missingOutcomeLotOrEntryPointIsRefusedByTheServiceItself() {
        assertThrows(IllegalArgumentException.class, () -> manualQcService.record(ANALYZER_ID,
                new AnalyzerManualQcForm(LOT_ID, null, null, null, null, null, QCEntryPoint.ANALYZER_IMPORT), 1));
        assertThrows(IllegalArgumentException.class,
                () -> manualQcService.record(ANALYZER_ID, new AnalyzerManualQcForm(null, QCQualitativeOutcome.PASS,
                        null, null, null, null, QCEntryPoint.ANALYZER_IMPORT), 1));
        assertThrows(IllegalArgumentException.class, () -> manualQcService.record(ANALYZER_ID,
                new AnalyzerManualQcForm(LOT_ID, QCQualitativeOutcome.PASS, null, null, null, null, null), 1));
    }

    @Test
    public void delegatedEntryKeepsRecorderAndPerformerApart() {
        AnalyzerManualQcStatus.Run saved = manualQcService.record(ANALYZER_ID, new AnalyzerManualQcForm(LOT_ID,
                QCQualitativeOutcome.PASS, null, null, null, "7701", QCEntryPoint.ANALYZER_IMPORT), 1);
        QCResult row = resultDAO.get(saved.id()).orElseThrow();
        assertEquals(Integer.valueOf(7701), row.getTechnicianId());
        assertEquals(Integer.valueOf(1), row.getSystemUserId());
        assertThrows(IllegalArgumentException.class,
                () -> manualQcService.record(ANALYZER_ID, new AnalyzerManualQcForm(LOT_ID, QCQualitativeOutcome.PASS,
                        null, null, null, "999999", QCEntryPoint.ANALYZER_IMPORT), 1));
    }

    @Test
    public void status_walksNotRunOverduePassFailed() throws Exception {
        AnalyzerManualQcStatus status = manualQcService.getStatus(ANALYZER_ID);
        assertEquals(State.NOT_RUN, status.state());
        assertFalse("no lot enforces a frequency yet", status.overdue());
        assertEquals("Mindray BS-240", status.analyzerName());
        assertEquals(1, status.lots().size());

        requireDaily(LOT_ID);
        status = manualQcService.getStatus(ANALYZER_ID);
        assertEquals("a required lot that never passed is overdue", State.OVERDUE, status.state());
        assertTrue(status.qcRequired());
        assertNull(status.lastRun());

        manualQcService.record(ANALYZER_ID,
                run(QCQualitativeOutcome.PASS, NOW.minusHours(2), QCEntryPoint.ANALYZER_IMPORT), 1);
        status = manualQcService.getStatus(ANALYZER_ID);
        assertEquals(State.PASS, status.state());
        assertFalse("a passing run dismisses the overdue warning", status.overdue());
        assertEquals(NOW.toLocalDate().plusDays(1).atStartOfDay(), status.nextDueAt());
        assertEquals("PASS", status.lastRun().outcome());
        assertNotNull(status.lastRun().performedBy());

        pinClock(NOW.plusDays(1));
        assertEquals("yesterday's pass does not cover today", State.OVERDUE,
                manualQcService.getStatus(ANALYZER_ID).state());

        pinClock(NOW);
        manualQcService.record(ANALYZER_ID,
                run(QCQualitativeOutcome.FAIL, NOW.minusHours(1), QCEntryPoint.ANALYZER_LIST), 1);
        status = manualQcService.getStatus(ANALYZER_ID);
        assertEquals("the latest run failed", State.FAILED, status.state());
        assertEquals("FAIL", status.lastRun().outcome());
    }

    @Test
    public void notRequiredLotNeverWarns() {
        jdbcTemplate.update("UPDATE qc_control_lot SET qc_frequency_type = 'CUSTOM_HOURS', qc_frequency_hours = 4,"
                + " qc_required = false WHERE id = ?", LOT_ID);
        AnalyzerManualQcStatus status = manualQcService.getStatus(ANALYZER_ID);
        assertFalse(status.qcRequired());
        assertFalse(status.overdue());
        assertEquals(State.NOT_RUN, status.state());
    }

    @Test
    public void failRaisesAManualFailViolationOnTheAnalyzer() throws Exception {
        AnalyzerManualQcStatus.Run saved = manualQcService.record(ANALYZER_ID,
                run(QCQualitativeOutcome.FAIL, NOW.minusMinutes(5), QCEntryPoint.ANALYZER_IMPORT), 1);
        String code = null;
        for (int attempt = 0; attempt < 50 && code == null; attempt++) {
            List<String> codes = jdbcTemplate.queryForList(
                    "SELECT rule_code FROM qc_rule_violation WHERE triggering_result_id = ? AND instrument_id = ?",
                    String.class, saved.id(), Long.parseLong(ANALYZER_ID));
            code = codes.isEmpty() ? null : codes.get(0);
            if (code == null) {
                Thread.sleep(100);
            }
        }
        assertEquals("MANUAL_FAIL", code);
    }

    @Test
    public void manualAnalyzerRunsStayOutOfTheBenchRegister() {
        manualQcService.record(ANALYZER_ID,
                run(QCQualitativeOutcome.PASS, NOW.minusHours(1), QCEntryPoint.ANALYZER_IMPORT), 1);
        Timestamp from = Timestamp.valueOf(NOW.minusDays(1));
        Timestamp to = Timestamp.valueOf(LocalDateTime.now().plusDays(1));
        assertTrue(qcResultService.findBenchResults(from, to, QCSource.MANUAL, 100).stream()
                .noneMatch(r -> ANALYZER_ID.equals(r.getInstrumentId())));
        assertTrue(resultDAO.summariseBenchQc(from, to, QCSource.MANUAL).stream()
                .noneMatch(row -> TEST_ID.equals(String.valueOf(row[1]))));
        assertEquals("but they are in the analyzer's own history", 1,
                manualQcService.getHistory(ANALYZER_ID, 10).size());
    }

    @Test
    public void lotFrequencyFieldsRoundTrip() {
        QCControlLot lot = controlLotDAO.get(LOT_ID).orElseThrow();
        assertFalse(lot.getQcRequired());
        lot.setQcFrequencyType(QCFrequencyType.PER_SHIFT);
        lot.setQcFrequencyHours(12);
        lot.setQcRequired(true);
        controlLotDAO.update(lot);
        assertEquals("PER_SHIFT", jdbcTemplate
                .queryForObject("SELECT qc_frequency_type FROM qc_control_lot WHERE id = ?", String.class, LOT_ID));
        assertEquals(Integer.valueOf(12), jdbcTemplate
                .queryForObject("SELECT qc_frequency_hours FROM qc_control_lot WHERE id = ?", Integer.class, LOT_ID));
    }

    @Test
    public void unknownAnalyzerIsNotFound() {
        assertThrows(ObjectNotFoundException.class, () -> manualQcService.getStatus("999999"));
    }
}
