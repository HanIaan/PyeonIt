"use client";

import Link from "next/link";
import { useState } from "react";
import { listUniversities, type Schemas } from "@/lib/universities-api";
import { regions } from "@/lib/universities-display";
import {
  cardClass,
  FavoriteButton,
  PageIntro,
  RegionSelect,
  ResourceMessage,
  useUniversityResource,
  useUniversitySession,
} from "./shared";

export default function UniversitiesView() {
  const [region, setRegion] = useState("");
  const session = useUniversitySession();
  const result = useUniversityResource(
    `universities:${region}:${session.identity}`,
    () => listUniversities((region as Schemas["Region"]) || undefined),
  );
  return (
    <>
      <PageIntro
        title="학교 찾기"
        description="관심 있는 학교를 저장하고 학년도별 모집인원과 일정을 확인하세요."
      />
      <div className="mb-6 max-w-xs">
        <RegionSelect value={region} onChange={setRegion} />
      </div>
      <ResourceMessage {...session} />
      <ResourceMessage {...result} />
      {result.data && (
        <ul className="grid gap-4 sm:grid-cols-2">
          {result.data.map((u) => (
            <li
              key={u.id}
              className={`${cardClass} flex flex-col items-start gap-4`}
            >
              <div>
                <p className="mb-2 text-xs text-slate-500">
                  {regions[u.region]} {u.campus}
                </p>
                <h2 className="text-xl font-semibold">
                  <Link
                    href={`/universities/${u.id}`}
                    className="hover:underline"
                  >
                    {u.name} →
                  </Link>
                </h2>
              </div>
              <FavoriteButton
                key={`${u.id}:${u.favorite}`}
                id={u.id}
                initialFavorite={u.favorite}
                session={session}
              />
              {u.admissionUrl && (
                <a
                  href={u.admissionUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-sm text-blue-600 underline dark:text-blue-400"
                >
                  입학처 ↗
                </a>
              )}
            </li>
          ))}
        </ul>
      )}
      {result.data?.length === 0 && (
        <p className={cardClass}>해당 지역에 등록된 학교가 없습니다.</p>
      )}
    </>
  );
}
