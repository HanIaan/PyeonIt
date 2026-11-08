"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useMe } from "@/components/auth/auth-provider";
import {
  AuthGate,
  buttonClass,
  Message,
  NicknameForm,
  QueryMessage,
} from "@/components/auth/auth-ui";
import { ApiError, apiErrorMessage, redirectIfAuthRequired } from "@/lib/api";
import {
  changeNickname,
  linkSocial,
  unlinkSocial,
  withdraw,
  type Provider,
} from "@/lib/auth-api";

const providerNames = { KAKAO: "카카오", GOOGLE: "구글" };
function dateLabel(value: string) {
  return (
    new Date(value).toLocaleString("ko-KR", { timeZone: "Asia/Seoul" }) +
    " (한국 시간)"
  );
}
function Profile() {
  const { me, refresh, logout } = useMe();
  const router = useRouter();
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [withdrawStep, setWithdrawStep] = useState(0);
  const [serverChangeableAt, setServerChangeableAt] = useState<string | null>(
    null,
  );
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  if (!me) return null;
  const changeableAt = serverChangeableAt ?? me.nicknameChangeableAt;
  const nicknameLocked = !!changeableAt && Date.parse(changeableAt) > now;
  async function action(work: () => Promise<void>, success = "") {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await work();
      setNotice(success);
    } catch (e) {
      if (!redirectIfAuthRequired(e)) setError(apiErrorMessage(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="space-y-6">
      <section className="space-y-3 rounded-2xl border border-current/20 p-5">
        <h2 className="text-xl font-semibold">{me.nickname}</h2>
        <p>권한: {me.role === "ADMIN" ? "관리자" : "일반 회원"}</p>
        {me.status === "SUSPENDED" && (
          <div className="space-y-2">
            <span className="inline-block rounded-full bg-amber-500/20 px-3 py-1 text-sm font-semibold">
              이용 정지
            </span>
            <p className="text-sm leading-6">
              정지 중에는 글·댓글 작성, 추천, 신고, 이미지 업로드를 할 수
              없습니다.{" "}
              {me.suspendedUntil
                ? `정지 종료: ${dateLabel(me.suspendedUntil)}`
                : "무기한 정지 상태입니다."}
            </p>
          </div>
        )}
      </section>
      <a href="/me/notifications" onClick={(event) => { event.preventDefault(); router.push("/me/notifications"); }} className="block text-sm underline underline-offset-4">알림 설정</a>
      <QueryMessage />
      <Message>{error}</Message>
      {notice && (
        <p role="status" className="rounded-xl bg-emerald-500/10 p-3 text-sm">
          {notice}
        </p>
      )}
      <section className="space-y-4 rounded-2xl border border-current/20 p-5">
        <h2 className="text-xl font-semibold">닉네임 변경</h2>
        <p className="text-sm opacity-70">
          닉네임은 30일에 한 번 변경할 수 있습니다.
        </p>
        {nicknameLocked && (
          <p className="text-sm" role="status">
            변경 가능: {dateLabel(changeableAt!)}
          </p>
        )}
        <NicknameForm
          initial={me.nickname}
          label="닉네임 변경"
          disabled={nicknameLocked || busy}
          onSave={async (nickname) => {
            setError("");
            setNotice("");
            try {
              await changeNickname(nickname);
              setServerChangeableAt(null);
              await refresh();
              setNotice("닉네임을 변경했습니다.");
            } catch (e) {
              if (
                e instanceof ApiError &&
                e.code === "NICKNAME_CHANGE_TOO_SOON"
              ) {
                setServerChangeableAt(e.nicknameChangeableAt);
                await refresh();
              }
              if (!redirectIfAuthRequired(e)) throw e;
            }
          }}
        />
      </section>
      <section className="space-y-4 rounded-2xl border border-current/20 p-5">
        <h2 className="text-xl font-semibold">연결된 소셜 계정</h2>
        <p className="text-sm leading-6 opacity-70">
          마지막 로그인 계정은 해제할 수 없습니다. 다른 계정을 먼저 연결해
          주세요.
        </p>
        <ul className="divide-y divide-current/10">
          {me.socialAccounts.map((account) => (
            <li
              key={account.provider}
              className="flex flex-wrap items-center justify-between gap-3 py-3"
            >
              <div>
                <p className="font-medium">{providerNames[account.provider]}</p>
                <p className="text-xs opacity-70">
                  연결일: {dateLabel(account.linkedAt)}
                </p>
              </div>
              <button
                className={buttonClass}
                disabled={busy || me.socialAccounts.length <= 1}
                onClick={() =>
                  void action(async () => {
                    await unlinkSocial(account.provider);
                    await refresh();
                  }, "소셜 계정 연결을 해제했습니다.")
                }
              >
                연결 해제
              </button>
            </li>
          ))}
        </ul>
        <div className="flex flex-wrap gap-2">
          {(["KAKAO", "GOOGLE"] as Provider[])
            .filter(
              (provider) =>
                !me.socialAccounts.some((a) => a.provider === provider),
            )
            .map((provider) => (
              <button
                key={provider}
                className={buttonClass}
                disabled={busy}
                onClick={() =>
                  void action(async () => {
                    await linkSocial(provider);
                    await refresh();
                  }, "소셜 계정 연결을 진행했습니다.")
                }
              >
                {providerNames[provider]} 연결 추가
              </button>
            ))}
        </div>
      </section>
      <section className="space-y-4 rounded-2xl border border-current/20 p-5">
        <h2 className="text-xl font-semibold">계정 관리</h2>
        <button
          className={buttonClass}
          disabled={busy}
          onClick={() => void action(logout)}
        >
          로그아웃
        </button>
        <div className="space-y-3 border-t border-current/15 pt-4">
          <h3 className="font-semibold">회원 탈퇴</h3>
          <p className="text-sm leading-6">
            탈퇴하면 소셜 계정 연결이 모두 삭제되고 닉네임이 비워지며 작성한
            글은 남는다.
          </p>
          {withdrawStep === 0 && (
            <button
              className={`${buttonClass} text-red-600 dark:text-red-400`}
              disabled={busy}
              onClick={() => setWithdrawStep(1)}
            >
              회원 탈퇴
            </button>
          )}
          {withdrawStep === 1 && (
            <div className="space-y-3">
              <p className="text-sm">
                1/2 · 위 안내를 확인하셨나요? 탈퇴한 계정은 복구할 수 없습니다.
              </p>
              <div className="flex gap-2">
                <button
                  className={buttonClass}
                  onClick={() => setWithdrawStep(2)}
                >
                  안내 확인, 계속
                </button>
                <button
                  className={buttonClass}
                  onClick={() => setWithdrawStep(0)}
                >
                  취소
                </button>
              </div>
            </div>
          )}
          {withdrawStep === 2 && (
            <div className="space-y-3">
              <p className="text-sm font-semibold">
                2/2 · 정말 탈퇴하시겠습니까? 최종 확인을 누르면 탈퇴합니다.
              </p>
              <div className="flex gap-2">
                <button
                  className={`${buttonClass} text-red-600 dark:text-red-400`}
                  disabled={busy}
                  onClick={() =>
                    void action(async () => {
                      await withdraw();
                      await refresh();
                      router.replace("/login");
                    })
                  }
                >
                  {busy ? "처리 중…" : "최종 확인 및 탈퇴"}
                </button>
                <button
                  className={buttonClass}
                  disabled={busy}
                  onClick={() => setWithdrawStep(0)}
                >
                  취소
                </button>
              </div>
            </div>
          )}
        </div>
      </section>
    </div>
  );
}
export default function MePage() {
  return (
    <section className="mx-auto max-w-xl space-y-6">
      <h1 className="text-3xl font-bold">내 정보</h1>
      <AuthGate>
        <Profile />
      </AuthGate>
    </section>
  );
}
