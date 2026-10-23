vi.mock("./manualQcApi", async (importOriginal) => ({
  ...(await importOriginal()),
  fetchManualQcStatus: vi.fn(),
  recordManualQc: vi.fn(),
  fetchUsers: vi.fn(),
}));

import React from "react";
import { render, screen, within } from "@testing-library/react";
import "@testing-library/jest-dom";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import { IntlProvider } from "react-intl";
import { MemoryRouter } from "react-router-dom";
import AnalyzerManualQcPanel from "./AnalyzerManualQcPanel";
import { validateRun } from "./QcRecordForm";
import { fetchManualQcStatus, fetchUsers, recordManualQc } from "./manualQcApi";
import UserSessionDetailsContext from "../../../UserSessionDetailsContext";
import { ConfigurationContext } from "../../layout/Layout";
import en from "../../../languages/en.json";
import fr from "../../../languages/fr.json";

const LOT = {
  id: "lot-1",
  label: "Chem control",
  lotNumber: "L-77",
  controlLevel: "NORMAL",
  testId: "6601",
  testName: "Glucose",
  status: "ACTIVE",
  frequencyType: "DAILY",
  qcRequired: true,
  overdue: false,
};

const status = (overrides = {}) => ({
  analyzerId: "7",
  analyzerName: "Mindray BS-240",
  state: "PASS",
  qcRequired: true,
  overdue: false,
  lastRun: {
    id: "r1",
    runDateTime: "2026-10-20T06:30:00",
    performedBy: "Jane Tech",
    outcome: "PASS",
    resultStatus: "ACCEPTED",
    resultValue: 4.9,
    unitOfMeasure: "mmol/L",
    notes: "level 1 ok",
    controlLotId: "lot-1",
  },
  nextDueAt: "2026-10-21T00:00:00",
  lots: [LOT],
  evaluatedAt: "2026-10-20T10:00:00",
  canRecord: true,
  canViewHistory: true,
  ...overrides,
});

const overdueNeverRun = status({
  state: "OVERDUE",
  overdue: true,
  lastRun: undefined,
  nextDueAt: undefined,
});

const renderPanel = ({
  props = {},
  locale = "en",
  messages = en,
  dateLocale = "en-US",
} = {}) =>
  render(
    <MemoryRouter>
      <ConfigurationContext.Provider
        value={{ configurationProperties: { DEFAULT_DATE_LOCALE: dateLocale } }}
      >
        <UserSessionDetailsContext.Provider
          value={{ userSessionDetails: { userId: "1" } }}
        >
          <IntlProvider locale={locale} messages={messages}>
            <AnalyzerManualQcPanel analyzerId="7" {...props} />
          </IntlProvider>
        </UserSessionDetailsContext.Provider>
      </ConfigurationContext.Provider>
    </MemoryRouter>,
  );

const respondWith = (body) =>
  fetchManualQcStatus.mockImplementation((_id, callback) => callback(body));

