"use client";
import { useRouter, useSearchParams } from "next/navigation";
import { listBoards } from "@/lib/community-api";
import {
  searchPosts,
  searchUsers,
  searchUniversities,
  searchError,
  pageNumber,
} from "@/lib/search-api";
import { useUniversityResource } from "@/components/universities/shared";
import {
  AuthorLink,
  Frame,
  Message,
  Pager,
  input,
  fieldLabel,
  button,
} from "@/components/community/shared";
import { PostItems } from "@/components/community/post-list";
import SearchForm from "./search-form";
import { EmptyState } from "@/components/ui/empty-state";
export default function SearchView() {
  const params = useSearchParams(),
    router = useRouter();
  const q = (params.get("q") ?? "").trim(),
    users = params.get("scope") === "users";
  const page = pageNumber(params.get("page")),
    size = Math.max(1, pageNumber(params.get("size"), 20, 100));
  const boardId = pageNumber(params.get("boardId")),
    universityId = pageNumber(params.get("universityId"));
  const valid = q.length >= 2 && q.length <= 50;
  const key = JSON.stringify({ q, users, page, size, boardId, universityId });
  const posts = useUniversityResource(`posts:${key}`, () =>
    valid && !users
      ? searchPosts({
          q,
          page,
          size,
          boardId: boardId || undefined,
          universityId: universityId || undefined,
        })
      : Promise.resolve(null),
  );
  const authors = useUniversityResource(`users:${key}`, () =>
    valid && users ? searchUsers({ q, page, size }) : Promise.resolve(null),
  );
  const filters = useUniversityResource("search-filters", async () => ({
    boards: await listBoards(),
    schools: await searchUniversities(""),
  }));
  const result = users ? authors : posts;
  const data = result.data;
  function change(key: string, value: string) {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    if (key !== "page") next.delete("page");
    router.push(`/community/search?${next}`);
  }
  return (
    <Frame title="검색">
      <SearchForm />
      <div className="flex flex-wrap gap-3">
        {!users && (
          <>
            <label className={`${fieldLabel} min-w-40 flex-1`}>
              <span>게시판</span>
              <select
                className={input}
                value={boardId}
                onChange={(e) => change("boardId", e.target.value)}
              >
                <option value={0}>전체 게시판</option>
                {filters.data?.boards.map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.name}
                  </option>
                ))}
              </select>
            </label>
            <label className={`${fieldLabel} min-w-40 flex-1`}>
              <span>학교 태그</span>
              <select
                className={input}
                value={universityId}
                onChange={(e) => change("universityId", e.target.value)}
              >
                <option value={0}>모든 학교</option>
                {filters.data?.schools.map((u) => (
                  <option key={u.id} value={u.id}>
                    {u.name}
                  </option>
                ))}
              </select>
            </label>
          </>
        )}
        <label className={`${fieldLabel} w-28`}>
          <span>표시 개수</span>
          <select
            className={input}
            value={size}
            onChange={(e) => change("size", e.target.value)}
          >
            {[...new Set([10, 20, 50, size])].map((n) => (
              <option key={n}>{n}</option>
            ))}
          </select>
        </label>
      </div>
      {!valid && <Message>검색어는 2~50자로 입력해 주세요.</Message>}
      <Message>
        {result.error
          ? searchError(result.error)
          : filters.error
            ? searchError(filters.error)
            : ""}
      </Message>
      {result.error || filters.error ? (
        <button
          className={button}
          onClick={() => {
            result.retry();
            filters.retry();
          }}
        >
          다시 시도
        </button>
      ) : null}
      {valid && !result.done && (
        <p role="status" className="text-label text-ink-3">
          검색 중…
        </p>
      )}
      {valid && data && (
        <>
          <p role="status" className="text-caption text-ink-3">
            검색 결과 {data.totalElements}건
          </p>
          {!data.content.length ? (
            <EmptyState>검색 결과가 없습니다.</EmptyState>
          ) : users ? (
            <ul className="divide-y divide-line border-y border-line">
              {authors.data?.content.map((u) => (
                <li key={u.id} className="flex min-h-14 items-center">
                  <AuthorLink author={u} />
                </li>
              ))}
            </ul>
          ) : (
            <PostItems posts={posts.data?.content ?? []} />
          )}
          <Pager
            page={page}
            total={data.totalPages}
            onChange={(n) => change("page", String(n))}
          />
        </>
      )}
    </Frame>
  );
}
