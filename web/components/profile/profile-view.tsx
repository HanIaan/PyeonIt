"use client";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { ApiError } from "@/lib/api";
import {
  getProfile,
  getProfilePosts,
  getProfileComments,
} from "@/lib/profile-api";
import { pageNumber, searchError } from "@/lib/search-api";
import { useUniversityResource } from "@/components/universities/shared";
import {
  Frame,
  Message,
  Pager,
  dateLabel,
  input,
  fieldLabel,
} from "@/components/community/shared";
import { EmptyState } from "@/components/ui/empty-state";
import { PostItems } from "@/components/community/post-list";
export default function ProfileView({ id }: { id: number }) {
  const params = useSearchParams(),
    router = useRouter();
  const tab = params.get("tab") === "comments" ? "comments" : "posts";
  const page = pageNumber(params.get("page")),
    size = Math.max(1, pageNumber(params.get("size"), 20, 100));
  const profile = useUniversityResource(`profile:${id}`, () => getProfile(id));
  const posts = useUniversityResource(
    `profile-posts:${id}:${tab}:${page}:${size}`,
    async () => {
      if (tab !== "posts") return null;
      await getProfile(id);
      return getProfilePosts(id, page, size);
    },
  );
  const comments = useUniversityResource(
    `profile-comments:${id}:${tab}:${page}:${size}`,
    async () => {
      if (tab !== "comments") return null;
      await getProfile(id);
      return getProfileComments(id, page, size);
    },
  );
  const result = tab === "posts" ? posts : comments;
  const error = profile.error ?? result.error;
  if (error instanceof ApiError && error.status === 404)
    return (
      <section className="mx-auto max-w-3xl">
        <h1 className="text-heading font-bold">탈퇴한 사용자입니다</h1>
      </section>
    );
  function change(key: string, value: string) {
    const next = new URLSearchParams(params);
    next.set(key, value);
    if (key !== "page") next.delete("page");
    router.push(`/users/${id}?${next}`);
  }
  return (
    <Frame title={profile.data?.nickname ?? "사용자 프로필"}>
      <Message>{error ? searchError(error) : ""}</Message>
      {!profile.done && (
        <p role="status" className="text-label text-ink-3">
          불러오는 중…
        </p>
      )}
      {profile.data && (
        <>
          <dl className="-mt-3 flex flex-wrap gap-x-4 gap-y-1 text-label text-ink-2">
            {[
              ["가입", dateLabel(profile.data.createdAt)],
              ["글", `${profile.data.postCount}개`],
              ["댓글", `${profile.data.commentCount}개`],
            ].map(([k, v]) => (
              <div key={k} className="flex gap-1.5">
                <dt className="text-ink-3">{k}</dt>
                <dd className="font-medium">{v}</dd>
              </div>
            ))}
          </dl>
          <div className="flex flex-wrap items-end justify-between gap-3">
            <nav
              aria-label="프로필 활동"
              className="inline-flex gap-1 rounded-pill border border-line bg-surface p-1"
            >
              {[
                ["posts", "글"],
                ["comments", "댓글"],
              ].map(([value, label]) => (
                <Link
                  key={value}
                  href={`/users/${id}?${new URLSearchParams({ tab: value, size: String(size) })}`}
                  aria-current={tab === value ? "page" : undefined}
                  className="inline-flex min-h-11 min-w-16 items-center justify-center rounded-pill px-4 text-label font-semibold text-ink-2 hover:text-ink aria-[current=page]:bg-lime aria-[current=page]:text-ink"
                >
                  {label}
                </Link>
              ))}
            </nav>
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
          {!result.done && (
            <p role="status" className="text-label text-ink-3">
              불러오는 중…
            </p>
          )}
          {tab === "posts" && !!posts.data?.content.length && (
            <PostItems posts={posts.data.content} />
          )}
          {tab === "comments" && !!comments.data?.content.length && (
            <ul className="divide-y divide-line border-y border-line">
              {comments.data.content.map((c) => (
                <li key={c.commentId} className="space-y-2 py-4">
                  <Link
                    className="group block space-y-1"
                    href={`/community/${c.postId}`}
                  >
                    <p className="line-clamp-3 whitespace-pre-wrap break-words text-body group-hover:underline">
                      {c.bodyExcerpt}
                      {c.bodyExcerpt.length >= 200 ? "…" : ""}
                    </p>
                    <p className="text-label text-ink-2">원문: {c.postTitle}</p>
                  </Link>
                  <time
                    className="text-caption text-ink-3"
                    dateTime={c.createdAt}
                  >
                    {dateLabel(c.createdAt)}
                  </time>
                </li>
              ))}
            </ul>
          )}
          {result.data && (
            <>
              {!result.data.content.length && (
                <EmptyState>
                  공개된 {tab === "posts" ? "글" : "댓글"}이 없습니다.
                </EmptyState>
              )}
              <Pager
                page={page}
                total={result.data.totalPages}
                onChange={(n) => change("page", String(n))}
              />
            </>
          )}
        </>
      )}
    </Frame>
  );
}