describe("AnalyzerManualQcPanel", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fetchUsers.mockImplementation((callback) =>
      callback([
        { id: "1", value: "Jane Tech" },
        { id: "7701", value: "Bench Tech" },
      ]),
    );
  });

  it("shows the last manual run from a single status request", async () => {
    respondWith(status());
    renderPanel();

    expect(await screen.findByText("Manual QC Status")).toBeInTheDocument();
    expect(screen.getByTestId("manual-qc-state")).toHaveTextContent("QC Pass");
    const summary = screen.getByTestId("manual-qc-last-run");
    expect(within(summary).getByText("Jane Tech")).toBeInTheDocument();
    expect(within(summary).getByText("Pass")).toBeInTheDocument();
    expect(
      within(summary).getByText("4.9 mmol/L · level 1 ok"),
    ).toBeInTheDocument();
    expect(fetchManualQcStatus).toHaveBeenCalledTimes(1);
    expect(fetchManualQcStatus.mock.calls[0][0]).toBe("7");
    expect(screen.queryByTestId("manual-qc-overdue")).not.toBeInTheDocument();
  });

  it("warns when QC is overdue and has never been run", async () => {
    respondWith(overdueNeverRun);
    renderPanel();

    const warning = await screen.findByTestId("manual-qc-overdue");
    expect(warning).toHaveTextContent(
      "Manual QC has not been performed within the required timeframe for this analyzer. Last QC: Never.",
    );
    expect(screen.getByTestId("manual-qc-state")).toHaveTextContent("Overdue");
  });

  it("does not warn when the analyzer does not require QC", async () => {
    respondWith({ ...overdueNeverRun, qcRequired: false, state: "NOT_RUN" });
    renderPanel();
    await screen.findByText("Manual QC Status");
    expect(screen.queryByTestId("manual-qc-overdue")).not.toBeInTheDocument();
    expect(screen.getByTestId("manual-qc-state")).toHaveTextContent("Not Run");
  });

  it("records a pass inline, then updates the status and dismisses the warning", async () => {
    respondWith(overdueNeverRun);
    recordManualQc.mockImplementation((_id, _run, callback) =>
      callback({ run: { id: "r2" }, status: status() }),
    );
    renderPanel();

    await userEvent.click(await screen.findByTestId("manual-qc-record"));
    // inline, not a modal
    expect(screen.getByTestId("manual-qc-form")).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    await userEvent.click(screen.getByLabelText("Pass"));
    await userEvent.type(screen.getByLabelText("Value (optional)"), "4.9");
    await userEvent.click(screen.getByTestId("manual-qc-save"));

    expect(recordManualQc).toHaveBeenCalledTimes(1);
    const [analyzerId, run] = recordManualQc.mock.calls[0];
    expect(analyzerId).toBe("7");
    expect(run).toMatchObject({
      controlLotId: "lot-1",
      outcome: "PASS",
      resultValue: "4.9",
      performedById: "1",
      entryPoint: "ANALYZER_IMPORT",
    });
    expect(run.runDateTime).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:00$/);

    expect(await screen.findByTestId("manual-qc-saved")).toBeInTheDocument();
    expect(screen.queryByTestId("manual-qc-form")).not.toBeInTheDocument();
    expect(screen.queryByTestId("manual-qc-overdue")).not.toBeInTheDocument();
    expect(screen.getByTestId("manual-qc-state")).toHaveTextContent("QC Pass");
    expect(fetchManualQcStatus).toHaveBeenCalledTimes(1);
  });

  it("shows an error recommending a re-run after a failed QC", async () => {
    respondWith(status());
    recordManualQc.mockImplementation((_id, _run, callback) =>
      callback({
        run: { id: "r3" },
        status: status({
          state: "FAILED",
          lastRun: { ...status().lastRun, outcome: "FAIL" },
        }),
      }),
    );
    renderPanel({
      props: { entryPoint: "ANALYZER_LIST", startWithForm: true },
    });

    await userEvent.click(await screen.findByLabelText("Fail"));
    await userEvent.click(screen.getByTestId("manual-qc-save"));

    expect(recordManualQc.mock.calls[0][1].entryPoint).toBe("ANALYZER_LIST");
    expect(await screen.findByTestId("manual-qc-failed")).toHaveTextContent(
      "QC failed for Mindray BS-240. Review and re-run QC before accepting patient results.",
    );
    expect(screen.getByTestId("manual-qc-state")).toHaveTextContent(
      "QC Failed",
    );
  });

  it("does not save without a result and says why", async () => {
    respondWith(status());
    renderPanel({ props: { startWithForm: true } });
    await userEvent.click(await screen.findByTestId("manual-qc-save"));
    expect(recordManualQc).not.toHaveBeenCalled();
    expect(screen.getByText("Select Pass or Fail")).toBeInTheDocument();
  });

  it("shows the server's reason when a save is refused", async () => {
    respondWith(status());
    recordManualQc.mockImplementation((_id, _run, callback) =>
      callback({ status: 400, message: "Control lot is not usable" }),
    );
    renderPanel({ props: { startWithForm: true } });
    await userEvent.click(await screen.findByLabelText("Pass"));
    await userEvent.click(screen.getByTestId("manual-qc-save"));
    expect(
      await screen.findByText(
        "The QC result could not be saved. Control lot is not usable",
      ),
    ).toBeInTheDocument();
    expect(screen.getByTestId("manual-qc-form")).toBeInTheDocument();
  });

  it("hides Record QC and the history link for users without those rights", async () => {
    respondWith(status({ canRecord: false, canViewHistory: false }));
    renderPanel();
    await screen.findByText("Manual QC Status");
    expect(screen.queryByTestId("manual-qc-record")).not.toBeInTheDocument();
    expect(screen.queryByTestId("manual-qc-history")).not.toBeInTheDocument();
  });

  it("links View Full History to the analyzer's QC history", async () => {
    respondWith(status());
    renderPanel();
    expect(await screen.findByTestId("manual-qc-history")).toHaveAttribute(
      "href",
      "/analyzers/qc/instruments/7",
    );
  });

  it("explains that a control lot is needed when the analyzer has none", async () => {
    respondWith(status({ lots: [], lastRun: undefined, state: "NOT_RUN" }));
    renderPanel();
    expect(await screen.findByTestId("manual-qc-no-lots")).toBeInTheDocument();
    expect(screen.queryByTestId("manual-qc-record")).not.toBeInTheDocument();
  });

  it("shows an error when the status cannot be loaded", async () => {
    respondWith(undefined);
    renderPanel();
    expect(
      await screen.findByTestId("manual-qc-load-error"),
    ).toBeInTheDocument();
  });

  /**
   * The acceptance criteria ask for the feature to be tested with the French
   * language file. Our keys reach fr.json through Transifex after merge, so the
   * test layers French strings for them over the shipped fr.json, and checks
   * that every string on screen comes from the messages (none is hardcoded
   * English) and that dates follow the French locale.
   */
  it("renders entirely from the French language file", async () => {
    const french = {
      ...en,
      ...fr,
      "analyzer.manualQc.title": "Statut du CQ manuel",
      "analyzer.manualQc.state.OVERDUE": "En retard",
      "analyzer.manualQc.state.PASS": "CQ conforme",
      "analyzer.manualQc.lastRun": "Dernier CQ",
      "analyzer.manualQc.performedBy": "Effectué par",
      "analyzer.manualQc.result": "Résultat",
      "analyzer.manualQc.value": "Valeur",
      "analyzer.manualQc.nextDue": "Prochain CQ prévu",
      "analyzer.manualQc.never": "Jamais",
      "analyzer.manualQc.overdue.title": "CQ en retard",
      "analyzer.manualQc.overdue.message":
        "Le CQ manuel n'a pas été réalisé dans le délai requis pour cet analyseur. Dernier CQ : {lastQc}.",
      "analyzer.manualQc.recordQc": "Enregistrer un CQ",
      "analyzer.manualQc.viewHistory": "Voir l'historique complet",
      "analyzer.manualQc.outcome.PASS": "Conforme",
      "analyzer.manualQc.outcome.FAIL": "Non conforme",
      "analyzer.manualQc.form.controlLot": "Lot de contrôle",
      "analyzer.manualQc.form.lotLabel":
        "{product} ({level}), {test}, lot {lot}",
      "analyzer.manualQc.form.date": "Date du CQ",
      "analyzer.manualQc.form.time": "Heure du CQ",
      "analyzer.manualQc.form.result": "Résultat",
      "analyzer.manualQc.form.value": "Valeur (facultatif)",
      "analyzer.manualQc.form.valueHelper": "La valeur mesurée du contrôle",
      "analyzer.manualQc.form.notes": "Remarques (facultatif)",
      "analyzer.manualQc.form.performedBy": "Effectué par",
      "analyzer.manualQc.form.save": "Enregistrer le résultat",
      "analyzer.manualQc.form.cancel": "Annuler",
      "error.analyzerQc.qcRequired": "Choisissez Conforme ou Non conforme",
      "error.analyzerQc.qcDateFuture": "La date du CQ ne peut pas être future",
    };
    respondWith(overdueNeverRun);
    renderPanel({ locale: "fr", messages: french, dateLocale: "fr-FR" });

    expect(await screen.findByText("Statut du CQ manuel")).toBeInTheDocument();
    expect(screen.getByTestId("manual-qc-overdue")).toHaveTextContent(
      "Dernier CQ : Jamais.",
    );
    await userEvent.click(screen.getByTestId("manual-qc-record"));
    await userEvent.click(screen.getByTestId("manual-qc-save"));
    expect(
      screen.getByText("Choisissez Conforme ou Non conforme"),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Date du CQ").value).toMatch(
      /^\d{2}\/\d{2}\/\d{4}$/,
    );

    const englishOnly = [
      "Manual QC Status",
      "Overdue",
      "Never",
      "Record QC",
      "View Full History",
      "Pass",
      "Fail",
      "Save QC result",
      "Cancel",
      "Select Pass or Fail",
      "QC date",
    ];
    for (const text of englishOnly) {
      expect(screen.queryByText(text)).not.toBeInTheDocument();
    }
  });
});

