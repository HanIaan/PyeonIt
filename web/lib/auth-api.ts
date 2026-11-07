import { api, ApiError, type Schemas } from "./api";
import { API_BASE_URL } from "./config";

export type Me = Schemas["Me"];
export type Provider = Schemas["SocialProvider"];
export const AUTH_MOCK = process.env.NEXT_PUBLIC_API_MOCK === "true";
export const ENABLE_DEV_LOGIN =
  process.env.NEXT_PUBLIC_ENABLE_DEV_LOGIN === "true";
export const nicknamePattern = /^[가-힣a-zA-Z0-9]{2,12}$/;
export const nicknameHelp =
  "닉네임은 한글·영문·숫자로 2~12자 입력해 주세요. 중복·금지어 여부는 서버에서 최종 확인합니다.";
let mockMe: Me | null = null;
let pendingProvider: Provider | null = null;
const usedNicknames = new Set<string>(["사용중"]);

function member(): Me {
  if (mockMe) return mockMe;
  throw new ApiError(
    pendingProvider ? 403 : 401,
    pendingProvider ? "SIGNUP_REQUIRED" : "UNAUTHORIZED",
    "로그인이 필요합니다.",
  );
}
function validateNickname(nickname: string) {
  if (!nicknamePattern.test(nickname))
    throw new ApiError(
      400,
      "VALIDATION_FAILED",
      "닉네임 형식을 확인해 주세요.",
      [
        {
          field: "nickname",
          message: "한글·영문·숫자 2~12자만 사용할 수 있습니다.",
        },
      ],
    );
  if (/운영자|관리자|편잇|pyeonit|admin/i.test(nickname))
    throw new ApiError(
      422,
      "NICKNAME_FORBIDDEN",
      "사용할 수 없는 단어가 포함되어 있습니다.",
    );
  if (usedNicknames.has(nickname.toLowerCase()))
    throw new ApiError(409, "NICKNAME_TAKEN", "이미 사용 중인 닉네임입니다.");
}
function createMember(
  nickname: string,
  role: Me["role"] = "USER",
  suspended = false,
): Me {
  return {
    id: 1,
    nickname,
    role,
    status: suspended ? "SUSPENDED" : "ACTIVE",
    suspendedUntil: null,
    nicknameChangeableAt: null,
    socialAccounts: [
      {
        provider: pendingProvider ?? "KAKAO",
        linkedAt: new Date().toISOString(),
      },
    ],
    createdAt: new Date().toISOString(),
  };
}
export async function getMe(): Promise<Me> {
  return AUTH_MOCK
    ? structuredClone(member())
    : api<Me>("/api/me", { cache: "no-store" });
}
export async function signup(nickname: string): Promise<Me> {
  if (!AUTH_MOCK)
    return api<Me>("/api/auth/signup", { method: "POST", json: { nickname } });
  if (mockMe)
    throw new ApiError(409, "ALREADY_REGISTERED", "이미 가입한 회원입니다.");
  if (!pendingProvider)
    throw new ApiError(
      401,
      "UNAUTHORIZED",
      "소셜 로그인을 먼저 진행해 주세요.",
    );
  validateNickname(nickname);
  mockMe = createMember(nickname);
  usedNicknames.add(nickname.toLowerCase());
  pendingProvider = null;
  return structuredClone(mockMe);
}
export async function changeNickname(nickname: string): Promise<Me> {
  if (!AUTH_MOCK)
    return api<Me>("/api/me/nickname", { method: "PATCH", json: { nickname } });
  const me = member();
  if (
    me.nicknameChangeableAt &&
    Date.parse(me.nicknameChangeableAt) > Date.now()
  )
    throw new ApiError(
      429,
      "NICKNAME_CHANGE_TOO_SOON",
      "닉네임은 30일에 한 번 변경할 수 있습니다.",
      [],
      me.nicknameChangeableAt,
    );
  validateNickname(nickname);
  usedNicknames.delete(me.nickname.toLowerCase());
  usedNicknames.add(nickname.toLowerCase());
  mockMe = {
    ...me,
    nickname,
    nicknameChangeableAt: new Date(Date.now() + 30 * 86400000).toISOString(),
  };
  return structuredClone(mockMe);
}
export async function logout(): Promise<void> {
  if (!AUTH_MOCK) return api<void>("/api/auth/logout", { method: "POST" });
  mockMe = null;
  pendingProvider = null;
}
export async function withdraw(): Promise<void> {
  if (!AUTH_MOCK) return api<void>("/api/me", { method: "DELETE" });
  usedNicknames.delete(member().nickname.toLowerCase());
  mockMe = null;
  pendingProvider = null;
}
export async function devLogin(
  request: Schemas["DevLoginRequest"],
): Promise<Me> {
  if (!ENABLE_DEV_LOGIN)
    throw new ApiError(404, "NOT_FOUND", "개발 로그인이 꺼져 있습니다.");
  if (!AUTH_MOCK)
    return api<Me>("/api/dev/login", { method: "POST", json: request });
  mockMe = createMember(request.nickname, request.role, request.suspended);
  pendingProvider = null;
  return structuredClone(mockMe);
}
export function startLogin(provider: Provider): void {
  // OAuth는 Next 라우트가 아닌 외부 API 서버에서 시작한다.
  // eslint-disable-next-line @next/next/no-location-assign-relative-destination
  window.location.assign(
    `${API_BASE_URL}/oauth2/authorization/${provider.toLowerCase()}`,
  );
}
// 목 모드에서만 제공하는 가입 대기 세션. 실제 OAuth 버튼은 항상 API로 이동한다.
export function startMockSignup(provider: Provider): void {
  if (!AUTH_MOCK) return;
  mockMe = null;
  pendingProvider = provider;
}
export async function linkSocial(provider: Provider): Promise<void> {
  if (AUTH_MOCK) {
    const me = member();
    if (me.socialAccounts.some((a) => a.provider === provider))
      throw new ApiError(
        409,
        "PROVIDER_ALREADY_LINKED",
        "이미 연결된 계정입니다.",
      );
    me.socialAccounts.push({ provider, linkedAt: new Date().toISOString() });
    return;
  }
  const result = await api<Schemas["SocialLinkStart"]>(
    `/api/me/social-accounts/${provider.toLowerCase()}`,
    { method: "POST" },
  );
  window.location.assign(new URL(result.authorizationUrl, API_BASE_URL).href);
}
export async function unlinkSocial(provider: Provider): Promise<void> {
  if (!AUTH_MOCK)
    return api<void>(`/api/me/social-accounts/${provider.toLowerCase()}`, {
      method: "DELETE",
    });
  const me = member();
  if (me.socialAccounts.length <= 1)
    throw new ApiError(
      422,
      "LAST_SOCIAL_ACCOUNT",
      "마지막 로그인 계정은 해제할 수 없습니다.",
    );
  me.socialAccounts = me.socialAccounts.filter((a) => a.provider !== provider);
}
