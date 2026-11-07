import { ApiError, type Schemas } from "./api";
import { getMe } from "./auth-api";
import type { NoticeFilters } from "./universities-api";

// NEXT_PUBLIC_API_MOCK=true 일 때만 쓰는 명세 스키마 그대로의 샘플(서강·성균관 2026).
type U = Schemas["UniversitySummary"];
type Dept = Schemas["DepartmentNode"];
type Quota = Schemas["Quota"];

const GENERAL = { id: 1, code: "GENERAL", name: "일반편입" };
const BACHELOR = { id: 2, code: "BACHELOR", name: "학사편입" };

export const mockUniversities: (U & { admissionUrl: string | null })[] = [
  {
    id: 1,
    name: "서강대학교",
    campus: "",
    region: "SEOUL",
    admissionUrl:
      "https://admission.sogang.ac.kr/enter/html/transfer/guide.asp",
  },
  {
    id: 2,
    name: "성균관대학교",
    campus: "",
    region: "SEOUL",
    admissionUrl:
      "https://admission.skku.edu/admission/html/transfer/guide.html",
  },
];

let qid = 1;
const q = (
  track: Quota["track"],
  rawTrackName: string,
  kind: Quota["kind"],
  rawText: string,
): Quota => ({
  id: qid++,
  track,
  rawTrackName,
  kind,
  count:
    kind === "EXACT" ? Number(rawText) : kind === "NOT_RECRUITING" ? 0 : null,
  rawText,
});
const node = (
  id: number,
  level: Dept["level"],
  name: string,
  quotas: Quota[],
  children: Dept[] = [],
): Dept => ({
  id,
  level,
  name,
  previousDepartmentId: null,
  quotas,
  children,
});

const sogangDepts: Dept[] = [
  node(
    101,
    "COLLEGE",
    "인문대학",
    [],
    [
      node(102, "MAJOR", "국어국문학과", [
        q(GENERAL, "일반편입", "EXACT", "3"),
        q(BACHELOR, "학사편입", "RANGE", "약간명"),
      ]),
      node(103, "MAJOR", "사학과", [
        q(GENERAL, "일반편입", "EXACT", "2"),
        q(BACHELOR, "학사편입", "NOT_RECRUITING", "-"),
      ]),
    ],
  ),
  node(
    104,
    "COLLEGE",
    "경영대학",
    [],
    [
      node(105, "MAJOR", "경영학과", [
        q(GENERAL, "일반편입", "EXACT", "12"),
        q(BACHELOR, "학사편입", "RANGE", "1명 이내"),
      ]),
    ],
  ),
  // 단과대 단위 모집 사례
  node(106, "COLLEGE", "지식융합미디어대학", [
    q(GENERAL, "일반편입", "EXACT", "5"),
    q(null, "농어촌 특별편입", "RANGE", "약간명"),
  ]),
];

const skkuDepts: Dept[] = [
  node(
    201,
    "COLLEGE",
    "사회과학대학",
    [],
    [
      node(202, "MAJOR", "경제학과", [
        q(GENERAL, "일반편입", "EXACT", "8"),
        q(null, "기회균형 편입", "RANGE", "2명 이내"),
      ]),
      node(203, "MAJOR", "심리학과", [
        q(GENERAL, "일반편입", "EXACT", "4"),
        q(null, "기회균형 편입", "NOT_RECRUITING", "미선발"),
      ]),
    ],
  ),
  node(
    204,
    "COLLEGE",
    "공과대학",
    [],
    [
      node(205, "MAJOR", "기계공학부", [
        q(GENERAL, "일반편입", "EXACT", "10"),
        q(null, "기회균형 편입", "EXACT", "1"),
      ]),
    ],
  ),
];

let sid = 1;
const s = (
  type: Schemas["ScheduleType"],
  rawLabel: string,
  startsAt: string,
  endsAt: string | null,
  allDay = false,
): Schemas["Schedule"] => ({
  id: sid++,
  type,
  rawLabel,
  startsAt,
  endsAt,
  allDay,
  note: null,
});