describe("validateRun", () => {
  const now = new Date(2026, 9, 20, 10, 0);
  const valid = {
    controlLotId: "lot-1",
    outcome: "PASS",
    value: "",
    date: "10/20/2026",
    time: "09:00",
    performedById: "1",
  };

  it("accepts a complete run", () => {
    expect(validateRun(valid, now, "en-US")).toEqual({});
  });

  it("refuses a run in the future", () => {
    expect(validateRun({ ...valid, time: "11:00" }, now, "en-US").date).toBe(
      "error.analyzerQc.qcDateFuture",
    );
    expect(
      validateRun({ ...valid, date: "21/10/2026" }, now, "fr-FR").date,
    ).toBe("error.analyzerQc.qcDateFuture");
  });

  it("refuses a missing result, lot or performer and a non-numeric value", () => {
    const errors = validateRun(
      {
        ...valid,
        outcome: "",
        controlLotId: "",
        performedById: "",
        value: "abc",
      },
      now,
      "en-US",
    );
    expect(errors).toEqual({
      outcome: "error.analyzerQc.qcRequired",
      controlLotId: "analyzer.manualQc.form.controlLotRequired",
      performedById: "error.analyzerQc.performedByRequired",
      value: "analyzer.manualQc.form.valueInvalid",
    });
  });
});
