package org.openelisglobal.qc.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hibernate.ObjectNotFoundException;
import org.openelisglobal.analyzer.service.AnalyzerService;
import org.openelisglobal.analyzer.valueholder.Analyzer;
import org.openelisglobal.qc.dao.QCControlLotDAO;
import org.openelisglobal.qc.dao.QCResultDAO;
import org.openelisglobal.qc.dto.AnalyzerManualQcStatus;
import org.openelisglobal.qc.dto.AnalyzerManualQcStatus.Lot;
import org.openelisglobal.qc.dto.AnalyzerManualQcStatus.Run;
import org.openelisglobal.qc.dto.AnalyzerManualQcStatus.State;
import org.openelisglobal.qc.form.AnalyzerManualQcForm;
import org.openelisglobal.qc.valueholder.QCControlLot;
import org.openelisglobal.qc.valueholder.QCResult;
import org.openelisglobal.systemuser.service.SystemUserService;
import org.openelisglobal.systemuser.valueholder.SystemUser;
import org.openelisglobal.test.service.TestService;
import org.openelisglobal.test.valueholder.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyzerManualQcServiceImpl implements AnalyzerManualQcService {

    @Autowired
    private AnalyzerService analyzerService;

    @Autowired
    private QCControlLotDAO controlLotDAO;

    @Autowired
    private QCResultDAO resultDAO;

    @Autowired
    private QCResultService qcResultService;

    @Autowired
    private SystemUserService systemUserService;

    @Autowired
    private TestService testService;

    private Clock clock = Clock.systemDefaultZone();

    /** Tests pin the clock so that DAILY and hour windows are deterministic. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public AnalyzerManualQcStatus getStatus(String analyzerId) {
        Analyzer analyzer = analyzerService.get(analyzerId);
        LocalDateTime now = LocalDateTime.now(clock);
        Names names = new Names();

        Map<String, LocalDateTime> lastPassByLot = new HashMap<>();
        for (Object[] row : resultDAO.findLatestManualPassPerLot(analyzerId)) {
            if (row[0] != null && row[1] != null) {
                lastPassByLot.put((String) row[0], ((Timestamp) row[1]).toLocalDateTime());
            }
        }

        List<Lot> lots = new ArrayList<>();
        boolean qcRequired = false;
        boolean overdue = false;
        LocalDateTime nextDueAt = null;
        for (QCControlLot lot : controlLotDAO.getUsableByInstrument(analyzerId)) {
            LocalDateTime lastPass = lastPassByLot.get(lot.getId());
            LocalDateTime due = ManualQcFrequencyEvaluator.dueAt(lot.getQcFrequencyType(), lot.getQcFrequencyHours(),
                    lastPass);
            boolean lotOverdue = ManualQcFrequencyEvaluator.isOverdue(lot.getQcFrequencyType(),
                    lot.getQcFrequencyHours(), lastPass, now);
            boolean lotRequired = Boolean.TRUE.equals(lot.getQcRequired()) && lot.getQcFrequencyType() != null;
            if (lotRequired) {
                qcRequired = true;
                overdue |= lotOverdue;
                if (due != null && (nextDueAt == null || due.isBefore(nextDueAt))) {
                    nextDueAt = due;
                }
            }
            lots.add(new Lot(lot.getId(), lot.getProductName(), lot.getLotNumber(), lot.getControlLevel(),
                    lot.getTestId(), names.test(lot.getTestId()), lot.getStatus(),
                    lot.getQcFrequencyType() == null ? null : lot.getQcFrequencyType().name(),
                    lot.getQcFrequencyHours(), lotRequired, lastPass, due, lotRequired && lotOverdue));
        }

        List<QCResult> latest = resultDAO.findManualByInstrument(analyzerId, 1);
        Run lastRun = latest.isEmpty() ? null : toRun(latest.get(0), names);

        State state;
        if (lastRun != null && isFailing(latest.get(0))) {
            state = State.FAILED;
        } else if (overdue) {
            state = State.OVERDUE;
        } else if (lastRun == null) {
            state = State.NOT_RUN;
        } else {
            state = State.PASS;
        }
        return new AnalyzerManualQcStatus(analyzerId, analyzer.getName(), state, qcRequired, overdue, lastRun,
                nextDueAt, lots, now, false, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Run> getHistory(String analyzerId, int maxRows) {
        analyzerService.get(analyzerId);
        Names names = new Names();
        return resultDAO.findManualByInstrument(analyzerId, maxRows).stream().map(r -> toRun(r, names)).toList();
    }

    @Override
    @Transactional
    public Run record(String analyzerId, AnalyzerManualQcForm run, int recorderUserId) {
        analyzerService.get(analyzerId);
        int performedBy = recorderUserId;
        if (run.performedById() != null && !run.performedById().isBlank()) {
            try {
                systemUserService.get(run.performedById());
            } catch (ObjectNotFoundException e) {
                throw new IllegalArgumentException("Performed By is not a system user: " + run.performedById());
            }
            performedBy = Integer.parseInt(run.performedById());
        }
        QCResult saved = qcResultService.createAnalyzerManualQCResult(analyzerId, run, recorderUserId, performedBy);
        return toRun(saved, new Names());
    }

    /**
     * A run fails QC when the technician judged it Fail, or when Westgard
     * evaluation rejected a run the technician passed.
     */
    private static boolean isFailing(QCResult result) {
        return (result.getQualitativeOutcome() != null && result.getQualitativeOutcome().isFailing())
                || "REJECTED".equals(result.getResultStatus());
    }

    private Run toRun(QCResult r, Names names) {
        Integer performer = r.getTechnicianId() != null ? r.getTechnicianId() : r.getSystemUserId();
        return new Run(r.getId(), r.getRunDateTime() == null ? null : r.getRunDateTime().toLocalDateTime(),
                names.user(performer), r.getQualitativeOutcome() == null ? null : r.getQualitativeOutcome().name(),
                r.getResultStatus(), r.getResultValue(), r.getUnitOfMeasure(), r.getExternalNotes(),
                r.getControlLotId(), r.getControlLabel(), names.test(r.getTestId()),
                r.getEntryPoint() == null ? null : r.getEntryPoint().name());
    }

    /** Per-request lookups, so a history page resolves each user and test once. */
    private final class Names {
        private final Map<Integer, String> users = new HashMap<>();
        private final Map<String, String> tests = new HashMap<>();

        String user(Integer id) {
            if (id == null) {
                return null;
            }
            return users.computeIfAbsent(id, key -> {
                try {
                    SystemUser user = systemUserService.get(String.valueOf(key));
                    return user.getDisplayName();
                } catch (ObjectNotFoundException e) {
                    return String.valueOf(key);
                }
            });
        }

        String test(String id) {
            if (id == null) {
                return null;
            }
            return tests.computeIfAbsent(id, key -> {
                Test test = testService.getTestById(key);
                return test == null ? key : Objects.requireNonNullElse(test.getLocalizedName(), key);
            });
        }
    }

}
