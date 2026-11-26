export interface paths {
  "/api/v1/ai/chat-requests": {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Accept a chat request without provider dispatch in the HTTP transaction */
    post: operations["createChatRequest"];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  "/api/v1/ai/requests/{requestId}/events": {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get: operations["readChatEvents"];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  "/api/v1/ai/requests/{requestId}": {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get: operations["getChatRequestStatus"];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  "/api/v1/ai/requests/{requestId}/cancel": {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    post: operations["cancelChatRequest"];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
}
export type webhooks = Record<string, never>;
export interface components {
  schemas: {
    /** @enum {string} */
    RequestState:
      | "ACCEPTED"
      | "IN_PROGRESS"
      | "COMPLETED"
      | "FAILED"
      | "CANCELLED";
    /** @enum {string} */
    TerminalState: "COMPLETED" | "FAILED" | "CANCELLED";
    /** @enum {string} */
    PrivacyTier: "FREE" | "PAID" | "UNKNOWN";
    /** @enum {string} */
    UsageStatus: "AVAILABLE" | "PARTIAL" | "UNAVAILABLE";
    /** @enum {string} */
    LinkStatus: "AVAILABLE" | "UNAVAILABLE";
    /** @enum {string} */
    QuoteSourceKind:
      | "MAIN_TURN"
      | "SUB_BRANCH_TURN"
      | "KB_ITEM"
      | "SOT_RECORD"
      | "CHAPTER_SUMMARY";
    /** @enum {string} */
    CancelReason:
      | "USER_CANCELLED"
      | "PROJECT_DELETING"
      | "CREDENTIAL_REMOVED"
      | "SOURCE_INVALIDATED";
    /** @enum {string} */
    RetrievalComponent:
      | "VECTOR_SEARCH"
      | "KEYWORD_SEARCH"
      | "SOT_LOOKUP"
      | "ANCESTOR_HISTORY";
    /** @enum {string} */
    DegradedStatus: "PARTIAL" | "UNAVAILABLE";
    /** @enum {string} */
    ErrorCode:
      | "VALIDATION_ERROR"
      | "INVALID_SSE_CURSOR"
      | "UNAUTHENTICATED"
      | "ACCOUNT_DISABLED"
      | "FORBIDDEN"
      | "RESOURCE_NOT_FOUND"
      | "PROJECT_DELETING"
      | "CHAT_SCOPE_LOCKED"
      | "IDEMPOTENCY_KEY_REUSED"
      | "CHAT_REQUEST_IN_PROGRESS"
      | "CONFLICT_STALE_REVISION"
      | "SOURCE_DELETED"
      | "SSE_CURSOR_EXPIRED"
      | "USER_CREDENTIAL_UNAVAILABLE"
      | "DISCLOSURE_REQUIRED"
      | "INVALID_EXPLICIT_REFERENCE"
      | "EXPLICIT_REFERENCE_BUDGET_EXCEEDED"
      | "STAGE_CONTEXT_LIMIT"
      | "BRANCH_CONTEXT_LIMIT"
      | "APP_USAGE_LIMIT_EXCEEDED"
      | "PROVIDER_RATE_LIMITED"
      | "PROVIDER_QUOTA_EXHAUSTED"
      | "PLATFORM_CREDENTIAL_UNAVAILABLE"
      | "PROVIDER_UNAVAILABLE"
      | "WORKER_LEASE_EXPIRED";
    ApiError: {
      code: components["schemas"]["ErrorCode"];
      message: string;
      retryable: boolean;
      /** Format: uuid */
      correlationId: string;
      details?: {
        [key: string]: unknown;
      };
    };
    ErrorEnvelope: {
      error: components["schemas"]["ApiError"];
    };
    TextBlock: {
      /**
       * @description discriminator enum property added by openapi-typescript
       * @enum {string}
       */
      type: "TEXT";
      text: string;
    };
    QuoteRefBlock: {
      /**
       * @description discriminator enum property added by openapi-typescript
       * @enum {string}
       */
      type: "QUOTE_REF";
      quoteRefId: string;
    };
    ContentBlock:
      | components["schemas"]["TextBlock"]
      | components["schemas"]["QuoteRefBlock"];
    Locator: {
      /** Format: int64 */
      charStart: number;
      /** Format: int64 */
      charEnd: number;
      prefixAnchor: string;
      suffixAnchor: string;
    };
    QuoteInput: {
      quoteRefId: string;
      sourceKind: components["schemas"]["QuoteSourceKind"];
      /** Format: uuid */
      sourceId: string;
      sourceRevision: string;
      locator: components["schemas"]["Locator"];
      contentHashSha256Hex: string;
      snapshotText: string;
    };
    StageReference: {
      /**
       * @description discriminator enum property added by openapi-typescript
       * @enum {string}
       */
      granularity: "STAGE";
      /** Format: uuid */
      targetStageId: string;
      targetChapterId?: null;
      targetTurnId?: null;
    };
    ChapterReference: {
      /**
       * @description discriminator enum property added by openapi-typescript
       * @enum {string}
       */
      granularity: "CHAPTER";
      /** Format: uuid */
      targetStageId: string;
      /** Format: uuid */
      targetChapterId: string;
      targetTurnId?: null;
    };
    TurnReference: {
      /**
       * @description discriminator enum property added by openapi-typescript
       * @enum {string}
       */
      granularity: "TURN";
      /** Format: uuid */
      targetStageId: string;
      targetChapterId?: null;
      /** Format: uuid */
      targetTurnId: string;
    };
    MainReference:
      | components["schemas"]["StageReference"]
      | components["schemas"]["ChapterReference"]
      | components["schemas"]["TurnReference"];
    ChatInput: {
      /** Format: uuid */
      projectId: string;
      /** Format: uuid */
      conversationId: string;
      /** Format: uuid */
      mainStageId: string;
      subBranchId?: string | null;
      userContentBlocks: components["schemas"]["ContentBlock"][];
      /** @default [] */
      quotes: components["schemas"]["QuoteInput"][];
      /** @default [] */
      mainReferences: components["schemas"]["MainReference"][];
    };
    ChatAccepted: {
      /** Format: uuid */
      requestId: string;
      /** Format: uuid */
      correlationId: string;
      status: components["schemas"]["RequestState"];
      effectivePrivacyTierPlanned?: components["schemas"]["PrivacyTier"];
      eventsUrl: string;
      statusUrl: string;
      cancelUrl: string;
      /** Format: date-time */
      createdAt: string;
    };
    RequestStatus: {
      /** Format: uuid */
      requestId: string;
      status: components["schemas"]["RequestState"];
      turnId: string | null;
      /** Format: int64 */
      lastEventId: number;
      errorCode: components["schemas"]["ErrorCode"] | null;
      /** Format: date-time */
      createdAt: string;
      finishedAt: string | null;
    };
    CancelResult: {
      /** Format: uuid */
      requestId: string;
      status: components["schemas"]["TerminalState"];
      alreadyTerminal: boolean;
    };
    QuoteSaved: {
      /** Format: uuid */
      quoteRefId: string;
      sourceKind: components["schemas"]["QuoteSourceKind"];
      /** Format: uuid */
      sourceId: string;
      sourceRevision: string;
      locator: components["schemas"]["Locator"];
      contentHashSha256Hex: string;
      snapshotText: string;
      linkStatus: components["schemas"]["LinkStatus"];
      excludeFromAutoKb: boolean;
    };
    Turn: {
      /** Format: uuid */
      id: string;
      /** Format: uuid */
      projectId?: string;
      /** Format: uuid */
      conversationId?: string;
      /** Format: uuid */
      mainStageId: string;
      subBranchId: string | null;
      chapterId?: string | null;
      /** Format: int64 */
      sequenceNo: number;
      /** Format: int64 */
      sourceRevision: number;
      userContentBlocks: components["schemas"]["ContentBlock"][];
      quotes: components["schemas"]["QuoteSaved"][];
      mainReferences: components["schemas"]["MainReference"][];
      assistantText: string;
      /** Format: date-time */
      createdAt: string;
    };
    Turns: {
      items: components["schemas"]["Turn"][];
      nextAfterSequence: number | null;
      hasMore: boolean;
    };
    PublicUsage: {
      totalInputTokens: number | null;
      totalOutputTokens: number | null;
      totalThoughtTokens: number | null;
      totalCachedTokens: number | null;
      totalTokens: number | null;
    };
    EventAccepted: {
      /** Format: uuid */
      requestId: string;
      /** @constant */
      status: "ACCEPTED";
      /** Format: uuid */
      projectId: string;
      /** Format: uuid */
      mainStageId: string;
      subBranchId: string | null;
    };
    DegradedComponent: {
      component: components["schemas"]["RetrievalComponent"];
      status: components["schemas"]["DegradedStatus"];
      reasonCode: string;
    };
    EventDegraded: {
      /** Format: uuid */
      requestId: string;
      overallStatus: components["schemas"]["DegradedStatus"];
      components: components["schemas"]["DegradedComponent"][];
      userMessage: string;
    };
    EventStarted: {
      /** Format: uuid */
      requestId: string;
      effectivePrivacyTier: components["schemas"]["PrivacyTier"];
      mixedTierWarning: boolean;
      platformTasksInUnpaidTier: string[];
      rolloverSuggested: boolean;
      /** Format: int64 */
      estimatedInputTokens: number;
    };
    EventDelta: {
      /** Format: uuid */
      requestId: string;
      text: string;
    };
    EventCompleted: {
      /** Format: uuid */
      requestId: string;
      /** Format: uuid */
      turnId: string;
      /** Format: int64 */
      sequenceNo: number;
      /** Format: int64 */
      sourceRevision: number;
      finishReason: string | null;
      modelUsed: string;
      effectivePrivacyTier: components["schemas"]["PrivacyTier"];
      usageStatus: components["schemas"]["UsageStatus"];
      usage: components["schemas"]["PublicUsage"];
    };
    EventFailed: {
      /** Format: uuid */
      requestId: string;
      errorCode: components["schemas"]["ErrorCode"];
      message: string;
      retryable: boolean;
      retryAfterSeconds: number | null;
    };
    EventCancelled: {
      /** Format: uuid */
      requestId: string;
      reason: components["schemas"]["CancelReason"];
    };
    OffsetPage: {
      items: unknown[];
      /** Format: int64 */
      page: number;
      /** Format: int64 */
      size: number;
      /** Format: int64 */
      totalElements: number;
      /** Format: int64 */
      totalPages: number;
      hasNext: boolean;
    };
  };
  responses: never;
  parameters: never;
  requestBodies: never;
  headers: never;
  pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
  createChatRequest: {
    parameters: {
      query?: never;
      header: {
        "Idempotency-Key": string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        "application/json": components["schemas"]["ChatInput"];
      };
    };
    responses: {
      /** @description Committed acceptance or idempotent replay */
      202: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ChatAccepted"];
        };
      };
      /** @description Normalized API error */
      400: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      401: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      404: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      410: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      422: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      429: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      503: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
    };
  };
  readChatEvents: {
    parameters: {
      query?: {
        cursor?: string;
      };
      header?: {
        "Last-Event-ID"?: string;
      };
      path: {
        requestId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Read committed backlog then live events; header cursor wins; GET never dispatches provider work */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "text/event-stream": string;
        };
      };
      /** @description Normalized API error */
      400: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      401: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      404: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      410: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      422: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      429: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      503: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
    };
  };
  getChatRequestStatus: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        requestId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Current committed request status */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["RequestStatus"];
        };
      };
      /** @description Normalized API error */
      400: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      401: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      404: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      410: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      422: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      429: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      503: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
    };
  };
  cancelChatRequest: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        requestId: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Idempotent application cancellation or existing terminal status */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["CancelResult"];
        };
      };
      /** @description Normalized API error */
      400: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      401: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      404: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      410: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      422: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      429: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
      /** @description Normalized API error */
      503: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["ErrorEnvelope"];
        };
      };
    };
  };
}
