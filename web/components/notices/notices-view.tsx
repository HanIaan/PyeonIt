"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import {
  listNotices,
  type NoticeFilters,
  type Schemas,
} from "@/lib/universities-api";
import { koreanDate, regions } from "@/lib/universities-display";
import { EmptyState } from "@/components/ui/empty-state";
import {
  buttonClass,
  cardClass,
  inputClass,
  LoginNotice,
  PageIntro,
  RegionSelect,
  ResourceMessage,
  SourceLinks,
  useUniversityResource,
  useUniversitySession,
} from "@/components/universities/shared";

function integer(
  value: string | null,
  fallback: number,
  min: number,
  max: number,
) {
  const n = Number(value);
  return value !== null &&
    /^\d+$/.test(value) &&
    Number.isSafeInteger(n) &&
    n >= min &&
    n <= max
    ? n
    : fallback;
}
export default function NoticesView() {
  const params = useSearchParams();
  const router = useRouter();
  const session = useUniversitySession();
  const region = params.get("region") ?? "";
  const year = integer(params.get("academicYear"), 0, 2000, 2100);
  const filters: NoticeFilters = {
    page: integer(params.get("page"), 0, 0, 2147483647),
    size: integer(params.get("size"), 20, 1, 100),
    academicYear: year || undefined,
    region: Object.hasOwn(regions, region)
      ? (region as Schemas["Region"])
      : undefined,
    favoritesOnly: params.get("favoritesOnly") === "true",
  };
  const blocked = filters.favoritesOnly && (!session.done || !session.data);
  const result = useUniversityResource(
    `notices:${JSON.stringify(filters)}:${blocked}:${session.identity}`,
    async () => (blocked ? null : listNotices(filters)),
  );
  const data = result.data;
  function change(key: string, value: string) {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    if (key !== "page") next.set("page", "0");
    router.push(`/notices?${next}`);
  }
  return (
    <>
      <PageIntro title="모집 공고" />
      <div className="mb-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <label className="grid gap-1.5 text-label font-medium">
          학년도
          <select
            className={inputClass}
            value={year || ""}
            onChange={(e) => change("academicYear", e.target.value)}
          >
            <option value="">전체 학년도</option>
            {Array.from({ length: 101 }, (_, i) => 2100 - i).map((y) => (
              <option key={y} value={y}>
                {y}학년도
              </option>
            ))}
          </select>
        </label>
        <RegionSelect
          value={filters.region ?? ""}
          onChange={(v) => change("region", v)}
        />
        <label className="grid gap-1.5 text-label font-medium">
          페이지당 공고
          <select
            className={inputClass}
            value={filters.size}
            onChange={(e) => change("size", e.target.value)}
          >
            {[...new Set([1, 10, 20, 50, 100, filters.size])]
              .sort((a, b) => a - b)
              .map((s) => (
                <option key={s} value={s}>
                  {s}개
                </option>
              ))}
          </select>
        </label>
        <label className="flex min-h-11 items-center gap-2 self-end text-label font-medium">
          <input
            type="checkbox"
            className="size-5 accent-ink"
            checked={!!filters.favoritesOnly}
            onChange={(e) =>
              change("favoritesOnly", e.target.checked ? "true" : "")
            }
          />
          관심학교만 보기
        </label>
      </div>
      {filters.favoritesOnly &&
        session.done &&
        !session.data &&
        !session.error && <LoginNotice />}
      {filters.favoritesOnly && <ResourceMessage {...session} />}
      {!blocked && <ResourceMessage {...result} />}
      {data && (
        <>
          <p className="mb-3 text-label text-ink-3">
            총 {data.totalElements}개
          </p>
          <ul className="grid gap-3">
            {data.content.map((notice) => (
              <li key={notice.documentId} className={cardClass}>
                <p className="text-caption text-ink-3">
                  {notice.university.name} {notice.university.campus} ·{" "}
                  {notice.academicYear}학년도
                </p>
                <h2 className="mt-1 text-title font-semibold">
                  <Link
                    href={`/universities/${notice.university.id}?academicYear=${notice.academicYear}`}
                    className="rounded-control underline-offset-4 hover:underline"
                  >
                    {notice.title}
                  </Link>
                </h2>
                <p className="mb-4 mt-2 text-label text-ink-2">
                  <span className="font-semibold text-ink">원서접수</span>{" "}
                  {notice.applicationStartsAt
                    ? koreanDate(notice.applicationStartsAt)
                    : "일정 미등록"}
                  {notice.applicationEndsAt
                    ? ` ~ ${koreanDate(notice.applicationEndsAt)}`
                    : ""}
                  {notice.applicationStartsAt && " (한국 시간)"}
                </p>
                <SourceLinks {...notice} />
              </li>
            ))}
          </ul>
          {!data.content.length && (
            <EmptyState>조건에 맞는 공고가 없습니다.</EmptyState>
          )}
          <nav
            aria-label="공고 페이지"
            className="mt-6 flex flex-wrap items-center justify-center gap-3"
          >
            <button
              className={buttonClass}
              disabled={filters.page === 0}
              onClick={() => change("page", String(filters.page - 1))}
            >
              이전
            </button>
            <span className="min-w-16 text-center text-label tabular-nums text-ink-2">
              {filters.page + 1} / {Math.max(1, data.totalPages)}
            </span>
            <button
              className={buttonClass}
              disabled={filters.page + 1 >= data.totalPages}
              onClick={() => change("page", String(filters.page + 1))}
            >
              다음
            </button>
            {filters.page > 0 && !data.content.length && (
              <button
                className={buttonClass}
                onClick={() => change("page", "0")}
              >
                첫 페이지로
              </button>
            )}
          </nav>
        </>
      )}
    </>
  );
}
