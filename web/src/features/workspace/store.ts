import { create } from "zustand";

// UI state only. History, permissions and request status belong to Core / TanStack Query.
interface WorkspaceUiState {
  dualPaneOpen: boolean;
  setDualPaneOpen: (open: boolean) => void;
}

export const useWorkspaceUi = create<WorkspaceUiState>((set) => ({
  dualPaneOpen: false,
  setDualPaneOpen: (dualPaneOpen) => set({ dualPaneOpen }),
}));
