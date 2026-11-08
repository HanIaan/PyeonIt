"use client";
import Link from "next/link";
import { useSearchParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import {
  listBoards,
  listSchools,
  listPosts,
  type S,
} from "@/lib/community-api";
import {
  Frame,
  Message,
  Tags,
  Pager,
  AuthorLink,
  dateLabel,
  input,
  primary,
  handleError,
  useCommunityAuth,
} from "./shared";
import SearchForm from "@/components/search/search-form";

export function PostItems({ posts }: { posts: S["PostSummary"][] }) {
  return (
    <ul className="divide-y divide-slate-200">
      {posts.map((p) => (
        <li key={p.id} className="space-y-3 py-5">
          <div className="text-xs text-indigo-600">{p.board.name}</div>
          <Link
            className="block text-lg font-semibold hover:text-indigo-600"
            href={`/community/${p.id}`}
          >
            {p.title}
            {p.hasImages && (
              <span className="ml-2 text-xs text-slate-500">이미지</span>
            )}
          </Link>
          <Tags schools={p.universities} />
          <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-500">
            <AuthorLink author={p.author} />
            <time dateTime={p.createdAt}>{dateLabel(p.createdAt)}</time>
            <span>추천 {p.likeCount}</span>
            <span>댓글 {p.commentCount}</span>
          </div>
        </li>
      ))}
    </ul>
  );
}

export default function PostList() {
  const query = useSearchParams();
  const router = useRouter();
  const auth = useCommunityAuth();
  const number = (
    key: string,
    fallback: number,
    max = Number.MAX_SAFE_INTEGER,
  ) => {
    const n = Number(query.get(key) ?? fallback);
    return Number.isInteger(n) && n >= 0 && n <= max ? n : fallback;
  };
  const boardId = number("boardId", 0),
    universityId = number("universityId", 0),
    page = number("page", 0),
    size = Math.max(1, number("size", 20, 100));
  const [boards, setBoards] = useState<S["Board"][]>([]),
    [schools, setSchools] = useState<S["University"][]>([]),
    [data, setData] = useState<S["PostPage"] | null>(null),
    [error, setError] = useState("");
  useEffect(() => {
    let live = true;
    Promise.all([
      listBoards(),
      listSchools(),
      listPosts({
        boardId: boardId || undefined,
        universityId: universityId || undefined,
        page,
        size,
      }),
    ]).then(
      ([b, u, p]) => {
        if (live) {
          setBoards(b);
          setSchools(u);
          setData(p);
          setError("");
        }
      },
      (e) => {
        if (live) handleError(e, setError);
      },
    );
    return () => {
      live = false;
    };
  }, [boardId, universityId, page, size]);
  function filter(key: string, value: number) {
    const next = new URLSearchParams(query);
    if (value) next.set(key, String(value));
    else next.delete(key);
    if (key !== "page") next.delete("page");
    router.push(`/community?${next}`);
  }
  return (
    <Frame title="함께 준비하는 편입">
      <SearchForm />
      <p className="text-sm text-slate-500">
        궁금한 점부터 작은 성취까지, 함께 나눠요.
      </p>
      <div className="flex justify-end">
        <button
          className={primary}
          onClick={() => {
            try {
              if (auth.check()) router.push("/community/write");
            } catch (e) {
              handleError(e, setError);
            }
          }}
        >
          글 쓰기
        </button>
      </div>
      <nav
        aria-label="게시판"
        className="flex gap-2 overflow-x-auto border-b pb-3"
      >
        {[{ id: 0, name: "전체" }, ...boards].map((b) => (
          <button
            key={b.id}
            aria-current={boardId === b.id ? "page" : undefined}
            onClick={() => filter("boardId", b.id)}
            className={`min-h-11 shrink-0 rounded-xl px-5 text-sm ${boardId === b.id ? "bg-indigo-600 text-white" : "bg-slate-100 text-slate-700"}`}
          >
            {b.name}
          </button>
        ))}
      </nav>
      <div className="flex flex-wrap gap-3">
        <label className="flex-1 text-sm">
          학교 태그
          <select
            className={`${input} mt-1`}
            value={universityId}
            onChange={(e) => filter("universityId", Number(e.target.value))}
          >
            <option value={0}>모든 학교</option>
            {schools.map((u) => (
              <option key={u.id} value={u.id}>
                {u.name} {u.campus}
              </option>
            ))}
          </select>
        </label>
        <label className="text-sm">
          표시 개수
          <select
            className={`${input} mt-1`}
            value={size}
            onChange={(e) => filter("size", Number(e.target.value))}
          >
            {[...new Set([10, 20, 50, size])]
              .sort((a, b) => a - b)
              .map((n) => (
                <option key={n}>{n}</option>
              ))}
          </select>
        </label>
      </div>
      <Message>{error}</Message>
      {!data && !error && <p role="status">글을 불러오는 중입니다…</p>}
      {data && (
        <>
          <p className="text-xs text-slate-500">
            총 {data.totalElements}개의 글
          </p>
          <PostItems posts={data.content} />
          {!data.content.length && (
            <p className="py-12 text-center text-slate-500">
              조건에 맞는 글이 없습니다.
            </p>
          )}
          <Pager
            page={page}
            total={data.totalPages}
            onChange={(n) => filter("page", n)}
          />
        </>
      )}
    </Frame>
  );
}
