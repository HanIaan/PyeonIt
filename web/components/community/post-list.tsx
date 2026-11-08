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
  fieldLabel,
  primary,
  handleError,
  useCommunityAuth,
} from "./shared";
import SearchForm from "@/components/search/search-form";
import { Segmented } from "@/components/ui/segmented";
import { EmptyState } from "@/components/ui/empty-state";

export function PostItems({ posts }: { posts: S["PostSummary"][] }) {
  return (
    <ul className="divide-y divide-line border-y border-line">
      {posts.map((p) => (
        <li key={p.id} className="space-y-2 py-4">
          <div className="text-caption font-medium text-ink-3">
            {p.board.name}
          </div>
          <Link
            className="block break-words text-title font-semibold hover:underline"
            href={`/community/${p.id}`}
          >
            {p.title}
            {p.hasImages && (
              <span className="ml-2 align-middle text-caption font-medium text-ink-3">
                이미지
              </span>
            )}
          </Link>
          <Tags schools={p.universities} />
          <div className="flex flex-wrap gap-x-3 gap-y-1 text-caption text-ink-3">
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
    <Frame
      title="커뮤니티"
      back={false}
      action={
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
      }
    >
      <SearchForm />
      <Segmented
        label="게시판"
        options={[{ id: 0, name: "전체" }, ...boards].map((b) => ({
          value: String(b.id),
          label: b.name,
        }))}
        value={String(boardId)}
        onChange={(v) => filter("boardId", Number(v))}
      />
      <div className="flex flex-wrap gap-3">
        <label className={`${fieldLabel} min-w-48 flex-1`}>
          <span>학교 태그</span>
          <select
            className={input}
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
        <label className={`${fieldLabel} w-28`}>
          <span>표시 개수</span>
          <select
            className={input}
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
      {!data && !error && (
        <p role="status" className="text-label text-ink-3">
          불러오는 중…
        </p>
      )}
      {data && (
        <>
          <p className="text-caption text-ink-3">총 {data.totalElements}개</p>
          {data.content.length ? (
            <PostItems posts={data.content} />
          ) : (
            <EmptyState>글이 없습니다</EmptyState>
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
