# 2026학년도 수도권 일반대학 초기 수집 결과

실행일: 2026-11-08. Python 3.15.2 / Playwright Chromium / Gemini REST `gemini-3.1-flash-lite`. DB 저장 없음.

사용자의 최종 범위는 서울·경기·인천의 일반 종합대학이다. 전문대·원격대·교육대·신학/종교·예술/체육 특화 대학은 제외했다. 통합 모집요강은 학교당 한 번 수집하고, 한양대 서울/ERICA처럼 별도 요강을 제공하는 캠퍼스는 구분했다. 통합 PDF에 들어 있는 타지역 캠퍼스 행은 원문 검증을 위해 보존하므로, JSON 전체를 수도권 모집인원 합계로 곧바로 집계하면 안 된다.

최종 YAML: **60개 수집 항목(59개 대학)**. 통과 11개, 불일치 25개, 검증불가 17개, 요강없음 4개, 실패 3개. 이와 별도로 홍익대 서울은 공식 사이트 봇 차단으로 출처 검증에 실패해 YAML 끝 주석에 기록했다.

## 실행 및 재현

```sh
cd crawler
.venv/bin/python -m pyeonit_crawler backfill --year 2026 --concurrency 2 --resume
.venv/bin/python -m pyeonit_crawler backfill --year 2026 --schools 가천대학교 --concurrency 1
.venv/bin/python -m pyeonit_crawler backfill --year 2026 --schools 차의과학대학교 --concurrency 1
```

전체 실행 종료 코드 **1**, 가천대 개별 확인 **1**, 차의과학대 개별 확인 **0**. 전체 항목의 처리가 완료됐으며, 불일치·검증불가·요강없음·실패가 포함되어 비정상 종료 코드를 반환한 것이다. 초회 전체 수집 도중 범위가 일반대학으로 좁혀졌고, 의왕 캠퍼스를 보완한 최종 60개 목록으로 전체 재실행했다. 이후 가천대 인증서 예외와 차의과학대 학교명 공백 정규화를 개별 재확인했으며, `summary.json`은 전체 실행 상태에 이 두 학교의 마지막 상태를 합쳐 보존했다. `--schools` 단독 실행은 summary를 선택한 학교로 교체하므로 운영 시 전체 summary를 별도로 보존해야 한다. `--resume`은 출처 목록·캠퍼스·지역·학년도·모델·원본 PDF 해시와 재계산 검증 결과가 일치하는 기존 JSON만 재사용한다. 미통과 판정도 그대로 유지하며 통과로 바꾸지 않는다. 새 폴더에서 전부 재추출하려면 `--resume`을 빼거나 `--output artifacts/fresh`를 지정한다.

최종 실행에서 기존 결과 42개를 재사용했다. 초기 동시 요청 6개 실행에서 API 429가 발생했고, 응답의 분당 입력 토큰 제한(250,000)과 `RetryInfo` 55초를 확인했다. 단기 제한이 명시된 429만 최대 3회 전송 재시도하며 서버 대기 시간(최대 60초)을 따른다. 일일 제한·긴 대기·불명확한 429는 즉시 실패로 남긴다.

## 학교별 최종 상태

상태 의미: 통과=추출한 합계·소계 모두 일치, 불일치=숫자 합계 불일치, 검증불가=기재 총계 없음·비숫자/누락 셀 등. **요강없음은 지정된 출처에서 현재 크롤러가 유효 PDF를 찾지 못했다는 뜻이며 실제 미모집을 의미하지 않는다.** 실패는 접속·사이트 스크립트·API·학교/학년도 또는 응답 형식 오류 등이다. JavaScript 전용 첨부나 목록 페이지 밖의 과거 공지는 추가 대응이 필요할 수 있다.

