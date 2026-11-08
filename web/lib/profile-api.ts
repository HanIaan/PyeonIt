import { api, ApiError, type Schemas as S } from "./api";
import { mockPage, mockSearchPosts, queryString } from "./search-api";
const mock = process.env.NEXT_PUBLIC_API_MOCK === "true";
function mockProfile(id: number): S["UserProfile"] {
  if (![1, 2].includes(id))
    throw new ApiError(404, "NOT_FOUND", "탈퇴한 사용자입니다");
  return {
    id,
    nickname: id === 1 ? "편입도전" : "차곡차곡",
    createdAt: "2026-01-01T00:00:00Z",
    postCount: mockSearchPosts.filter(
      (p) => p.author.id === id && p.author.nickname !== null,
    ).length,
    commentCount: id === 1 ? 1 : 0,
  };
}
export async function getProfile(id: number): Promise<S["UserProfile"]> {
  return mock ? mockProfile(id) : api(`/api/users/${id}`);
}
export async function getProfilePosts(
  id: number,
  page: number,
  size: number,
): Promise<S["PostPage"]> {
  if (!mock)
    return api(`/api/users/${id}/posts?${queryString({ page, size })}`);
  mockProfile(id);
  return mockPage(
    mockSearchPosts.filter(
      (p) => p.author.id === id && p.author.nickname !== null,
    ),
    page,
    size,
  );
}
export async function getProfileComments(
  id: number,
  page: number,
  size: number,
): Promise<S["UserCommentPage"]> {
  if (!mock)
    return api(`/api/users/${id}/comments?${queryString({ page, size })}`);
  mockProfile(id);
  return mockPage(
    id === 1
      ? [
          {
            commentId: 1,
            bodyExcerpt: "기초 어휘부터 꾸준히 쌓는 게 도움이 됐어요.",
            postId: 1,
            postTitle: mockSearchPosts.find((p) => p.id === 1)!.title,
            createdAt: "2026-11-08T01:00:00Z",
          },
        ]
      : [],
    page,
    size,
  );
}
