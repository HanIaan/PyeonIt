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
    readonly nicknameChangeableAt: string | null = null,
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
  return (
    readCookie("XSRF-TOKEN") ??
    (await api<Schemas["CsrfToken"]>("/api/csrf")).token
  );
}

async function toApiError(res: Response): Promise<ApiError> {
  try {
    const e = (await res.json()) as ErrorResponse & {
      nicknameChangeableAt?: string;
    };
    if (typeof e.code === "string")
      return new ApiError(
        res.status,
        e.code,
        typeof e.message === "string" ? e.message : "요청에 실패했습니다.",
        Array.isArray(e.fieldErrors) ? e.fieldErrors : [],
        e.nicknameChangeableAt ?? null,
      );
  } catch {
    // JSON 이 아닌 응답(프록시 오류 등)은 아래 기본값으로
  }
  return new ApiError(
    res.status,
    `HTTP_${res.status}`,
    res.statusText || "요청에 실패했습니다",
  );
}

/** 백엔드 호출. 쿠키 포함, 쓰기 요청엔 X-XSRF-TOKEN 자동 첨부, 2xx 아니면 ApiError 를 던진다. 204 는 undefined. */
export async function api<T>(
  path: string,
  { json, ...init }: ApiInit = {},
): Promise<T> {
  const method = (init.method ?? "GET").toUpperCase();
  const headers = new Headers(init.headers);
  if (json !== undefined) {
    headers.set("Content-Type", "application/json");
    init.body = JSON.stringify(json);
  }
  if (!SAFE_METHODS.has(method)) headers.set("X-XSRF-TOKEN", await csrfToken());
  const res = await fetch(API_BASE_URL + path, {
    ...init,
    method,
    headers,
    credentials: "include",
  });
  if (!res.ok) throw await toApiError(res);
  return (res.status === 204 ? undefined : await res.json()) as T;
}

/** 인증이 필요한 호출의 catch에서 사용. 공개 API의 401에는 자동 적용하지 않는다. */
export function redirectIfAuthRequired(error: unknown): boolean {
  if (!(error instanceof ApiError) || typeof window === "undefined")
    return false;
  const target =
    error.code === "SIGNUP_REQUIRED"
      ? "/signup"
      : error.status === 401
        ? "/login"
        : null;
  if (!target) return false;
  window.location.assign(target);
  return true;
}

const errorMessages: Record<string, string> = {
  OAUTH2_FAILED: "소셜 로그인에 실패했습니다. 다시 시도해 주세요.",
  SOCIAL_ACCOUNT_IN_USE: "다른 회원이 사용 중인 소셜 계정입니다.",
  PROVIDER_ALREADY_LINKED: "이미 연결된 소셜 계정입니다.",
  NICKNAME_TAKEN: "이미 사용 중인 닉네임입니다.",
  NICKNAME_FORBIDDEN: "사용할 수 없는 단어가 포함된 닉네임입니다.",
  NICKNAME_CHANGE_TOO_SOON: "닉네임은 30일에 한 번 변경할 수 있습니다.",
  LAST_SOCIAL_ACCOUNT:
    "마지막 로그인 계정은 해제할 수 없습니다. 다른 계정을 먼저 연결해 주세요.",
  UNAUTHORIZED: "로그인이 필요합니다.",
  SIGNUP_REQUIRED: "닉네임을 설정하여 가입을 완료해 주세요.",
  ACCOUNT_SUSPENDED: "정지된 계정으로는 글을 작성할 수 없습니다.",
  CSRF_INVALID: "보안 확인에 실패했습니다. 새로고침 후 다시 시도해 주세요.",
};
export function apiErrorMessage(error: unknown): string {
  if (typeof error === "string")
    return (
      errorMessages[error] ?? "요청을 처리하지 못했습니다. 다시 시도해 주세요."
    );
  if (error instanceof ApiError) {
    if (error.code === "VALIDATION_FAILED")
      return (
        error.fieldErrors
          .map(
            (e) =>
              `${e.field === "nickname" ? "닉네임" : e.field}: ${e.message}`,
          )
          .join(" ") || "입력값을 확인해 주세요."
      );
    return errorMessages[error.code] ?? error.message;
  }
  return "서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.";
}
