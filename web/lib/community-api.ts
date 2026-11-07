import { api, ApiError, type Schemas } from "@/lib/api";
import { getMe } from "@/lib/auth-api";
export type S = Schemas;
// F1 목 인증과 마찬가지로 새로고침하면 초기화되는 메모리 목이다.
const mock = process.env.NEXT_PUBLIC_API_MOCK === "true";
const boards: S["Board"][] = [
  { id: 1, code: "QUESTION", name: "질문" },
  { id: 2, code: "FREE", name: "자유" },
  { id: 3, code: "INFO", name: "정보" },
];
const schools: S["University"][] = [
  "서울대학교",
  "연세대학교",
  "고려대학교",
].map((name, i) => ({
  id: i + 1,
  name,
  campus: "",
  region: "SEOUL",
  admissionUrl: null,
  favorite: false,
}));
const date = "2026-11-08T01:00:00Z";
const illustration = `data:image/svg+xml,${encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="640" height="240"><rect width="640" height="240" fill="#e0f2fe"/><text x="40" y="130" font-size="32" fill="#075985">PyeonIt · Study together</text></svg>')}`;
let posts: S["PostDetail"][] = Array.from({ length: 24 }, (_, i) => ({
  id: i + 1,
  board: boards[i % 3],
  title: [
    "편입 영어 공부 순서, 어떻게 잡으셨나요?",
    "오늘도 도서관 출석했어요",
    "학교별 모집 일정 함께 확인해요",
  ][i % 3],
  body: "혼자 준비하다 보니 궁금한 점이 많네요.\n각자의 경험과 공부 방법을 함께 나눠 주세요!",
  author: {
    id: (i % 2) + 1,
    nickname: i === 2 ? null : i % 2 ? "차곡차곡" : "편입도전",
  },
  universities: [schools[i % 3]],
  viewCount: 30 + i,
  likeCount: i + 2,
  commentCount: i === 0 ? 2 : 0,
  hasImages: i === 0,
  images:
    i === 0
      ? [
          {
            id: 1,
            url: illustration,
            contentType: "image/png",
            sizeBytes: 1024,
          },
        ]
      : [],
  createdAt: date,
  updatedAt: date,
  likedByMe: false,
  mine: false,
}));
let comments: S["Comment"][] = [
  {
    id: 1,
    postId: 1,
    parentId: null,
    author: { id: 1, nickname: "편입도전" },
    body: "기초 어휘부터 꾸준히 쌓는 게 도움이 됐어요.",
    status: "PUBLISHED",
    mine: false,
    createdAt: date,
    updatedAt: date,
    replies: [
      {
        id: 2,
        postId: 1,
        parentId: 1,
        author: { id: 2, nickname: null },
        body: "같이 힘내요!",
        status: "PUBLISHED",
        mine: false,
        createdAt: date,
        updatedAt: date,
        replies: [],
      },
    ],
  },
];
const uploaded: S["PostImage"][] = [];
const reports: number[] = [];
let nextMockId = 1000;
function mockId() {
  return ++nextMockId;
}
async function actor() {
  const me = await getMe();
  if (me.status === "SUSPENDED")
    throw new ApiError(
      403,
      "ACCOUNT_SUSPENDED",
      "정지 중이라 작성할 수 없습니다.",
    );
  return me;
}
async function viewer() {
  try {
    return (await getMe()).id;
  } catch {
    return null;
  }
}
function find(id: number) {
  const p = posts.find((p) => p.id === id);
  if (!p) throw new ApiError(404, "NOT_FOUND", "글을 찾을 수 없습니다.");
  return p;
}
function page<T>(items: T[], p: number, size: number) {
  return {
    content: structuredClone(items.slice(p * size, (p + 1) * size)),
    page: p,
    size,
    totalElements: items.length,
    totalPages: Math.ceil(items.length / size),
  };
}
export async function listBoards(): Promise<S["Board"][]> {
  return mock ? structuredClone(boards) : api("/api/boards");
}
export async function listSchools(): Promise<S["University"][]> {
  return mock ? structuredClone(schools) : api("/api/universities");
}
export async function listPosts(q: {
  boardId?: number;
  universityId?: number;
  page: number;
  size: number;
}): Promise<S["PostPage"]> {
  if (!mock)
    return api(
      `/api/posts?${new URLSearchParams(
        Object.entries(q)
          .filter(([, v]) => v !== undefined)
          .map(([k, v]) => [k, String(v)]),
      )}`,
    );
  return page(
    posts.filter(
      (p) =>
        (!q.boardId || p.board.id === q.boardId) &&
        (!q.universityId ||
          p.universities.some((u) => u.id === q.universityId)),
    ),
    q.page,
    q.size,
  );
}
export async function getPost(id: number): Promise<S["PostDetail"]> {
  if (!mock) return api(`/api/posts/${id}`);
  const p = structuredClone(find(id));
  p.mine = p.author.id === (await viewer());
  return p;
}
export async function savePost(
  data: S["PostWriteRequest"],
  id?: number,
): Promise<S["PostDetail"]> {
  if (!mock)
    return api(id ? `/api/posts/${id}` : "/api/posts", {
      method: id ? "PATCH" : "POST",
      json: data,
    });
  const me = await actor();
  const old = id ? find(id) : null;
  if (old && old.author.id !== me.id)
    throw new ApiError(403, "FORBIDDEN", "본인 글만 수정할 수 있습니다.");
  const images = (data.imageIds ?? [])
    .map(
      (id) =>
        uploaded.find((i) => i.id === id) ??
        old?.images.find((i) => i.id === id),
    )
    .filter((i): i is S["PostImage"] => !!i);
  const p: S["PostDetail"] = {
    id: id ?? mockId(),
    board: boards.find((b) => b.id === data.boardId)!,
    title: data.title,
    body: data.body,
    author: { id: me.id, nickname: me.nickname },
    universities: schools.filter((u) => data.universityIds?.includes(u.id)),
    viewCount: old?.viewCount ?? 0,
    likeCount: old?.likeCount ?? 0,
    commentCount: old?.commentCount ?? 0,
    hasImages: !!images.length,
    images,
    likedByMe: old?.likedByMe ?? false,
    mine: true,
    createdAt: old?.createdAt ?? new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  };
  posts = [p, ...posts.filter((v) => v.id !== p.id)];
  return structuredClone(p);
}
export async function deletePost(id: number): Promise<void> {
  if (!mock) return api(`/api/posts/${id}`, { method: "DELETE" });
  const me = await actor();
  if (find(id).author.id !== me.id)
    throw new ApiError(403, "FORBIDDEN", "본인 글만 삭제할 수 있습니다.");
  posts = posts.filter((p) => p.id !== id);
}
export async function likePost(
  id: number,
  liked: boolean,
): Promise<S["LikeStatus"]> {
  if (!mock)
    return api(`/api/posts/${id}/like`, { method: liked ? "PUT" : "DELETE" });
  await actor();
  const p = find(id);
  if (p.likedByMe !== liked) p.likeCount += liked ? 1 : -1;
  p.likedByMe = liked;
  return { liked, likeCount: p.likeCount };
}
export async function reportPost(
  id: number,
  reason: S["ReportReason"],
  detail: string,
): Promise<S["ReportReceipt"]> {
  if (!mock)
    return api("/api/reports", {
      method: "POST",
      json: { targetType: "POST", targetId: id, reason, detail },
    });
  await actor();
  if (reports.includes(id))
    throw new ApiError(409, "ALREADY_REPORTED", "이미 신고한 글입니다.");
  reports.push(id);
  return {
    id: mockId(),
    status: "PENDING",
    createdAt: new Date().toISOString(),
  };
}
export async function listComments(
  id: number,
  p = 0,
): Promise<S["CommentPage"]> {
  if (!mock) return api(`/api/posts/${id}/comments?page=${p}&size=20`);
  const uid = await viewer();
  return page(
    comments
      .filter((c) => c.postId === id)
      .map((c) => ({
        ...c,
        mine: c.author?.id === uid,
        replies: c.replies.map((r) => ({ ...r, mine: r.author?.id === uid })),
      })),
    p,
    20,
  );
}
export async function writeComment(
  postId: number,
  body: string,
  parentId: number | null,
): Promise<S["Comment"]> {
  if (!mock)
    return api(`/api/posts/${postId}/comments`, {
      method: "POST",
      json: { body, parentId },
    });
  const me = await actor();
  const c: S["Comment"] = {
    id: mockId(),
    postId,
    parentId,
    body,
    author: { id: me.id, nickname: me.nickname },
    status: "PUBLISHED",
    mine: true,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    replies: [],
  };
  if (parentId) {
    const parent = comments.find(
      (c) => c.id === parentId && c.postId === postId,
    );
    if (!parent)
      throw new ApiError(
        422,
        "REPLY_DEPTH_EXCEEDED",
        "답글은 한 단계까지만 작성할 수 있습니다.",
      );
    parent.replies.push(c);
  } else comments.push(c);
  find(postId).commentCount++;
  return structuredClone(c);
}
export async function changeComment(id: number, body?: string): Promise<void> {
  if (!mock)
    return api(`/api/comments/${id}`, {
      method: body === undefined ? "DELETE" : "PATCH",
      ...(body === undefined ? {} : { json: { body } }),
    });
  const me = await actor();
  const c = comments.flatMap((c) => [c, ...c.replies]).find((c) => c.id === id);
  if (!c || c.author?.id !== me.id)
    throw new ApiError(403, "FORBIDDEN", "본인 댓글만 변경할 수 있습니다.");
  if (body !== undefined) {
    c.body = body;
    c.updatedAt = new Date().toISOString();
  } else {
    c.status = "DELETED";
    c.body = null;
    c.author = null;
    c.mine = false;
    find(c.postId).commentCount--;
    comments = comments
      .filter((v) => v.status !== "DELETED" || v.replies.length)
      .map((v) => ({
        ...v,
        replies: v.replies.filter((r) => r.status !== "DELETED"),
      }));
  }
}
export function validateImage(file: File) {
  if (
    !["image/jpeg", "image/png", "image/webp", "image/gif"].includes(file.type)
  )
    throw new Error("JPG, PNG, WEBP, GIF 이미지만 올릴 수 있습니다.");
  if (file.size > 5 * 1024 * 1024)
    throw new Error("이미지는 한 장당 5MB 이하여야 합니다.");
}
export async function uploadImage(file: File): Promise<S["PostImage"]> {
  validateImage(file);
  if (!mock) {
    const body = new FormData();
    body.set("file", file);
    return api("/api/post-images", { method: "POST", body });
  }
  await actor();
  const url = await new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
  const result: S["PostImage"] = {
    id: mockId(),
    url,
    contentType: file.type as S["PostImage"]["contentType"],
    sizeBytes: file.size,
  };
  uploaded.push(result);
  return result;
}
