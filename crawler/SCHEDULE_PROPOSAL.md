# 일일 backfill 자동 실행 제안 (등록 안 함)

> 이 문서는 제안서다. `launchctl`·`crontab` 명령은 **사용자 승인 전에는 실행하지 않는다.** 아래 명령은 승인 뒤 사용자가 직접 실행한다.

## 무엇을 실행하나

`crawler/scripts/daily-backfill.sh` 하나를 하루 한 번 실행한다.

- `pyeonit-crawler backfill --year 2026 --output artifacts/t6-chain`을 실행한다. 통과한 학교는 건너뛰고 ① → ② → ③ 순서로 처리한다. 하루 요청 예산(기본 20)을 넘지 않는다.
- 예산은 `crawler/artifacts/quota-state.json`에 태평양시 날짜별로 누적된다. 같은 날 여러 번 실행돼도(잠자기에서 깨어난 뒤 다시 실행되는 경우 등) 합계가 20을 넘지 않는다. 예산이 바닥나면 남은 학교는 `QUOTA_WAIT`이고 종료 코드는 0이다.
- 이어서 `backfill --next-run`으로 다음 권장 실행 시각을 로그에 남긴다.
- 추가 인자는 그대로 전달된다. 예: `daily-backfill.sh --model-chain gemini-3.8-flash`.

## 실행 시각 정하기

Gemini 무료 일일 한도는 **미국 태평양시 자정**에 초기화된다. T6에서 받은 429 응답의 `retryDelay`(예: 16:52:28 UTC + 54457s = 08:00:05 UTC)가 모두 08:00 UTC ± 몇 초를 가리켰으므로 이 가정은 확인됐다. 오차는 `collected_at`이 429보다 조금 늦게 기록되는 정도다.

| 기간 | 태평양 자정 | KST |
| --- | --- | --- |
| 표준시(PST, 11월 첫 일요일 ~ 3월 둘째 일요일) | 08:00 UTC | 17:00 |
| 서머타임(PDT) | 07:00 UTC | 16:00 |

KST에는 서머타임이 없다. 그래서 **매일 17:10 KST** 한 번이면 일 년 내내 리셋 직후가 된다(10분은 여유). 실제 기준은 마지막 429의 `retryDelay`다. 실행 뒤 로그의 `--next-run` 출력(마지막 429 시각 + retryDelay + 5분, KST/UTC 표기)이 17:10보다 늦으면 시각을 그만큼 늦춘다. 출력에 `태평양 자정 리셋 가정과의 차이`가 크게 나오면 리셋 가정이 바뀐 것이므로 조율자에게 알린다.

## 공통 준비

```sh
REPO=/Users/iaan/.superset/worktrees/PyeonIt/feat/crawler   # 실제 저장소 경로로 바꾼다
chmod 600 "$REPO/.env"                                      # GEMINI_API_KEY(무료 키)만 둔다
"$REPO/crawler/scripts/daily-backfill.sh" --dry-run         # 수동 1회 점검(Gemini 호출 0)
```

## 방식 A: macOS launchd (권장)

`~/Library/LaunchAgents/com.pyeonit.daily-backfill.plist`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>com.pyeonit.daily-backfill</string>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/sh</string>
    <string>/Users/iaan/.superset/worktrees/PyeonIt/feat/crawler/crawler/scripts/daily-backfill.sh</string>
  </array>
  <key>StartCalendarInterval</key>
  <dict>
    <key>Hour</key>
    <integer>17</integer>
    <key>Minute</key>
    <integer>10</integer>
  </dict>
  <key>StandardOutPath</key>
  <string>/Users/iaan/.superset/worktrees/PyeonIt/feat/crawler/crawler/artifacts/logs/launchd.out.log</string>
  <key>StandardErrorPath</key>
  <string>/Users/iaan/.superset/worktrees/PyeonIt/feat/crawler/crawler/artifacts/logs/launchd.err.log</string>
  <key>RunAtLoad</key>
  <false/>
