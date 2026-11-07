"use client";
import { useCallback, useState, type FormEvent } from "react";
import {
  listSanctions,
  createSanction,
  releaseSanction,
  type Sanction,
} from "@/lib/admin-api";
import type { Schemas } from "@/lib/api";
import {
  button,
  secondary,
  input,
  table,
  formatDate,
  ResourceState,
  Pagination,
  useAdminResource,
  useAdminFailure,
  errorMessage,
} from "@/components/admin/admin-shell";
import { Confirm } from "@/components/admin/confirm";
const types = { WARNING: "경고", SUSPEND: "기간 정지", BAN: "영구 정지" };
export default function SanctionsPage() {
  const [activeOnly, setActiveOnly] = useState(false);
  const [page, setPage] = useState(0);
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-lg font-bold">회원 제재</h2>
        <label className="flex max-w-full flex-wrap items-center gap-2 text-sm">
          조회 범위
          <select
            className={input}
            value={String(activeOnly)}
            onChange={(e) => {
              setActiveOnly(e.target.value === "true");
              setPage(0);
            }}
          >
            <option value="false">전체 (활성 / 해제·만료 / 경고)</option>
            <option value="true">활성 제재</option>
          </select>
        </label>
      </div>
      <Sanctions
        key={`${activeOnly}-${page}`}
        activeOnly={activeOnly}
        page={page}
        setPage={setPage}
      />
    </div>
  );
}
function Sanctions({
  activeOnly,
  page,
  setPage,
}: {
  activeOnly: boolean;
  page: number;
  setPage: (page: number) => void;
}) {
  const load = useCallback(
    () => listSanctions(activeOnly, page),
    [activeOnly, page],
  );
  const resource = useAdminResource(load);
  const fail = useAdminFailure();
  const [type, setType] = useState<Schemas["SanctionType"]>("WARNING");
  const [userId, setUserId] = useState("");
  const [reason, setReason] = useState("");
  const [endsAt, setEndsAt] = useState("");
  const [draft, setDraft] = useState<Schemas["SanctionCreateRequest"] | null>(
    null,
  );
  const [release, setRelease] = useState<Sanction | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  function prepare(e: FormEvent) {
    e.preventDefault();
    setError("");
    if (
      !Number.isSafeInteger(Number(userId)) ||
      Number(userId) < 1 ||
      !reason.trim()
    ) {
      setError("올바른 회원 ID와 제재 사유를 입력해 주세요.");
      return;
    }
    const end = endsAt ? new Date(`${endsAt}:00+09:00`) : null;
    if (
      type === "SUSPEND" &&
      (!end || Number.isNaN(end.getTime()) || end.getTime() <= Date.now())
    ) {
      setError(
        "기간 정지의 종료 시각은 현재 이후여야 합니다. 입력 시각은 한국시간입니다.",
      );
      return;
    }
    setDraft({
      userId: Number(userId),
      type,
      reason: reason.trim(),
      endsAt: type === "SUSPEND" ? end!.toISOString() : null,
    });
  }
  async function submit() {
    if (busy || (!draft && !release)) return;
    setBusy(true);
    setError("");
    try {
      if (draft) {
        await createSanction(draft);
        setMessage(
          `회원 #${draft.userId}에게 ${types[draft.type]} 제재를 등록했습니다.`,
        );
        setUserId("");
        setReason("");
        setEndsAt("");
      } else if (release) {
        await releaseSanction(release.id);
        setMessage(`제재 #${release.id}을 해제했습니다.`);
      }
      resource.reload();
    } catch (e) {
      fail(e);
      setError(errorMessage(e));
    } finally {
      setBusy(false);
      setDraft(null);
      setRelease(null);
    }
  }
  return (
    <div className="space-y-4">
      {message && (
        <p role="status" className="rounded-lg bg-teal-50 p-3 text-sm">
          {message}
        </p>
      )}
      {error && (
        <p
          role="alert"
          className="rounded-lg bg-red-50 p-3 text-sm text-red-800"
        >
          {error}
        </p>
      )}
      <form
        onSubmit={prepare}
        className="space-y-4 rounded-xl border border-slate-200 bg-white p-5"
      >
        <h3 className="font-bold">제재 등록</h3>
        <fieldset disabled={busy} className="grid gap-4 sm:grid-cols-2">
          <label className="text-sm">
            대상 회원 ID
            <input
              className={`${input} mt-1`}
              type="number"
              min="1"
              step="1"
              required
              value={userId}
              onChange={(e) => setUserId(e.target.value)}
            />
          </label>
          <label className="text-sm">
            제재 유형
            <select
              className={`${input} mt-1`}
              value={type}
              onChange={(e) =>
                setType(e.target.value as Schemas["SanctionType"])
              }
            >
              {Object.entries(types).map(([value, label]) => (
                <option key={value} value={value}>
                  {label} ({value})
                </option>
              ))}
            </select>
          </label>
          <label className="text-sm sm:col-span-2">
            사유 (필수, 최대 500자)
            <textarea
              className={`${input} mt-1`}
              required
              maxLength={500}
              rows={3}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </label>
          <div className="text-sm">
            <p>시작 시각</p>
            <p className="mt-2 text-slate-600">등록 즉시 · 서버에서 기록</p>
          </div>
          {type === "SUSPEND" ? (
            <label className="text-sm">
              종료 시각 (한국시간)
              <input
                className={`${input} mt-1`}
                type="datetime-local"
                required
                value={endsAt}
                onChange={(e) => setEndsAt(e.target.value)}
              />
            </label>
          ) : (
            <p className="self-center text-sm text-slate-600">
              {type === "BAN"
                ? "무기한 정지 · 해제 전까지 유지"
                : "종료 시각 없음 · 회원 상태를 변경하지 않는 경고"}
            </p>
          )}
        </fieldset>
        <button className={button} disabled={busy} type="submit">
          등록 검토
        </button>
      </form>
      <div className="flex items-center justify-between gap-3">
        <p className="text-xs text-slate-500">
          해제와 자연 만료는 API에서 구분하지 않아 종료 이력으로 함께
          표시합니다.
        </p>
        <button className={secondary} disabled={busy} onClick={resource.reload}>
          새로고침
        </button>
      </div>
      <ResourceState {...resource} />
      {resource.data && (
        <>
          <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white">
            <table className={table}>
              <caption className="p-3 text-left">
                {activeOnly ? "활성" : "전체"} 제재{" "}
                {resource.data.totalElements}건 · 한국시간
              </caption>
              <thead>
                <tr>
                  <th>회원</th>
                  <th>유형 / 상태</th>
                  <th>사유</th>
                  <th>기간</th>
                  <th>등록 관리자</th>
                  <th>관리</th>
                </tr>
              </thead>
              <tbody>
                {resource.data.content.map((s) => (
                  <tr key={s.id}>
                    <td>
                      {s.user.nickname ?? "탈퇴 회원"}
                      <p className="text-slate-500">#{s.user.id}</p>
                    </td>
                    <td>
                      {types[s.type]}
                      <p
                        className={s.active ? "text-red-700" : "text-slate-500"}
                      >
                        {s.active
                          ? "활성"
                          : s.type === "WARNING"
                            ? "경고 기록"
                            : "해제·만료"}
                      </p>
                    </td>
                    <td className="min-w-40 whitespace-pre-wrap break-words">
                      {s.reason}
                    </td>
                    <td className="whitespace-nowrap">
                      {formatDate(s.startsAt)}
                      <p>
                        ~{" "}
                        {s.endsAt
                          ? formatDate(s.endsAt)
                          : s.type === "WARNING"
                            ? "종료 없음"
                            : "무기한"}
                      </p>
                    </td>
                    <td>{s.createdBy.nickname ?? "관리자"}</td>
                    <td>
                      <button
                        className={secondary}
                        disabled={busy || !s.active}
                        onClick={() => setRelease(s)}
                      >
                        해제
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {!resource.data.content.length && (
              <p className="p-6 text-sm">제재 내역이 없습니다.</p>
            )}
          </div>
          <Pagination
            page={page}
            totalPages={resource.data.totalPages}
            onChange={setPage}
          />
        </>
      )}
      {(draft || release) && (
        <Confirm
          title={draft ? "제재 등록 확인" : "제재 해제 확인"}
          busy={busy}
          onCancel={() => {
            setDraft(null);
            setRelease(null);
          }}
          onConfirm={() => void submit()}
        >
          {draft ? (
            <>
              <p>
                대상 회원 #{draft.userId} · {types[draft.type]}
              </p>
              <p className="whitespace-pre-wrap break-words">
                사유: {draft.reason}
              </p>
              <p>
                기간: 등록 즉시 ~{" "}
                {draft.endsAt
                  ? formatDate(draft.endsAt)
                  : draft.type === "WARNING"
                    ? "종료 없음"
                    : "무기한"}
              </p>
            </>
          ) : (
            <p>
              {release?.user.nickname ?? "회원"} (#{release?.user.id})의 제재 #
              {release?.id}을 즉시 해제합니다. 다른 활성 제재가 있으면 회원의
              정지 상태는 유지됩니다.
            </p>
          )}
        </Confirm>
      )}
    </div>
  );
}
