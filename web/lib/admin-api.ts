import { api, ApiError, type Schemas } from "@/lib/api";

export const ADMIN_MOCK = process.env.NEXT_PUBLIC_API_MOCK === "true";
export type AdminDocument = Schemas["AdminDocument"];
export type AdminReport = Schemas["AdminReport"];
export type Sanction = Schemas["Sanction"];
export const mockAdmin: Schemas["Me"] = {
  id: 900,
  nickname: "목 관리자",
  role: "ADMIN",
  status: "ACTIVE",
  suspendedUntil: null,
  nicknameChangeableAt: null,
  socialAccounts: [],
  createdAt: "2026-11-01T00:00:00Z",
};
const operator = { id: 900, nickname: "목 관리자" };
const date = "2026-11-08T00:00:00Z";
const counts = (n: number) => [{ 전형: "일반편입", 인원: n, 원문: String(n) }];
let documents: AdminDocument[] = ["서강대학교", "성균관대학교"].map(
  (name, i) => ({
    id: i + 1,
    university: { id: i + 1, name, campus: "", region: "SEOUL" },
    academicYear: 2027,
    title: `2027학년도 ${name} 편입학 모집요강`,
    status: i ? "NEEDS_REVIEW" : "EXTRACTED",
    pageUrl: null,
    fileUrl: null,
    detectedAt: date,
    reviewedBy: null,
    reviewedAt: null,
    latestExtraction: {
      id: i + 101,
      model: "mock",
      error: null,
      createdAt: date,
      result: {
        학교: name,
        학년도: 2027,
        모집인원: [
          {
            계열: "인문대학",
            단위: "학과",
            모집단위: "국어국문학과",
            인원: counts(4),
          },
          {
            계열: "공과대학",
            단위: "학과",
            모집단위: "컴퓨터공학과",
            인원: counts(6),
          },
        ],
        소계: [],
        총계: counts(i ? 12 : 10),
        일정: [
          {
            항목: "원서접수",
            시작: "2026-12-01 09:00",
            종료: "2026-12-04 17:00",
            비고: "온라인 접수",
          },
          { 항목: "필기시험", 시작: "2027-01-09", 비고: "고사장 별도 공지" },
        ],
      },
      validation: {
        status: i ? "failed" : "passed",
        passed: !i,
        checks: [
          {
            범위: "총계",
            전형: "일반편입",
            합계: 10,
            기재값: i ? 12 : 10,
            status: i ? "failed" : "passed",
            칸_누락: 0,
          },
        ],
        warnings: [],
      },
    },
  }),
);
let reports: AdminReport[] = ["PENDING", "RESOLVED", "DISMISSED"].map(
  (status, i) => ({
    id: i + 1,
    reporter: { id: 10, nickname: "편입준비생" },
    targetType: i ? "COMMENT" : "POST",
    targetId: i + 20,
    target: {
      author: { id: 20, nickname: "대상회원" },
      title: i ? null : "광고성 게시물 신고",
      excerpt: "반복 광고가 게시되었습니다.",
      status: i === 1 ? "HIDDEN" : "PUBLISHED",
    },
    reason: "ADVERTISEMENT",
    detail: "같은 광고를 반복 게시함",
    status: status as AdminReport["status"],
    createdAt: date,
    handledBy: i ? operator : null,
    handledAt: i ? date : null,
  }),
);
let sanctions: Sanction[] = [
  {
    id: 1,
    user: { id: 20, nickname: "대상회원" },
    type: "BAN",
    reason: "반복 광고",
    startsAt: date,
    endsAt: null,
    active: true,
    createdBy: operator,
    createdAt: date,
  },
  {
    id: 2,
    user: { id: 21, nickname: "종료회원" },
    type: "SUSPEND",
    reason: "욕설",
    startsAt: "2026-10-01T00:00:00Z",
    endsAt: "2026-10-08T00:00:00Z",
    active: false,
    createdBy: operator,
    createdAt: "2026-10-01T00:00:00Z",
  },
  {
    id: 3,
    user: { id: 22, nickname: "경고회원" },
    type: "WARNING",
    reason: "이용규칙 안내",
    startsAt: date,
    endsAt: null,
    active: false,
    createdBy: operator,
    createdAt: date,
  },
];
function paged<T>(items: T[], page: number) {
  return structuredClone({
    content: items.slice(page * 20, (page + 1) * 20),
    page,
    size: 20,
    totalElements: items.length,
    totalPages: Math.ceil(items.length / 20),
  });
}
export async function listDocuments(
  page = 0,
): Promise<Schemas["AdminDocumentPage"]> {
  return ADMIN_MOCK
    ? paged(
        documents.filter((d) =>
          ["EXTRACTED", "NEEDS_REVIEW"].includes(d.status),
        ),
        page,
      )
    : api(`/api/admin/admission-documents?page=${page}&size=20`, {
        cache: "no-store",
      });
}
export async function reviewDocument(
  id: number,
  action: "approve" | "reject",
  extractionRunId?: number,
): Promise<AdminDocument> {
  if (action === "approve" && extractionRunId === undefined)
    throw new ApiError(422, "EXTRACTION_UNUSABLE", "추출 결과가 없습니다.");
  if (!ADMIN_MOCK)
    return api(`/api/admin/admission-documents/${id}/${action}`, {
      method: "POST",
      ...(action === "approve"
        ? {
            json: {
              extractionRunId: extractionRunId!,
            } satisfies Schemas["ApproveDocumentRequest"],
          }
        : {}),
    });
  const doc = documents.find((d) => d.id === id)!;
  if (["APPROVED", "REJECTED"].includes(doc.status))
    throw new ApiError(
      409,
      "DOCUMENT_ALREADY_REVIEWED",
      "이미 처리된 요강입니다.",
    );
  if (
    action === "approve" &&
    (!doc.latestExtraction?.result ||
      doc.latestExtraction.error ||
      doc.latestExtraction.id !== extractionRunId)
  )
    throw new ApiError(
      422,
      "EXTRACTION_UNUSABLE",
      "사용할 수 없는 추출 결과입니다.",
    );
  documents = documents.map((d) =>
    d.id === id
      ? {
          ...d,
          status: action === "approve" ? "APPROVED" : "REJECTED",
          reviewedBy: operator,
          reviewedAt: new Date().toISOString(),
        }
      : d,
  );
  return structuredClone(documents.find((d) => d.id === id)!);
}
export async function listReports(
  status: Schemas["ReportStatus"] = "PENDING",
  page = 0,
): Promise<Schemas["AdminReportPage"]> {
  return ADMIN_MOCK
    ? paged(
        reports.filter((r) => r.status === status),
        page,
      )
    : api(`/api/admin/reports?status=${status}&page=${page}&size=20`, {
        cache: "no-store",
      });
}
export async function handleReport(
  id: number,
  body: Schemas["ReportHandleRequest"],
): Promise<AdminReport> {
  if (!ADMIN_MOCK)
    return api(`/api/admin/reports/${id}/handle`, {
      method: "POST",
      json: body,
    });
  const report = reports.find((r) => r.id === id)!;
  if (report.status !== "PENDING")
    throw new ApiError(
      409,
      "REPORT_ALREADY_HANDLED",
      "이미 처리된 신고입니다.",
    );
  reports = reports.map((r) =>
    r.id === id
      ? {
          ...r,
          status: body.action === "RESOLVE" ? "RESOLVED" : "DISMISSED",
          handledBy: operator,
          handledAt: new Date().toISOString(),
          target: {
            ...r.target,
            status:
              body.hideTarget && r.targetType !== "USER"
                ? "HIDDEN"
                : r.target.status,
          },
        }
      : r,
  );
  return structuredClone(reports.find((r) => r.id === id)!);
}
export async function listSanctions(
  activeOnly = false,
  page = 0,
): Promise<Schemas["SanctionPage"]> {
  return ADMIN_MOCK
    ? paged(
        sanctions.filter((s) => !activeOnly || s.active),
        page,
      )
    : api(
        `/api/admin/sanctions?activeOnly=${activeOnly}&page=${page}&size=20`,
        { cache: "no-store" },
      );
}
export async function createSanction(
  body: Schemas["SanctionCreateRequest"],
): Promise<Sanction> {
  if (!ADMIN_MOCK)
    return api("/api/admin/sanctions", { method: "POST", json: body });
  const now = new Date().toISOString();
  const row: Sanction = {
    id: Math.max(...sanctions.map((s) => s.id)) + 1,
    user: { id: body.userId, nickname: `회원 ${body.userId}` },
    type: body.type,
    reason: body.reason,
    startsAt: now,
    endsAt: body.endsAt ?? null,
    active: body.type !== "WARNING",
    createdBy: operator,
    createdAt: now,
  };
  sanctions = [row, ...sanctions];
  return structuredClone(row);
}
export async function releaseSanction(id: number): Promise<Sanction> {
  if (!ADMIN_MOCK)
    return api(`/api/admin/sanctions/${id}/release`, { method: "POST" });
  const row = sanctions.find((s) => s.id === id)!;
  if (!row.active)
    throw new ApiError(
      409,
      "SANCTION_NOT_ACTIVE",
      "이미 종료되었거나 활성 상태가 아닌 제재입니다.",
    );
  sanctions = sanctions.map((s) =>
    s.id === id ? { ...s, active: false, endsAt: new Date().toISOString() } : s,
  );
  return structuredClone(sanctions.find((s) => s.id === id)!);
}