export const mockGuides: Record<number, Schemas["AdmissionGuide"]> = {
  1: {
    documentId: 11,
    academicYear: 2026,
    title: "2026학년도 서강대학교 편입학 모집요강",
    pageUrl: "https://admission.sogang.ac.kr/enter/html/transfer/guide.asp",
    fileUrl: "https://admission.sogang.ac.kr/files/2026_transfer_guide.pdf",
    approvedAt: "2025-11-20T10:00:00+09:00",
    departments: sogangDepts,
    schedules: [
      s(
        "APPLICATION",
        "원서접수",
        "2025-12-29T10:00:00+09:00",
        "2025-12-31T17:00:00+09:00",
      ),
      s(
        "DOCUMENT_SUBMISSION",
        "서류제출",
        "2025-12-29T00:00:00+09:00",
        "2026-01-05T00:00:00+09:00",
        true,
      ),
      s("EXAM", "필기고사", "2026-01-17T10:00:00+09:00", null),
      s(
        "RESULT_FIRST_STAGE",
        "1단계 합격자 발표",
        "2026-01-23T14:00:00+09:00",
        null,
      ),
      s("INTERVIEW", "면접", "2026-01-29T00:00:00+09:00", null, true),
      s("RESULT_FINAL", "최종 합격자 발표", "2026-02-06T14:00:00+09:00", null),
    ],
  },
  2: {
    documentId: 21,
    academicYear: 2026,
    title: "2026학년도 성균관대학교 편입학 모집요강",
    pageUrl: "https://admission.skku.edu/admission/html/transfer/guide.html",
    fileUrl: "https://admission.skku.edu/files/2026_transfer_guide.pdf",
    approvedAt: "2025-11-25T09:00:00+09:00",
    departments: skkuDepts,
    schedules: [
      s(
        "APPLICATION",
        "원서접수",
        "2025-12-30T10:00:00+09:00",
        "2026-01-02T18:00:00+09:00",
      ),
      s("EXAM", "편입학 필기시험", "2026-01-18T09:00:00+09:00", null),
      s(
        "RESULT_FIRST_STAGE",
        "1단계 합격자 발표",
        "2026-01-28T00:00:00+09:00",
        null,
        true,
      ),
      s("INTERVIEW", "면접고사", "2026-02-02T13:00:00+09:00", null),
      s(
        "RESULT_FINAL",
        "최종 합격자 발표",
        "2026-02-10T00:00:00+09:00",
        null,
        true,
      ),
      s(
        "REGISTRATION",
        "등록",
        "2026-02-12T00:00:00+09:00",
        "2026-02-14T00:00:00+09:00",
        true,
      ),
    ],
  },
};

const mockFavorites = new Set<number>([1]);

async function mockIsMember(): Promise<boolean> {
  try {
    await getMe();
    return true;
  } catch (error) {
    if (
      error instanceof ApiError &&
      (error.status === 401 || error.code === "SIGNUP_REQUIRED")
    )
      return false;
    throw error;
  }
}

export async function listMockUniversities(
  region?: Schemas["Region"],
): Promise<Schemas["University"][]> {
  const member = await mockIsMember();
  return mockUniversities
    .filter((u) => !region || u.region === region)
    .map((u) => ({
      ...u,
      favorite: member && mockFavorites.has(u.id),
    }));
}

export async function getMockUniversity(
  id: number,
  academicYear?: number,
): Promise<Schemas["UniversityDetail"]> {
  const university = (await listMockUniversities()).find((u) => u.id === id);
  if (!university)
    throw new ApiError(404, "NOT_FOUND", "학교를 찾을 수 없습니다.");
  return {
    ...university,
    academicYears: [2026],
    guide: !academicYear || academicYear === 2026 ? mockGuides[id] : null,
  };
}

export async function listMockNotices(
  filters: NoticeFilters,
): Promise<Schemas["NoticePage"]> {
  if (filters.favoritesOnly && !(await mockIsMember()))
    throw new ApiError(401, "UNAUTHORIZED", "로그인이 필요합니다.");
  const content: Schemas["NoticeSummary"][] = (
    await listMockUniversities(filters.region)
  )
    .filter((u) => !filters.favoritesOnly || u.favorite)
    .map((university) => {
      const guide = mockGuides[university.id];
      const application = guide.schedules.find((s) => s.type === "APPLICATION");
      return {
        documentId: guide.documentId,
        university,
        academicYear: guide.academicYear,
        title: guide.title,
        approvedAt: guide.approvedAt,
        applicationStartsAt: application?.startsAt ?? null,
        applicationEndsAt: application?.endsAt ?? null,
        pageUrl: guide.pageUrl,
        fileUrl: guide.fileUrl,
      };
    })
    .filter(
      (n) => !filters.academicYear || n.academicYear === filters.academicYear,
    )
    .sort((a, b) => Date.parse(b.approvedAt) - Date.parse(a.approvedAt));
  return {
    content: content.slice(
      filters.page * filters.size,
      (filters.page + 1) * filters.size,
    ),
    page: filters.page,
    size: filters.size,
    totalElements: content.length,
    totalPages: Math.ceil(content.length / filters.size),
  };
}

export function listMockCalendar(
  from: string,
  to: string,
  universityId?: number,
): Schemas["CalendarEvent"][] {
  const start = Date.parse(from),
    end = Date.parse(to);
  if (
    !Number.isFinite(start) ||
    !Number.isFinite(end) ||
    end <= start ||
    end - start > 100 * 86400000
  )
    throw new ApiError(400, "VALIDATION_FAILED", "조회 기간을 확인해 주세요.");
  return mockUniversities
    .filter((u) => !universityId || u.id === universityId)
    .flatMap((university) => {
      const guide = mockGuides[university.id];
      return guide.schedules.map((s) => ({
        ...s,
        documentId: guide.documentId,
        academicYear: guide.academicYear,
        university,
      }));
    })
    .filter(
      (s) =>
        Date.parse(s.startsAt) < end &&
        Date.parse(s.endsAt ?? s.startsAt) >= start,
    )
    .sort((a, b) => Date.parse(a.startsAt) - Date.parse(b.startsAt));
}

export async function setMockFavorite(
  id: number,
  favorite: boolean,
): Promise<void> {
  if (!(await mockIsMember()))
    throw new ApiError(401, "UNAUTHORIZED", "로그인이 필요합니다.");
  if (favorite) mockFavorites.add(id);
  else mockFavorites.delete(id);
}
