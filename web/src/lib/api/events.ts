import type { components } from "./generated/public";

type Schemas = components["schemas"];
export const PUBLIC_SSE_EVENTS = {
  "request.accepted": "EventAccepted",
  "retrieval.degraded": "EventDegraded",
  "assistant.started": "EventStarted",
  "assistant.delta": "EventDelta",
  "assistant.completed": "EventCompleted",
  "request.failed": "EventFailed",
  "request.cancelled": "EventCancelled",
} as const satisfies Record<string, keyof Schemas>;

export type PublicEventName = keyof typeof PUBLIC_SSE_EVENTS;
export type PublicSseEvent = {
  [E in PublicEventName]: {
    /** Keep SSE event IDs as decimal strings for Last-Event-ID. */
    id: string;
    event: E;
    data: Schemas[(typeof PUBLIC_SSE_EVENTS)[E]];
  };
}[PublicEventName];
export const TERMINAL_EVENTS: ReadonlySet<PublicEventName> = new Set([
  "assistant.completed",
  "request.failed",
  "request.cancelled",
]);
