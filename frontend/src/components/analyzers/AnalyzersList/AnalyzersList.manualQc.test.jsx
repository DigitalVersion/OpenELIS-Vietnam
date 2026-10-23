vi.mock("../../../services/analyzerService", () => ({
  activateAnalyzer: vi.fn(),
  createAnalyzer: vi.fn(),
  deactivateAnalyzer: vi.fn(),
  getAnalyzer: vi.fn(),
  getAnalyzers: vi.fn(),
  getAnalyzerDeliveryIssues: vi.fn(),
  getBridgePairing: vi.fn(),
  getAnalyzerLabUnits: vi.fn(),
  getAnalyzerTypeCatalog: vi.fn(),
  getAnalyzerTypeMapping: vi.fn(),
  reactivateAnalyzer: vi.fn(),
  updateAnalyzer: vi.fn(),
}));

// The panel has its own tests; here only the entry point matters.
vi.mock("../AnalyzerManualQc/AnalyzerManualQcPanel", () => ({
  default: ({ analyzerId, entryPoint, startWithForm, onClose }) => (
    <div data-testid="manual-qc-panel-stub">
      {`${analyzerId}|${entryPoint}|${startWithForm ? "form" : "status"}`}
      <button type="button" onClick={onClose}>
        close-stub
      </button>
    </div>
  ),
}));

import React from "react";
import { act, render, screen, within } from "@testing-library/react";
import "@testing-library/jest-dom";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import { IntlProvider } from "react-intl";
import { BrowserRouter } from "react-router-dom";
import AnalyzersList from "./AnalyzersList";
import {
  getAnalyzers,
  getAnalyzerLabUnits,
  getAnalyzerTypeCatalog,
} from "../../../services/analyzerService";
import messages from "../../../languages/en.json";

const analyzer = {
  id: "7",
  name: "Mindray BS-240",
  analyzerType: "CHEMISTRY",
  profileId: "site.synthetic-analyzer",
  profileRevision: 1,
  connected: true,
  testUnitIds: [],
  active: true,
  lifecycleStage: "ACTIVE",
  status: "ACTIVE",
  heldResultCount: 0,
};

describe("AnalyzersList: Record Manual QC (#3490)", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(Element.prototype, "getBoundingClientRect").mockReturnValue({
      bottom: 40,
      height: 40,
      left: 0,
      right: 160,
      top: 0,
      width: 160,
      x: 0,
      y: 0,
      toJSON: () => ({}),
    });
    window.history.replaceState({}, "", "/analyzers");
    getAnalyzerTypeCatalog.mockImplementation((callback) =>
      callback({
        schemaVersion: "1.0",
        catalogFingerprint: `sha256:${"a".repeat(64)}`,
        summary: { total: 0, inUse: 0, needsAttention: 0, deactivated: 0 },
        types: [],
      }),
    );
    getAnalyzerLabUnits.mockImplementation((callback) => callback([]));
    getAnalyzers.mockImplementation((_filters, callback) => {
      act(() => callback({ analyzers: [analyzer] }));
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  const renderList = () =>
    render(
      <BrowserRouter>
        <IntlProvider locale="en" messages={messages}>
          <AnalyzersList />
        </IntlProvider>
      </BrowserRouter>,
    );

  it("offers Record Manual QC in the row overflow menu", async () => {
    renderList();
    await userEvent.click(await screen.findByTestId("analyzer-row-overflow-7"));
    expect(
      await screen.findByTestId("analyzer-action-record-manual-qc-7"),
    ).toHaveTextContent("Record Manual QC");
  });

  it("expands an inline row with the form for that analyzer, and closes it", async () => {
    renderList();
    await userEvent.click(await screen.findByTestId("analyzer-row-overflow-7"));
    await userEvent.click(
      await screen.findByTestId("analyzer-action-record-manual-qc-7"),
    );

    const row = await screen.findByTestId("analyzer-manual-qc-row-7");
    expect(row.tagName).toBe("TR");
    expect(within(row).getByTestId("manual-qc-panel-stub")).toHaveTextContent(
      "7|ANALYZER_LIST|form",
    );
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    await userEvent.click(within(row).getByText("close-stub"));
    expect(
      screen.queryByTestId("analyzer-manual-qc-row-7"),
    ).not.toBeInTheDocument();
  });
});
