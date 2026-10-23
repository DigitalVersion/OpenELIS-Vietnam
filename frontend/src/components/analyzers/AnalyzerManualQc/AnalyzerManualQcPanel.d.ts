import type { ComponentType } from "react";

export type ManualQcEntryPoint = "ANALYZER_IMPORT" | "ANALYZER_LIST";

export interface AnalyzerManualQcPanelProps {
  analyzerId: string;
  entryPoint?: ManualQcEntryPoint;
  startWithForm?: boolean;
  onClose?: () => void;
}

declare const AnalyzerManualQcPanel: ComponentType<AnalyzerManualQcPanelProps>;

export default AnalyzerManualQcPanel;
