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
  button,
} from "@/components/community/shared";
import { PostItems } from "@/components/community/post-list";
import SearchForm from "./search-form";
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
    <Frame title="커뮤니티 검색">
      <SearchForm />
      {!users && (
        <div className="grid grid-cols-2 gap-3">
          <label>
            게시판
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
          <label>
            학교 태그
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
        </div>
      )}
      <label className="block text-sm">
        표시 개수
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
      {valid && !result.done && <p role="status">검색 중입니다…</p>}
      {valid && data && (
        <>
          <p role="status">검색 결과 {data.totalElements}건</p>
          {users ? (
            <ul className="divide-y">
              {authors.data?.content.map((u) => (
                <li key={u.id} className="py-4">
                  <AuthorLink author={u} />
                </li>
              ))}
            </ul>
          ) : (
            <PostItems posts={posts.data?.content ?? []} />
          )}
          {!data.content.length && (
            <p className="py-10 text-center text-slate-500">
              검색 결과가 없습니다.
            </p>
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
