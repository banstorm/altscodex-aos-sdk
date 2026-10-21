# AltsCodex SDK sample

**English**
1. Add `altscodex.clientId=<client_id>` and `altscodex.redirectUri=com.altscodex.sample:/callback` to the root `local.properties` (never commit it). The redirect URI must match the developer center registration exactly.
2. Run `./gradlew :sample:installDebug`, then open "AltsCodex Sample". Without a client_id the app only shows a setup hint.
3. Check: Sign in, then tap Session (returns the session without a refresh), force-stop and reopen (the stored session is restored), Sign out, then Sign in again (the browser shows the AltsCodex "Continue" screen, which is expected).

**한국어**
1. 루트 `local.properties` 에 `altscodex.clientId=<client_id>`, `altscodex.redirectUri=com.altscodex.sample:/callback` 를 넣습니다(커밋 금지). redirect URI 는 개발자센터 등록값과 글자 그대로 같아야 합니다.
2. `./gradlew :sample:installDebug` 후 "AltsCodex Sample" 을 엽니다. client_id 가 없으면 설정 안내만 표시합니다.
3. 확인 순서는 로그인 → Session 버튼(refresh 없이 반환) → 앱 강제 종료 후 재실행(저장 세션 복원) → Sign out → 재로그인(브라우저 Continue 화면이 정상)입니다.