| 학교 / 캠퍼스 | 지역 | 상태 | 모집인원 행 | 추출 시도 | 근거 / 후속 확인 |
| --- | --- | --- | ---: | ---: | --- |
| 서강대학교 | SEOUL | 통과 | 26 | 1 | 합계·소계 일치 · [공식 출처](https://admission.sogang.ac.kr/enter/html/transfer/guide.asp) |
| 성균관대학교 | SEOUL | 불일치 | 45 | 3 | 총계 학사 편입학: 56/73; 재추출 중 HTTPStatusError · [공식 출처](https://admission.skku.edu/admission/html/transfer/guide.html) |
| 연세대학교 (서울) | SEOUL | 검증불가 | 74 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://admission.yonsei.ac.kr/seoul/admission/html/transfer/guide.asp) |
| 가천대학교 | GYEONGGI | 실패 | — | — | 추출 IDENTITY_MISMATCH · [공식 출처](https://admission.gachon.ac.kr/admission/html/transfer/guide1.asp) |
| 가톨릭대학교 | GYEONGGI | 불일치 | 36 | 3 | 총계 일반 편입학: 473/77 · [공식 출처](https://ipsi.catholic.ac.kr/submenu.do?menuurl=lktmA6TpgpjUpNE2tkO4bw%3d%3d&) |
| 강남대학교 | GYEONGGI | 통과 | 8 | 1 | 합계·소계 일치 · [공식 출처](https://admission.kangnam.ac.kr/jeonhyung/yogang.htm?ctg_cd=transfer) |
| 강서대학교 | SEOUL | 요강없음 | — | — | 고정 요강/공지에서 지원 방식의 PDF 미발견 · [공식 출처](https://entrance.gangseo.ac.kr/kcui/homeService?menuCode=MC0500) |
| 건국대학교 (서울) | SEOUL | 불일치 | 51 | 3 | 총계 일반편입: 431/429; 재추출 중 HTTPStatusError · [공식 출처](https://admission.konkuk.ac.kr/admission/37889/subview.do) |
| 경기대학교 | GYEONGGI | 검증불가 | 56 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://enter.kyonggi.ac.kr/cms/FR_CON/index.do?MENU_ID=160) |
| 경동대학교 (메트로폴) | GYEONGGI | 검증불가 | 29 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://www.kduniv.ac.kr/iphak/CMS/Contents/Contents.do?mCode=MN036) |
| 경희대학교 | SEOUL | 통과 | 76 | 1 | 합계·소계 일치 · [공식 출처](https://iphak.khu.ac.kr/submenu.do?menuurl=pNIJTWxI%2b8B8m%2bUBBAchHA%3d%3d&) |
| 고려대학교 (서울) | SEOUL | 검증불가 | 59 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://oku.korea.ac.kr/oku/cms/FR_CON/index.do?MENU_ID=660) |
| 광운대학교 | SEOUL | 불일치 | 41 | 3 | 총계 일반: 119/39; 총계 특성화고졸업자: 3/2; 총계 학사: 59/33 외 5건 · [공식 출처](https://iphak.kw.ac.kr/mojib/mojib.php?m_type=PYEONIP) |
| 국민대학교 | SEOUL | 검증불가 | 61 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://admission.kookmin.ac.kr/transfer/application.php) |
| 단국대학교 (죽전) | GYEONGGI | 불일치 | 47 | 3 | 총계 일반편입: 337/348; 총계 학사편입: 43/45 · [공식 출처](https://ipsi.dankook.ac.kr/jukjeon/doumi/mojip.html?bbsid=juk_paper&ctg_cd=05&kw=03952E) |
| 대진대학교 | GYEONGGI | 불일치 | 35 | 3 | 총계 일반편입(정원내): 186/184; 재추출 중 HTTPStatusError · [공식 출처](https://admission.daejin.ac.kr/submenu.do?menuurl=iR4AzM40LcXsoQSN2OSCIg%3d%3d&) |
| 덕성여자대학교 | SEOUL | 통과 | 38 | 1 | 합계·소계 일치 · [공식 출처](https://enter.duksung.ac.kr/mojib/?m_type=PYEONIP) |
| 동국대학교 | SEOUL | 불일치 | 39 | 3 | 총계 일반편입(일반): 49/41 · [공식 출처](https://ipsi.dongguk.edu/admission/html/transfer/guide.asp) |
| 동덕여자대학교 | SEOUL | 검증불가 | 41 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://ipsi.dongduk.ac.kr/ipsi/contents/transfer-viewer.do?gotoMenuNo=transfer-viewer) |
| 동양대학교 (동두천) | GYEONGGI | 요강없음 | — | — | 고정 요강/공지에서 지원 방식의 PDF 미발견 · [공식 출처](https://ipsi.dyu.ac.kr/information/information_01/) |
| 명지대학교 | SEOUL | 검증불가 | 47 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://iphak.mju.ac.kr/pages/?p=26&mj=04) |
| 삼육대학교 | SEOUL | 통과 | 24 | 1 | 합계·소계 일치 · [공식 출처](https://ipsi.syu.ac.kr/2016_syu/pages/index.asp?p=20&mj=04) |
| 상명대학교 (서울) | SEOUL | 불일치 | 32 | 3 | 총계 일반편입: 196/164; 총계 학사편입: 38/26 · [공식 출처](https://admission.smu.ac.kr/_seoul/iphak/mojip.html?bbsid=seoul_mojib&ctg_cd=pyunip) |
| 서경대학교 | SEOUL | 불일치 | 25 | 3 | 총계 농어촌학생편입: 6/7; 총계 일반편입: 119/110; 총계 학사편입: 20/26; 재추출 중 HTTPStatusError · [공식 출처](https://go.skuniv.ac.kr/index.php?mid=transfer_admission_guide) |
| 서울과학기술대학교 | SEOUL | 불일치 | 36 | 3 | 총계 일반: 134/132; 총계 평생학습자: 3/5 · [공식 출처](https://admission.seoultech.ac.kr/cms/FR_CON/index.do?MENU_ID=290) |
| 서울대학교 | SEOUL | 검증불가 | 42 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://admission.snu.ac.kr/transfer/transfer/guide) |
| 서울시립대학교 | SEOUL | 통과 | 45 | 1 | 합계·소계 일치 · [공식 출처](https://admission.uos.ac.kr/admissionNew/html/pyunib/info.do?menuid=2002003001000000000) |
| 서울여자대학교 | SEOUL | 통과 | 38 | 1 | 합계·소계 일치 · [공식 출처](https://admission.swu.ac.kr/bbs/board.php?bo_table=flashpaper&sca=%ED%8E%B8%EC%9E%85%ED%95%99&sub_ck=1) |
| 성결대학교 | GYEONGGI | 검증불가 | 28 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://ipsi.sungkyul.ac.kr/10000016) |
| 성공회대학교 | SEOUL | 불일치 | 6 | 3 | 총계 편입학 모집인원 총 계: 0/51 · [공식 출처](https://enter.skhu.ac.kr/enter/3075/subview.do) |
| 성신여자대학교 | SEOUL | 불일치 | 43 | 3 | 총계 기회균형선발: 3/4; 총계 농·어촌학생: 11/10; 총계 일반편입학: 200/142 외 1건 · [공식 출처](https://ipsi.sungshin.ac.kr/guide/recruit.htm?ctg_cd=transfer) |
| 세종대학교 | SEOUL | 불일치 | 42 | 3 | 총계 농어촌학생: 22/18; 총계 특성화고교졸업자: 9/10 · [공식 출처](https://ipsi.sejong.ac.kr/ipsi/transfer/recruitment-guidelines.do) |
| 수원대학교 | GYEONGGI | 불일치 | 50 | 3 | 총계 학사: 43/42; 재추출 중 HTTPStatusError,HTTPStatusError · [공식 출처](https://ipsi.suwon.ac.kr/guide/transfer) |
| 숙명여자대학교 | SEOUL | 통과 | 40 | 1 | 합계·소계 일치 · [공식 출처](https://admission.sookmyung.ac.kr/admission/html/transfer/guide.asp) |
| 숭실대학교 | SEOUL | 검증불가 | 0 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://iphak.ssu.ac.kr/mojip/req.asp?flag=3&page_no=1_4_2) |
| 신한대학교 | GYEONGGI | 검증불가 | 38 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://ipsi.shinhan.ac.kr/adm/info.do?cateId=4&bbsId=BBSMSTR_000000000051&relateImagePath=svis_4) |
| 아주대학교 | GYEONGGI | 검증불가 | 37 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://www.iajou.ac.kr/mojib/?m_type=PYEONIP) |
| 안양대학교 | GYEONGGI | 불일치 | 34 | 3 | 총계 기회균형: 5/6; 총계 농어촌학생: 9/8; 총계 학사편입: 29/22 · [공식 출처](https://enter.anyang.ac.kr/10000022) |
| 용인대학교 | GYEONGGI | 불일치 | 28 | 3 | 총계 기초생활수급자 및 차상위계층: 5/7; 총계 의료인력: 2/0; 총계 일반: 279/280 · [공식 출처](https://ipsi.yongin.ac.kr/pyunip/guide.do) |
| 을지대학교 | GYEONGGI | 실패 | — | — | Error · [공식 출처](https://admission.eulji.ac.kr/?menuno=2735) |
| 이화여자대학교 | SEOUL | 불일치 | 61 | 3 | 총계 학사편입학: 69/61 · [공식 출처](https://admission.ewha.ac.kr/admission/html/transfer/guide.asp) |
| 인천가톨릭대학교 | INCHEON | 검증불가 | 4 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://admission.iccu.ac.kr/admission/php/transfer_a.php) |
| 인천대학교 | INCHEON | 불일치 | 4 | 3 | 총계 기회균형: 0/3; 총계 유치원교사양성: 0/11; 총계 일반: 2/299 외 2건 · [공식 출처](https://admission.inu.ac.kr/submenu.do?menuurl=hB8GYKGYSI7BBlNJFDXa%2bQ%3d%3d&) |
| 인하대학교 | INCHEON | 통과 | 56 | 1 | 합계·소계 일치 · [공식 출처](https://admission.inha.ac.kr/cms/FR_CON/index.do?MENU_ID=620) |
| 중앙대학교 | SEOUL | 요강없음 | — | — | 고정 요강/공지에서 지원 방식의 PDF 미발견 · [공식 출처](https://admission.cau.ac.kr/submenu.do?menuurl=8Dv5j3LJonuTK%2BIWKKbkOQ%3D%3D) |
| 차의과학대학교 | GYEONGGI | 통과 | 1 | 1 | 합계·소계 일치 · [공식 출처](https://admission.cha.ac.kr/%ed%8e%b8%ec%9e%85%ed%95%99/%eb%aa%a8%ec%a7%91%ec%9a%94%ea%b0%95/) |
| 청운대학교 (인천) | INCHEON | 불일치 | 31 | 3 | 총계 일반전형(학생부): 748/565 · [공식 출처](https://enter.chungwoon.ac.kr/enter/contents.do?key=112) |
| 평택대학교 | GYEONGGI | 불일치 | 26 | 3 | 총계 기회균형: 3/4 · [공식 출처](https://entrance.ptu.ac.kr/entrance/3665/subview.do) |
| 한경국립대학교 | GYEONGGI | 불일치 | 46 | 3 | 총계 농어촌학생: 6/1; 총계 일반전형: 199/157; 총계 재외국민: 2/1 외 4건 · [공식 출처](https://ipsi.hknu.ac.kr/mojib/mojib.php?m_type=PYEONIP) |
| 한국공학대학교 | GYEONGGI | 요강없음 | — | — | 고정 요강/공지에서 지원 방식의 PDF 미발견 · [공식 출처](https://iphak.tukorea.ac.kr/transfer/guide.htm) |
| 한국외국어대학교 | SEOUL | 불일치 | 65 | 3 | 총계 학사편입학: 99/67 · [공식 출처](https://adms.hufs.ac.kr/cms/FrCon/index.do?MENU_ID=230) |
| 한국항공대학교 | GYEONGGI | 검증불가 | 12 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://ibhak.kau.ac.kr/admission/html/transfer/guide.asp) |
| 한성대학교 | SEOUL | 불일치 | 23 | 3 | 총계 농어촌편입: 4/3; 총계 일반편입: 165/163; 총계 특성화고교졸편입: 3/4 · [공식 출처](https://enter.hansung.ac.kr/?m1=menu03%25&m2=sub00%25&board=application%25&application=8%25) |
| 한세대학교 | GYEONGGI | 통과 | 19 | 1 | 합계·소계 일치 · [공식 출처](https://ipsi.hansei.ac.kr/view.do?no=1083) |
| 한신대학교 | GYEONGGI | 검증불가 | 29 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://ent.hs.ac.kr/ipsi/pages/?p=12&mj=03) |
| 한양대학교 (서울) | SEOUL | 검증불가 | 49 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://go.hanyang.ac.kr/web/mojib/mojib.do?m_type=PYEONIP) |
| 한양대학교 (ERICA) | GYEONGGI | 검증불가 | 27 | 1 | 기재 총계 없음 또는 비숫자·누락 셀 보존 · [공식 출처](https://goerica.hanyang.ac.kr/admission/html/transfer/guide.asp) |
| 협성대학교 | GYEONGGI | 불일치 | 28 | 3 | 총계 기초생활수급자/차상위계층/한부모가족: 3/1; 총계 특성화고졸업자: 1/3; 재추출 중 HTTPStatusError · [공식 출처](https://iphak.uhs.ac.kr/pyuniphak/mojip.do) |
| 화성의과학대학교 | GYEONGGI | 실패 | — | — | PAGE_SCRIPT_CONFLICT · [공식 출처](https://www.hsmu.ac.kr/admission/contents/AMS30100000.do) |
| 국립한국교통대학교 (의왕) | GYEONGGI | 불일치 | 48 | 3 | 총계 학사편입(주간): 46/37 · [공식 출처](https://www.ut.ac.kr/prog/rcrit/ipsi/sub03_01/view.do?cntNo=36) |
| 홍익대학교 (서울) | SEOUL | 실패 (출처 미확인) | — | — | 입학처 → STCLab BotManager 차단. [탐색 주소](https://admission.hongik.ac.kr/) |

접속/원문 제한: 가천대는 인증서 예외 후 PDF 다운로드가 가능해졌으나 현재 고정 페이지에 2027학년도 예정 자료가 게시되어 2026 요청과 불일치했다. 을지대 PDF는 Playwright API 요청에서 `Parse Error: Invalid header token`이 발생했다. 화성의과학대는 `this._engines.set is not a function` 사이트 스크립트 충돌로 링크 평가가 실패했다. 이들 오류를 검증 통과로 대체하지 않았다.

## 성균관대 73명 재확인

원본 PDF SHA-256: `91e5561ee528a519eecf9f2b849238cfeb7202c534a5e0c2b10b68352296fd96`. 이전 보고와 동일하다. 사전 점검에서 같은 PDF를 최초 추출 + 2회 재추출했으나 **세 번 모두 학사 56명 / 기재 총계 73명**으로 불일치했다. 프로토타입의 73명 일치를 이번 실행에서는 재현하지 못했으므로 비결정성만으로 원인을 단정하거나 인원을 보정하지 않았다.

| 실행 | 시도 | 학사 합 | 기재 총계 | 결과 |
| --- | ---: | ---: | ---: | --- |
| 사전 점검 | 1 | 56 | 73 | 불일치 |
| 사전 점검 | 2 | 56 | 73 | 불일치 |
| 사전 점검 | 3 | 56 | 73 | 불일치 |
| 전체 수집(최종 재사용) | 1 | 56 | 73 | 불일치 |
| 전체 수집(최종 재사용) | 2 | 56 | 73 | 불일치 |
| 전체 수집(최종 재사용) | 3 | — | — | HTTPStatusError (API 오류) |

## 재추출·보존 정책

- 합계 검증 실패 시 같은 PDF 바이트로 최대 2회 재추출한다(최초 포함 3회). 최초 통과 결과를 즉시 채택한다. 중간에 검증불가가 나와도 남은 재시도에서 통과를 확인한다. 최초 결과가 검증불가면 불필요한 재추출은 하지 않는다.
- 통과가 없으면 최초의 유효한 추출 결과를 유지한다. 후속 API 오류·학교/학년도 오류가 기존 불일치 데이터를 덮어쓰지 않는다. 모든 시도의 추출 데이터/검증 또는 오류 종류를 `extraction_attempts`에 저장한다.
- PDF.js 뷰어의 확장자 없는 주소, 학년도별 PDF 우선순위, 동적 프레임 교체와 JS 전용 PDF 다운로드 버튼을 지원한다. TLS 예외 허용 목록은 `admission.sogang.ac.kr`에 더해 가천대 공식 PDF 서버의 인증서 체인 실패를 확인한 `admission.gachon.ac.kr`만 이유 주석과 함께 추가했다. 전체 TLS 비활성화나 외부 리다이렉트로의 예외 전파는 없다.
- 공지 게시판과 고정 요강 메뉴 중 확인된 종류만 YAML에 넣었다. 미확인 메뉴 종류는 YAML 끝 주석에 기록했다.

## 산출물

`crawler/artifacts/summary.json`에는 최종 60개 항목의 상태가 들어 있다. 학교별 PDF와 JSON도 같은 폴더에 저장했다. JSON에는 v2 스키마, 원본 출처·SHA-256·수집 시각·모델·학년도, 추출 데이터, 검증 결과와 재추출 이력이 포함된다. 추출 자체가 실패한 학교는 학교별 JSON 대신 summary 오류와 수집된 PDF(있는 경우)로 확인한다. 모든 artifacts와 로컬 환경·키는 Git 제외 대상이며 PR에 포함하지 않았다. 범위 변경 전 신학/예술계 산출물과 진단 로그가 같은 로컬 폴더에 남아 있으므로 최종 `summary.json`에 나열된 항목만 이번 결과로 취급한다.

| 학교 | JSON (artifacts/ 기준) | 원본 SHA-256 |
| --- | --- | --- |
| 서강대학교 | `sogang-2026.json` | `48ce417d96eba8f67bc96b12524416b2db184d0e2d6c51187229fe2b28d724af` |
| 성균관대학교 | `skku-2026.json` | `91e5561ee528a519eecf9f2b849238cfeb7202c534a5e0c2b10b68352296fd96` |
| 연세대학교 (서울) | `yonsei-2026.json` | `713c65369713fe1b35c94412ebeb02dd28a6abb41e522669699066e408e202ce` |
| 가톨릭대학교 | `가톨릭대학교-2026.json` | `bc91566de7d2c06c4e10dd9207721e7c2b67ca414df1559755d4d474790364ed` |
| 강남대학교 | `강남대학교-2026.json` | `c8de0a782a1109e9af89d28d780ba485507cb8b4a4032ae692b3c3961bc8836e` |
| 건국대학교 (서울) | `건국대학교-서울-2026.json` | `976f49f932f5d1ed780c54947c9cfd65155bc420d14ea756cfb21e8c63f9c12d` |
| 경기대학교 | `경기대학교-2026.json` | `74de277406d666fe014693d3f4779d3fc04cf9e81bd7d3eb654dd275e0e5237f` |
| 경동대학교 (메트로폴) | `경동대학교-메트로폴-2026.json` | `ed92ced55b844c20eb6b1edcb34aff8a90e9088601e3450261333dafc0d1a952` |
| 경희대학교 | `경희대학교-2026.json` | `ea0849638ecd565a56a2486dfa3a2802f548fa6818c58a3f5a150948506f7c57` |
| 고려대학교 (서울) | `고려대학교-서울-2026.json` | `7f5634bd8be0e0274b0306c3dd9acb574901cc890a82e2af5c79c3ec8cf36acd` |
| 광운대학교 | `광운대학교-2026.json` | `4ad6045d4c1a8ab7a4ddbad053ba7bb1c2679b78e3144942575950a70e6cffb9` |
| 국민대학교 | `국민대학교-2026.json` | `366c9731d838215c1841c9d0a27278385c1dc26eff7f9a186efc5c810282db8f` |
| 단국대학교 (죽전) | `단국대학교-죽전-2026.json` | `8583f8a5fe381aa64ec08c1818506c88c8901643c8c9c5e891f561fdd118b9c7` |
| 대진대학교 | `대진대학교-2026.json` | `19d436c09aa13f194d794602db15681182f1153f29fa8861467c61a6f902c297` |
| 덕성여자대학교 | `덕성여자대학교-2026.json` | `ccb5616c2910ae24008df9964d9dae1cc5c4a4e9106e9f8c8f38ce84288b9930` |
| 동국대학교 | `동국대학교-2026.json` | `bee63d238dac9eb7b831c611e8ea7d1eb763f3b41c06e2e55b12c37919da4acf` |
| 동덕여자대학교 | `동덕여자대학교-2026.json` | `e99afca4570269f606298cc6138b34a5cb74fde874fb6f6887981163dedbc3a9` |
| 명지대학교 | `명지대학교-2026.json` | `c101b2e04ccd17a8e31ee6b87ea49c7b2d1d0a0ab7b425a79281895c70f8b144` |
| 삼육대학교 | `삼육대학교-2026.json` | `7efa1d79e82b5dfaec11544de21f0feb82f0cfc65e21978c54437d98145abc55` |
| 상명대학교 (서울) | `상명대학교-서울-2026.json` | `2f5a2921a6c75c555dfb931136e2f2d40314061be129d5eaef0e8e4a6ffe047a` |
| 서경대학교 | `서경대학교-2026.json` | `84569ffc310119118a888c5acf9430d1dae2e5ef866d7f137454e3fd7b1b8e22` |
| 서울과학기술대학교 | `서울과학기술대학교-2026.json` | `2c877ccc018cc58f1ed2e22799fb3994f9a257595f0aff2acee7c5dae7782929` |
| 서울대학교 | `서울대학교-2026.json` | `3d6d58f15b32281513ecd59307e79684dd85ed1291db88090e58d83b02955084` |
| 서울시립대학교 | `서울시립대학교-2026.json` | `26d8953337b60f4ab2013f96fa0f4d6300d19dbf5a1892a9e210ae867ba81dc8` |
| 서울여자대학교 | `서울여자대학교-2026.json` | `a7186b7b0b34590c37f26ce0553637b1c852f7f7fc85121fe09075431c853042` |
| 성결대학교 | `성결대학교-2026.json` | `9915f3a6d2a210fe334c533dfcc0f8044b9bf8d40809639cf6ddebfeb9a9338b` |
| 성공회대학교 | `성공회대학교-2026.json` | `5969dd9548d8f8aa4864c68a00f2a845fbc6e6563c48f1d5a4052d41475e05c2` |
| 성신여자대학교 | `성신여자대학교-2026.json` | `cd7642d434e6b57935c3d6d3fa01655769d185ac412f01ce99a10931573f9ea0` |
| 세종대학교 | `세종대학교-2026.json` | `e8750670630612c0dbb772f4b07ccf1c24ec90150c96deca94f6aafa4a2f02cf` |
| 수원대학교 | `수원대학교-2026.json` | `4a42c0c116cf9a8e7454ea878747fd31c83d686cc0968cfe4e3877ee5f9bf73f` |
| 숙명여자대학교 | `숙명여자대학교-2026.json` | `c6c9e771c1f9f8e996f65b143f5f1bdf2c737d554cf91ea0143715b1552bc391` |
| 숭실대학교 | `숭실대학교-2026.json` | `ef3a5b2519a712477fe4077ab39d5baa3197b3d78d8b06cf17400df4177079d6` |
| 신한대학교 | `신한대학교-2026.json` | `63c7adae5bac60ea8afdc45db4183eb8bda4736250690856a67ffdb70860f23a` |
| 아주대학교 | `아주대학교-2026.json` | `35da2d94c9de6755d7cd2dd553a2fa758ab70f191d7532555bf72e6499bb05e6` |
| 안양대학교 | `안양대학교-2026.json` | `ced649d4d9276759f903f658352a4935950e6912a0c37569a6f2265b725072a0` |
| 용인대학교 | `용인대학교-2026.json` | `470b0e39b8ca7bca8ec80ec81d46258736db94c005d24418f22f6c14bd480278` |
| 이화여자대학교 | `이화여자대학교-2026.json` | `a5a9d443031ed175a97b9bd65632d2e1540741405a5e4391ffc50409489cbb0a` |
| 인천가톨릭대학교 | `인천가톨릭대학교-2026.json` | `69f23b7aea3c6e22eb571771d64ea9e74612d039773fcbad2f86ed24dc3ccb3a` |
| 인천대학교 | `인천대학교-2026.json` | `993de534dde432e52aa083a26bd806516db70db1a6be47c308560a60aeb028ed` |
| 인하대학교 | `인하대학교-2026.json` | `0a4cd5e905763b97bbe3d1ec24f661412d54d4e9736da43ac62d28b07a2d36cd` |
| 차의과학대학교 | `차의과학대학교-2026.json` | `f2a1a5cef3374d0bff06cf8b8cc42c82961989019789c02ecd740bdaa85a27ca` |
| 청운대학교 (인천) | `청운대학교-인천-2026.json` | `99fca04716a6f810b9bb34d42a44c04fa05d789c5f6842d7d1d86841378b5d32` |
| 평택대학교 | `평택대학교-2026.json` | `920bf143bb6a04724b0f1e0371a79ba815632a1e6dfa35d246419152270d7d27` |
| 한경국립대학교 | `한경국립대학교-2026.json` | `f158e8a0f600571760c5929105585f31dfca2ebcb11f99677b23c8a45390b047` |
| 한국외국어대학교 | `한국외국어대학교-2026.json` | `843b2ebe6687a01093852fbe4a6db75a1b8b9715fdcf0150fef3f53a499a92cc` |
| 한국항공대학교 | `한국항공대학교-2026.json` | `64be85db839b2b79fdc2945a91d6ecd586befb4d360ca959e985cc49aa32b511` |
| 한성대학교 | `한성대학교-2026.json` | `900bf5192507b8b90f471a89d655417c22da7e479bf16c5b977b44a0bf17edc4` |
| 한세대학교 | `한세대학교-2026.json` | `3df37afb3df12250cd03fbc14dffa7d0271b9547b5afec4126e859e11b67a5dd` |
| 한신대학교 | `한신대학교-2026.json` | `63f3b8dc78c6172c44afe0233f106a4c49f003c80041c0886ff6ee71bad8f39f` |
| 한양대학교 (서울) | `한양대학교-서울-2026.json` | `ff64ee14eb008ee21dd8671f0cf6972f42def90f2be6d8b5d54bf7ea8477e57b` |
| 한양대학교 (ERICA) | `한양대학교-ERICA-2026.json` | `90c70ad427f4d6462921ec09ea492c51833b85cd7787e105ea9e3b633bcfdbe4` |
| 협성대학교 | `협성대학교-2026.json` | `8b28a4316ac563719f74e73091ef2a02c247761745e7da782eb684effda954a5` |
| 국립한국교통대학교 (의왕) | `국립한국교통대학교-의왕-2026.json` | `fe9aa1a3a21d4a43b2bac6384290aae79a4738d6403be7c05e68084b2eb3591f` |

## 검증

```sh
cd crawler
.venv/bin/pytest -m ''
.venv/bin/ruff check src tests
git diff --check
git check-ignore artifacts/summary.json .venv/bin/python
```

- 실제 대학 다운로드 3건을 포함한 pytest **80개 통과**.
- 최종 summary 60개 항목과 53개 학교 JSON/PDF의 SHA-256·검증 결과 일치를 프로그램으로 재확인했다.
- Ruff, diff 공백 검사 통과. 결과 JSON·가상환경의 ignore 적용 확인.
- 재추출 조기 통과·최대 횟수·검증불가 처리·후속 오류 보존·동일 PDF 사용·JSON 이력 보존, YAML 파싱·잘못된 입력 거부, 출처 fallback, 재사용 무결성, 학교명 공백 정규화(원문은 보존), 429 단기/일일 제한 정책을 테스트했다.
- DB 연결·저장·마이그레이션은 실행하지 않았다. 불일치·검증불가 데이터는 원문 검토 후 다음 작업으로 넘겨야 한다.
