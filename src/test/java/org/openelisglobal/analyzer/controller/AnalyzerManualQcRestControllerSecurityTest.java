package org.openelisglobal.analyzer.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import org.hibernate.ObjectNotFoundException;
import org.junit.Before;
import org.junit.Test;
import org.openelisglobal.common.action.IActionConstants;
import org.openelisglobal.login.valueholder.UserSessionData;
import org.openelisglobal.qc.dto.AnalyzerManualQcStatus;
import org.openelisglobal.qc.service.AnalyzerManualQcService;
import org.openelisglobal.security.SecuritySliceMockMvcTest;
import org.openelisglobal.testsupport.SliceSecurityConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * #3490: each manual QC endpoint enforces its own permission at the API, so a
 * caller without it gets 403 whatever the UI shows. Status follows reading the
 * analyzer, history follows {@code qa.view.qc}, and recording follows the
 * RESULTS role like {@code POST /rest/qc/results}.
 */
@WebAppConfiguration
@ContextConfiguration(classes = { SliceSecurityConfig.class,
        AnalyzerManualQcRestControllerSecurityTest.TestConfig.class })
@TestPropertySource("classpath:common.properties")
public class AnalyzerManualQcRestControllerSecurityTest extends SecuritySliceMockMvcTest {

    private static final String STATUS = "/rest/analyzer/analyzers/7/qc-status";
    private static final String RUNS = "/rest/analyzer/analyzers/7/qc-runs";
    private static final String BODY = "{\"controlLotId\":\"lot-1\",\"outcome\":\"PASS\",\"entryPoint\":\"ANALYZER_IMPORT\"}";

    private AnalyzerManualQcService service;

    @Before
    public void resetStub() {
        service = webApplicationContext.getBean(AnalyzerManualQcService.class);
        org.mockito.Mockito.reset(service);
        AnalyzerManualQcStatus status = new AnalyzerManualQcStatus("7", "Mindray BS-240",
                AnalyzerManualQcStatus.State.NOT_RUN, false, false, null, null, List.of(), LocalDateTime.now(), false,
                false);
        when(service.getStatus("7")).thenReturn(status);
        when(service.getHistory(eq("7"), anyInt())).thenReturn(List.of());
        when(service.record(eq("7"), any(), anyInt()))
                .thenReturn(new AnalyzerManualQcStatus.Run("r1", LocalDateTime.now(), "Doe", "PASS", "ACCEPTED", null,
                        null, null, "lot-1", "L1", "Hb", "ANALYZER_IMPORT"));
    }

    private UserSessionData sessionUser() {
        UserSessionData data = new UserSessionData();
        data.setSytemUserId(1);
        return data;
    }

    @Test
    public void status_receptionRoleReturns403() throws Exception {
        mockMvc.perform(get(STATUS).with(user("front").roles("RECEPTION"))).andExpect(status().isForbidden());
    }

    @Test
    public void status_analyserImportRoleReturns200WithPermissionHints() throws Exception {
        mockMvc.perform(get(STATUS).with(user("imp").roles("ANALYSER_IMPORT"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NOT_RUN")).andExpect(jsonPath("$.canRecord").value(false))
                .andExpect(jsonPath("$.canViewHistory").value(false));
    }

    @Test
    public void status_resultsRoleMayRecord() throws Exception {
        mockMvc.perform(get(STATUS).with(user("tech").roles("RESULTS"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.canRecord").value(true));
    }

    @Test
    public void status_unknownAnalyzerReturns404() throws Exception {
        when(service.getStatus("7")).thenThrow(new ObjectNotFoundException("7", "Analyzer"));
        mockMvc.perform(get(STATUS).with(user("tech").roles("RESULTS"))).andExpect(status().isNotFound());
    }

    @Test
    public void history_withoutQcViewReturns403() throws Exception {
        mockMvc.perform(get(RUNS).with(user("tech").roles("RESULTS"))).andExpect(status().isForbidden());
    }

    @Test
    public void history_qcViewAuthorityReturns200() throws Exception {
        mockMvc.perform(get(RUNS).with(user("qc").authorities(new SimpleGrantedAuthority("qa.view.qc"))))
                .andExpect(status().isOk());
    }

    @Test
    public void record_qcViewAloneReturns403AndSavesNothing() throws Exception {
        mockMvc.perform(post(RUNS).with(user("qc").authorities(new SimpleGrantedAuthority("qa.view.qc")))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        verify(service, never()).record(anyString(), any(), anyInt());
    }

    @Test
    public void record_analyserImportAloneReturns403() throws Exception {
        mockMvc.perform(post(RUNS).with(user("imp").roles("ANALYSER_IMPORT")).contentType(MediaType.APPLICATION_JSON)
                .content(BODY)).andExpect(status().isForbidden());
    }

    @Test
    public void record_resultsRoleReturns201WithRunAndRefreshedStatus() throws Exception {
        mockMvc.perform(post(RUNS).with(user("tech").roles("RESULTS"))
                .sessionAttr(IActionConstants.USER_SESSION_DATA, sessionUser()).contentType(MediaType.APPLICATION_JSON)
                .content(BODY)).andExpect(status().isCreated()).andExpect(jsonPath("$.run.id").value("r1"))
                .andExpect(jsonPath("$.status.state").value("NOT_RUN"));
    }

    @Test
    public void record_globalAdminReturns201() throws Exception {
        mockMvc.perform(post(RUNS).with(user("admin").roles("GLOBAL_ADMIN"))
                .sessionAttr(IActionConstants.USER_SESSION_DATA, sessionUser()).contentType(MediaType.APPLICATION_JSON)
                .content(BODY)).andExpect(status().isCreated());
    }

    @Test
    public void record_rejectedRunReturns400WithMessage() throws Exception {
        when(service.record(eq("7"), any(), anyInt()))
                .thenThrow(new IllegalArgumentException("Control lot lot-1 does not belong to analyzer 7"));
        mockMvc.perform(post(RUNS).with(user("tech").roles("RESULTS"))
                .sessionAttr(IActionConstants.USER_SESSION_DATA, sessionUser()).contentType(MediaType.APPLICATION_JSON)
                .content(BODY)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Control lot lot-1 does not belong to analyzer 7"));
    }

    @Configuration
    static class TestConfig {

        @Bean
        AnalyzerManualQcService analyzerManualQcService() {
            return mock(AnalyzerManualQcService.class);
        }

        @Bean
        AnalyzerManualQcRestController analyzerManualQcRestController(AnalyzerManualQcService service) {
            AnalyzerManualQcRestController controller = new AnalyzerManualQcRestController();
            ReflectionTestUtils.setField(controller, "manualQcService", service);
            return controller;
        }
    }
}
