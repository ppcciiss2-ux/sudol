# 서비스 시작 시 앱 크래시 — 진행 상황 노트

작성일: 2026-08-14

## 증상

- 앱에서 예약 조건 설정 후 "시작" 버튼을 누르면(`ReservationService` 포그라운드 서비스 시작),
  실제 폰에서는 **바로 앱이 꺼짐(강제 종료)**.
- 이후 앱을 다시 켜도 정상 진입이 안 되다가, **앱 캐시를 지우면 다시 들어가짐**.
  → 크래시 자체보다도 뭔가 **영구 저장 상태가 깨지는** 느낌 (아래 "의심 지점" 참고).

## 이미 확인한 것 (문제 없음)

- **코레일 백엔드 로그인/조회는 정상.** `korail_test.py` (Python, `korail_mobile_api` 패키지, `korail_credentials.txt` 자격증명 사용)로
  실제 계정 로그인 성공 확인 (member_no / member_card_no / customer_no 정상 반환),
  서울→부산 열차 검색도 10건 정상 조회됨.
  → 코레일 계정/비밀번호, 리버스엔지니어링된 API 로직 자체는 원인이 아님.
  → 문제는 **안드로이드 앱 쪽**(`app/src/main/java/com/korailmacro/app/`)에 있음.

## 코드 리뷰로 찾은 의심 지점 (확정 아님 — 실제 logcat으로 검증 필요)

1. **`ReservationService.kt` → `startLoop()`**
   - `val api = KorailApi()` 와 `val notifier = TelegramNotifier(...)` 생성 코드가
     `api.login(...)`을 감싸는 try/catch **바깥/사이**에 있음.
   - 이 코루틴은 `CoroutineScope(Dispatchers.IO).launch { ... }`로 시작되는데
     `CoroutineExceptionHandler`가 없어서, 저 생성자들에서 뭔가 예외가 터지면
     **앱 프로세스 전체가 죽음** (uncaught exception → crash).
   - 지금까지 코드만 봐서는 이 생성자들 자체가 예외를 던질 이유는 못 찾았지만,
     방어적으로 try/catch로 감싸는 게 안전함.

2. **`Prefs.kt` — `EncryptedSharedPreferences` (androidx.security-crypto 1.1.0-alpha06)**
   - 이 라이브러리 버전은 일부 기기/OS 버전 조합에서 Android Keystore 관련 크래시 버그가
     알려져 있음. "캐시 지우면 복구된다"는 증상이 여기(암호화 키/저장 파일 손상)와 잘 맞음.
   - alpha 버전이라 안정적인 정식 릴리즈로 교체를 고려해볼 만함.

3. **매니페스트/포그라운드 서비스 타입**
   - `AndroidManifest.xml`에 `android:foregroundServiceType="dataSync"` 선언은 되어 있고
     `FOREGROUND_SERVICE_DATA_SYNC` 권한도 있음 (Android 14 기준 정상으로 보임).
   - 다만 실제 폰이 Android 14보다 높은 버전(15 등)이거나 특정 제조사(OEM) 배터리 최적화가
     심한 기기(예: 샤오미/MIUI 등)라면, **OS/제조사 차원에서 강제 종료**하는 것일 수도 있음
     — 이 경우는 코드 버그가 아니라 "배터리 최적화 제외" 설정 문제일 가능성.

## 재현/디버깅 계획 (다음에 이어서 할 것)

가장 확실한 방법은 **logcat으로 실제 크래시 스택트레이스를 보는 것.** 아직 못 봄.

### 방법 A: 실제 폰 + adb (제일 빠름, 추천)

1. 폰 설정 → 휴대전화 정보 → 빌드번호 7번 연타 → 개발자 옵션 활성화
2. 개발자 옵션 → **USB 디버깅** 켜기
3. USB로 PC 연결 → "USB 디버깅 허용?" 팝업 허용
4. PC에서 adb 확인: `adb devices` (연결된 기기 나오면 OK)
5. 로그 캡처 시작: `adb logcat -c && adb logcat *:E AndroidRuntime:E`
   (또는 그냥 `adb logcat > crash.log` 로 전체 저장해도 됨)
6. 그 상태에서 폰에서 앱 켜고 → 설정 입력 → "시작" 버튼 클릭 → 크래시 재현
7. logcat에 뜨는 `FATAL EXCEPTION` 스택트레이스를 보고 원인 확정

### 방법 B: 로컬 에뮬레이터 (지난 세션에 시도하다 중단함)

집 PC에 Android SDK가 있다면:

1. `sdkmanager.bat "emulator" "system-images;android-34;google_apis;x86_64" "platform-tools"` 설치
   (원래 PC 기준 `build-tools;34.0.0`, `platforms;android-34`는 이미 있었음, 라이선스도 동의 완료 상태였음.
    집 PC는 처음부터 다시 설치해야 할 수 있음.)
2. AVD 생성: `avdmanager create avd -n korail_test -k "system-images;android-34;google_apis;x86_64"`
3. 실행: `emulator -avd korail_test`
4. 이미 빌드되어 있는 `korail-macro-3.apk`를 그대로 설치 (재빌드 불필요):
   `adb install korail-macro-3.apk`
5. `adb logcat -c && adb logcat *:E AndroidRuntime:E` 캡처 시작
6. 에뮬레이터 안에서 앱 실행 → 설정 입력 → 시작 버튼 → 크래시 재현 및 로그 확인

> 단, OEM 배터리 최적화/자동 실행 제한 같은 건 순정 구글 에뮬레이터에서는 재현 안 될 수 있음.
> 그런 경우는 방법 A(실제 폰)로만 확인 가능.

## 크래시 원인 확정되면

로그의 `FATAL EXCEPTION` 블록(예외 타입 + 스택트레이스)을 그대로 붙여넣어 주면
바로 어느 파일/어느 줄이 문제인지 짚어서 고칠 수 있음.
