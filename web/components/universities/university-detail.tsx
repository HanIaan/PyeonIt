"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { getUniversity, type Schemas } from "@/lib/universities-api";
import { quotaColumn, regions } from "@/lib/universities-display";
import { buttonClass } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import {
  cardClass,
  FavoriteButton,
  inputClass,
  PageIntro,
  ResourceMessage,
  ScheduleList,
  SourceLinks,
  useUniversityResource,
  useUniversitySession,
} from "./shared";

function QuotaTable({
  departments,
}: {
  departments: Schemas["DepartmentNode"][];
}) {
  const rows: { node: Schemas["DepartmentNode"]; depth: number }[] = [];
  const visit = (nodes: Schemas["DepartmentNode"][], depth: number) =>
    nodes.forEach((node) => {
      rows.push({ node, depth });
      visit(node.children, depth + 1);
    });
  visit(departments, 0);
  const columns = new Map<string, string>();
  rows.forEach(({ node }) =>
    node.quotas.forEach((q) =>
      columns.set(
        quotaColumn(q),
        q.track
          ? q.rawTrackName !== q.track.name
            ? `${q.track.name} (${q.rawTrackName})`
            : q.track.name
          : q.rawTrackName,
      ),
    ),
  );
  if (!rows.length)
    return (
      <p className="py-4 text-label text-ink-3">등록된 모집인원이 없습니다.</p>
    );
  return (
    <div
      className="mt-3 overflow-x-auto rounded-control border border-line"
      role="region"
      aria-label="모집인원 표 (가로 스크롤 가능)"
      tabIndex={0}
    >
      <table className="w-full min-w-[36rem] border-collapse text-left text-label tabular-nums">
        <caption className="sr-only">단과대 및 학과별 전형별 모집인원</caption>
        <thead className="bg-paper text-ink-2">
          <tr>
            <th scope="col" className="min-w-48 p-3 font-semibold">
              단과대 / 학과
            </th>
            {[...columns].map(([key, label]) => (
              <th key={key} scope="col" className="min-w-32 p-3 font-semibold">
                {label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map(({ node, depth }) => (
            <tr
              key={node.id}
              className={`border-t border-line ${node.level === "COLLEGE" ? "bg-paper font-semibold" : "font-normal"}`}
            >
              <th
                scope="row"
                className="p-3"
                style={{ paddingLeft: `${12 + depth * 20}px` }}
              >
                {node.name}
              </th>
              {[...columns.keys()].map((key) => {
                const quotas = node.quotas.filter(
                  (q) => quotaColumn(q) === key,
                );
                return (
                  <td key={key} className="p-3">
                    {quotas.length
                      ? quotas
                          .map((q) =>
                            q.kind === "EXACT"
                              ? q.count
                              : q.kind === "NOT_RECRUITING"
                                ? "미선발"
                                : q.rawText,
                          )
                          .join(" / ")
                      : "—"}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default function UniversityDetail({ id }: { id: number }) {
  const params = useSearchParams();
  const router = useRouter();
  const rawYear = params.get("academicYear");
  const year =
    rawYear &&
    /^\d{4}$/.test(rawYear) &&
    Number(rawYear) >= 2000 &&
    Number(rawYear) <= 2100
      ? Number(rawYear)
      : undefined;
  const session = useUniversitySession();
  const result = useUniversityResource(
    `university:${id}:${year}:${session.identity}`,
    () => getUniversity(id, year),
  );
  const u = result.data;
  return (
    <>
      <Link href="/universities" className={buttonClass("ghost", "-ml-3 mb-3")}>
        ← 학교 목록
      </Link>
      <ResourceMessage {...result} />
      {u && (
        <>
          <PageIntro
            title={u.name}
            description={`${regions[u.region]} ${u.campus}`}
          />
          <ResourceMessage {...session} />
          <div className="mb-6 flex flex-wrap items-end gap-3">
            <FavoriteButton
              key={`${u.id}:${u.favorite}`}
              id={u.id}
              initialFavorite={u.favorite}
              session={session}
            />
            <label className="grid w-full gap-1.5 text-label font-medium sm:w-56">
              모집 학년도
              <select
                className={inputClass}
                value={year ?? u.guide?.academicYear ?? ""}
                onChange={(e) => {
                  const next = new URLSearchParams(params);
                  next.set("academicYear", e.target.value);
                  router.push(`/universities/${id}?${next}`);
                }}
              >
                <option value="" disabled>
                  학년도를 선택하세요
                </option>
                {year && !u.academicYears.includes(year) && (
                  <option value={year}>{year}학년도 (요강 없음)</option>
                )}
                {u.academicYears.map((y) => (
                  <option key={y} value={y}>
                    {y}학년도
                  </option>
                ))}
              </select>
            </label>
          </div>
          {u.guide ? (
            <>
              <section className={cardClass}>
                <h2 className="mb-3 text-title font-semibold">
                  {u.guide.title}
                </h2>
                <SourceLinks {...u.guide} />
                <div className="mt-6 flex flex-wrap items-baseline justify-between gap-2">
                  <h3 className="font-semibold">모집인원</h3>
                  <p className="text-caption text-ink-3">단위: 명 · — 미등록</p>
                </div>
                <QuotaTable departments={u.guide.departments} />
              </section>
              <section className={`${cardClass} mt-4`}>
                <h2 className="text-title font-semibold">전형 일정</h2>
                <ScheduleList
                  schedules={[...u.guide.schedules].sort(
                    (a, b) => Date.parse(a.startsAt) - Date.parse(b.startsAt),
                  )}
                />
              </section>
            </>
          ) : (
            <EmptyState>선택한 학년도의 게시된 모집요강이 없습니다.</EmptyState>
          )}
        </>
      )}
    </>
  );
}
