package org.openelisglobal.analyzer.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.hibernate.ObjectNotFoundException;
import org.openelisglobal.common.log.LogEvent;
import org.openelisglobal.common.rest.BaseRestController;
import org.openelisglobal.common.util.ControllerUtills;
import org.openelisglobal.qa.security.QaPermissions;
import org.openelisglobal.qc.dto.AnalyzerManualQcStatus;
import org.openelisglobal.qc.form.AnalyzerManualQcForm;
import org.openelisglobal.qc.service.AnalyzerManualQcService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual QC on an analyzer (#3490): the status panel on the analyzer import
 * page and the "Record Manual QC" row on the analyzers list.
 *
 * <p>
 * Permissions follow the endpoints the feature builds on. Reading the status
 * takes the same roles as reading the analyzer; the history is QC history, so
 * it takes {@code qa.view.qc}; recording a run is a results-entry write, so it
 * takes RESULTS like {@code POST /rest/qc/results}. Each check is on the
 * method, not the class, so a caller without it gets 403.
 */
@RestController
@RequestMapping("/rest/analyzer/analyzers/{id}")
public class AnalyzerManualQcRestController extends BaseRestController {

    static final String CAN_VIEW_STATUS = "hasAnyRole('ANALYSER_IMPORT', 'RESULTS', 'ADMIN', 'GLOBAL_ADMIN')";
    static final String CAN_RECORD = "hasRole('RESULTS') or hasRole('GLOBAL_ADMIN')";
    private static final int MAX_HISTORY_ROWS = 500;

    @Autowired
    private AnalyzerManualQcService manualQcService;

    @PreAuthorize(CAN_VIEW_STATUS)
    @GetMapping(value = "/qc-status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AnalyzerManualQcStatus> status(@PathVariable String id) {
        return ResponseEntity.ok(withCallerPermissions(manualQcService.getStatus(id)));
    }

    @PreAuthorize(QaPermissions.VIEW_QC)
    @GetMapping(value = "/qc-runs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<AnalyzerManualQcStatus.Run>> history(@PathVariable String id,
            @RequestParam(defaultValue = "100") int limit) {
        int rows = Math.max(1, Math.min(limit, MAX_HISTORY_ROWS));
        return ResponseEntity.ok(manualQcService.getHistory(id, rows));
    }

    /**
     * Record a run. Returns 201 with the saved run and the analyzer's status
     * re-evaluated after it, so the panel updates from this one response.
     */
    @PreAuthorize(CAN_RECORD)
    @PostMapping(value = "/qc-runs", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> record(@PathVariable String id, @Valid @RequestBody AnalyzerManualQcForm run,
            HttpServletRequest request) {
        int recorder;
        try {
            recorder = Integer.parseInt(ControllerUtills.getSysUserId(request));
        } catch (NumberFormatException | NullPointerException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "No session user"));
        }
        AnalyzerManualQcStatus.Run saved = manualQcService.record(id, run, recorder);
        AnalyzerManualQcStatus status = withCallerPermissions(manualQcService.getStatus(id));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("run", saved, "status", status));
    }

    @ExceptionHandler(ObjectNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(ObjectNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Analyzer not found"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        LogEvent.logWarn(this.getClass().getName(), "record", "rejected manual QC run: " + e.getMessage());
        return ResponseEntity.badRequest()
                .body(Map.of("message", e.getMessage() == null ? "Invalid QC run" : e.getMessage()));
    }

    /**
     * Tell the UI which actions to offer, from the same rules the endpoints
     * enforce. A hint only: hiding a button is not the security boundary.
     */
    private static AnalyzerManualQcStatus withCallerPermissions(AnalyzerManualQcStatus status) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Set<String> granted = auth == null ? Set.of()
                : auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        boolean admin = granted.contains("ROLE_GLOBAL_ADMIN");
        boolean record = admin || granted.contains("ROLE_RESULTS");
        boolean history = admin || granted.contains(QaPermissions.KEY_VIEW_QC);
        return status.withPermissions(record, history);
    }
}