</dict>
</plist>
```

등록·해제(승인 뒤 사용자가 직접 실행):

```sh
plutil -lint ~/Library/LaunchAgents/com.pyeonit.daily-backfill.plist
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.pyeonit.daily-backfill.plist
launchctl print gui/$(id -u)/com.pyeonit.daily-backfill        # 상태 확인
launchctl bootout gui/$(id -u) ~/Library/LaunchAgents/com.pyeonit.daily-backfill.plist   # 해제
rm ~/Library/LaunchAgents/com.pyeonit.daily-backfill.plist
```

- **잠자기**: 17:10에 Mac이 잠자고 있으면 launchd가 깨어날 때 한 번 실행한다(놓친 여러 회차는 하나로 합쳐진다).
- **전원 꺼짐·로그아웃**: LaunchAgent는 로그인한 사용자 세션에서만 돈다. 꺼져 있던 회차는 실행되지 않고, 다음 날 17:10에 다시 돈다. 반드시 그 시각에 깨워야 하면 `sudo pmset repeat wakeorpoweron MTWRFSU 17:05:00`을 쓸 수 있다(시스템 설정 변경이라 별도 승인이 필요하다).
- **늦은 실행도 안전한 이유**: 예산 파일이 태평양시 날짜로 누적되므로 깨어난 시각이 언제든 하루 20요청을 넘지 않는다. 리셋 전(16:00~17:00 KST 이전)에 깨어나 실행되면 전날 한도 안에서 처리되어 429만 받을 수 있다. 이때도 429는 예산에 포함되고 `QUOTA_WAIT`로 끝난다.

## 방식 B: cron

`crontab -e`로 아래 한 줄을 추가한다(승인 뒤 사용자가 직접 실행). 시각은 시스템 시간대(KST) 기준이다.

```cron
10 17 * * * /bin/sh /Users/iaan/.superset/worktrees/PyeonIt/feat/crawler/crawler/scripts/daily-backfill.sh >> /Users/iaan/.superset/worktrees/PyeonIt/feat/crawler/crawler/artifacts/logs/cron.log 2>&1
```

- **잠자기·꺼짐**: macOS cron은 놓친 회차를 따라잡지 않는다. 17:10에 자고 있었다면 그날은 건너뛴다. 그래서 launchd를 권장한다.
- 최신 macOS에서 cron이 다른 앱 폴더에 접근하려면 `/usr/sbin/cron`에 전체 디스크 접근 권한이 필요할 수 있다.
- 해제: `crontab -e`에서 해당 줄을 지운다. 확인은 `crontab -l`.

## 로그

- 실행별: `crawler/artifacts/logs/backfill-YYYYMMDD.log`(래퍼가 남긴다. 시작·종료 UTC 시각, 분류표, 학교별 상태, 다음 권장 시각)
- launchd·cron 자체 출력: `crawler/artifacts/logs/launchd.*.log`, `cron.log`
- 결과: `crawler/artifacts/t6-chain/summary.json`, 학교별 JSON의 `model_attempts`, 예산: `crawler/artifacts/quota-state.json`
- `artifacts/`는 gitignore 대상이라 커밋되지 않는다.

## 비밀 값(.env)

- API 키는 plist·crontab·스크립트 인자·환경변수에 **넣지 않는다**. `pyeonit-crawler`가 워크스페이스 루트(`.git`이 있는 폴더)의 `.env`에서 `GEMINI_API_KEY`를 읽는다. launchd와 cron은 셸 프로필을 읽지 않으므로 이 방식이 가장 단순하다.
- `.env`는 `chmod 600`, gitignore 대상이며 **무료 키만** 둔다. 유료 키를 넣지 않는다.
- 로그·JSON에는 키, 인증 헤더, 오류 본문이 남지 않는다. 429에서는 허용된 쿼터 필드만 저장한다.
