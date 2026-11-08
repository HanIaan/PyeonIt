import { api, ApiError, apiErrorMessage, type Schemas as S } from "./api";

export function pageNumber(
  value: string | null,
  fallback = 0,
  max = Number.MAX_SAFE_INTEGER,
) {
  const n = value === null ? fallback : Number(value);
  return Number.isSafeInteger(n) && n >= 0 && n <= max ? n : fallback;
}
const mock = process.env.NEXT_PUBLIC_API_MOCK === "true";
export type SearchOptions = {
  q: string;
  page: number;
  size: number;
  boardId?: number;
  universityId?: number;
};
export function queryString(values: object) {
  return new URLSearchParams(
    Object.entries(values)
      .filter(([, v]) => v !== undefined && v !== "")
      .map(([k, v]) => [k, String(v)]),
  ).toString();
}
export function searchError(error: unknown) {
  if (error instanceof ApiError) {
    if (error.status === 429 || error.code === "RATE_LIMITED")
      return "검색 요청이 많습니다. 서버가 지정한 Retry-After(대기 시간) 후 다시 시도해 주세요.";
    if (error.code === "VALIDATION_FAILED")
      return "검색어와 필터를 확인해 주세요. 검색어는 2~50자, 학교명은 1~50자로 입력해 주세요.";
  }
  return apiErrorMessage(error);
}
export function mockPage<T>(items: T[], page: number, size: number) {
  return {
    content: items.slice(page * size, (page + 1) * size),
    page,
    size,
    totalElements: items.length,
    totalPages: Math.ceil(items.length / size),
  };
}
export const mockSearchPosts: S["PostSummary"][] = Array.from(
  { length: 24 },
  (_, i) => ({
    id: i + 1,
    title: [
      "편입 영어 공부 순서, 어떻게 잡으셨나요?",
      "오늘도 도서관 출석했어요",
      "학교별 모집 일정 함께 확인해요",
    ][i % 3],
    board: [
      { id: 1, code: "QUESTION", name: "질문" },
      { id: 2, code: "FREE", name: "자유" },
      { id: 3, code: "INFO", name: "정보" },
    ][i % 3],
    author: {
      id: (i % 2) + 1,
      nickname: i === 2 ? null : i % 2 ? "차곡차곡" : "편입도전",
    },
    universities: [
      {
        id: (i % 2) + 1,
        name: i % 2 ? "성균관대학교" : "서강대학교",
        campus: "",
        region: "SEOUL",
      },
    ],
    viewCount: 30 + i,
    likeCount: i + 2,
    commentCount: i === 0 ? 2 : 0,
    hasImages: i === 0,
    createdAt: "2026-11-08T01:00:00Z",
  }),
);
mockSearchPosts.reverse();

export async function searchPosts(
  options: SearchOptions,
): Promise<S["PostPage"]> {
  if (!mock) return api(`/api/posts/search?${queryString(options)}`);
  const q = options.q.toLocaleLowerCase();
  return mockPage(
    mockSearchPosts.filter(
      (p) =>
        `${p.title} 서강 편입 준비 경험과 공부 방법`
          .toLocaleLowerCase()
          .includes(q) &&
        (!options.boardId || p.board.id === options.boardId) &&
        (!options.universityId ||
          p.universities.some((u) => u.id === options.universityId)),
    ),
    options.page,
    options.size,
  );
}
export async function searchUsers(
  options: SearchOptions,
): Promise<S["UserSummaryPage"]> {
  if (!mock)
    return api(
      `/api/users/search?${queryString({ q: options.q, page: options.page, size: options.size })}`,
    );
  return mockPage(
    [
      { id: 1, nickname: "편입도전" },
      { id: 2, nickname: "차곡차곡" },
    ].filter((u) =>
      u.nickname.toLocaleLowerCase().includes(options.q.toLocaleLowerCase()),
    ),
    options.page,
    options.size,
  );
}
export async function searchUniversities(
  q: string,
  region?: S["Region"],
): Promise<S["University"][]> {
  if (!mock) {
    try {
      return await api(`/api/universities?${queryString({ q, region })}`);
    } catch (error) {
      throw new Error(searchError(error));
    }
  }
  const { listMockUniversities } = await import("./universities-mock");
  return (await listMockUniversities(region)).filter((u) =>
    `${u.name} ${u.name.includes("성균관") ? "성대" : "서강"}`.includes(q),
  );
}
