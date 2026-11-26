import type { components } from "./generated/public";

export type ApiErrorBody = components["schemas"]["ApiError"];
type TransportError = Omit<ApiErrorBody, "code"> & { code: "HTTP_ERROR" };

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly body: ApiErrorBody | TransportError,
  ) {
    super(body.message);
    this.name = "ApiError";
  }
}

// Pass credentials per request. Never persist tokens or provider keys in this module.
export async function apiRequest<T>(
  path: `/api/v1/${string}`,
  options: RequestInit & { accessToken?: string } = {},
): Promise<T> {
  const { accessToken, ...init } = options;
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body) headers.set("Content-Type", "application/json");
  if (accessToken) headers.set("Authorization", `Bearer ${accessToken}`);
  const baseUrl =
    process.env.NEXT_PUBLIC_CORE_API_URL ?? "http://localhost:8080";
  const response = await fetch(`${baseUrl}${path}`, {
    ...init,
    headers,
    cache: "no-store",
  });
  if (!response.ok) {
    const payload = (await response.json().catch(() => null)) as {
      error?: ApiErrorBody;
    } | null;
    throw new ApiError(
      response.status,
      payload?.error ?? {
        code: "HTTP_ERROR",
        message: "Không thể xử lý yêu cầu.",
        retryable: false,
        correlationId: response.headers.get("X-Correlation-Id") ?? "",
      },
    );
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}
