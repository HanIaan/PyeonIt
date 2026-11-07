import type { components } from "./api-types";
import { API_BASE_URL } from "./config";

// 타입은 api/openapi.yaml 에서 생성한다(npm run gen:api). 예: api<Schemas["Me"]>("/api/me")
export type Schemas = components["schemas"];
export type ErrorResponse = Schemas["ErrorResponse"];
export type FieldError = Schemas["FieldError"];

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly fieldErrors: FieldError[] = [],
  ) {
    super(message);
    this.name = "ApiError";
  }
}

type ApiInit = Omit<RequestInit, "body"> & { body?: BodyInit; json?: unknown };

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

function readCookie(name: string): string | undefined {
  if (typeof document === "undefined") return undefined;
  const hit = document.cookie.split("; ").find((c) => c.startsWith(name + "="));
  return hit && decodeURIComponent(hit.slice(name.length + 1));
}

// shortcut: 쿠키를 못 읽는 배포(웹·API 서브도메인 분리)에서는 쓰기마다 /api/csrf 를 한 번 더 부른다. 잦아지면 토큰을 메모이즈하고 CSRF_INVALID 시 1회 재시도로 바꾼다.
async function csrfToken(): Promise<string> {
  return readCookie("XSRF-TOKEN") ?? (await api<Schemas["CsrfToken"]>("/api/csrf")).token;
}

async function toApiError(res: Response): Promise<ApiError> {
  try {
    const e = (await res.json()) as ErrorResponse;
    if (typeof e.code === "string") return new ApiError(res.status, e.code, e.message, e.fieldErrors ?? []);
  } catch {
    // JSON 이 아닌 응답(프록시 오류 등)은 아래 기본값으로
  }
  return new ApiError(res.status, `HTTP_${res.status}`, res.statusText || "요청에 실패했습니다");
}

/** 백엔드 호출. 쿠키 포함, 쓰기 요청엔 X-XSRF-TOKEN 자동 첨부, 2xx 아니면 ApiError 를 던진다. 204 는 undefined. */
export async function api<T>(path: string, { json, ...init }: ApiInit = {}): Promise<T> {
  const method = (init.method ?? "GET").toUpperCase();
  const headers = new Headers(init.headers);
  if (json !== undefined) {
    headers.set("Content-Type", "application/json");
    init.body = JSON.stringify(json);
  }
  if (!SAFE_METHODS.has(method)) headers.set("X-XSRF-TOKEN", await csrfToken());
  const res = await fetch(API_BASE_URL + path, { ...init, method, headers, credentials: "include" });
  if (!res.ok) throw await toApiError(res);
  return (res.status === 204 ? undefined : await res.json()) as T;
}
