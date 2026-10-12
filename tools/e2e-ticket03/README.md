# Ticket 03: 실제 전면 과목에만 정기 조회

Ticket 03은 Ticket 01의 임시 loopback 서버와 Ticket 02의 실제 debug 앱, 읽기 전용 외부 observer 및 준비 기기를 재사용합니다. 실제 한글·수학·영어 앱의 전면 전환을 이용해 등록한 아이의 전면 미달성 과목만 1분 간격으로 조회하는지 확인합니다. 서버 응답은 기존 제품 HTTP·엔진 경로에 전달되며 실제 TodoSchool API는 호출하지 않습니다.

## 기기와 observer 준비

먼저 Ticket 02에서 준비한 기기와 준비 manifest를 그대로 사용합니다. 접근성 서비스를 다시 연결하거나 제품 debug APK를 재설치하지 않습니다. Ticket 03는 observer 코드만 갱신하므로 아래 명령은 외부 observer와 테스트 APK만 빌드·설치하고, 제품 APK 해시와 실제 접근성 서비스 PID·lease가 유지되는지 확인합니다.

```powershell
.\tools\e2e-ticket03\Run-Ticket03.ps1 -RefreshObserver
```

기존 설치본과 APK 해시가 맞으면 다음 명령으로 실행합니다. 인자 없이 실행해도 `-RunPrepared`와 같습니다.

```powershell
.\tools\e2e-ticket03\Run-Ticket03.ps1 -RunPrepared
```

설정은 `tools/e2e-ticket01/config.json` 또는 `config.example.json`의 `deviceSerial`, Java 21, Python 실행 경로를 사용합니다. API 33 태블릿과 다음 앱 패키지가 설치되어 있어야 합니다.

- `com.enuma.todohangeul`
- `com.enuma.todomath`
- `com.enuma.todoenglish`

접근성 서비스, 알림, Android 13 이상 알림 런타임 권한은 사용자가 직접 설정해야 합니다. 앱 채널은 LOW 또는 MIN 중요도에 진동 OFF의 무음 구성으로 유지합니다. 테스트는 채널 설정이나 권한을 바꾸지 않으며 소리와 heads-up 전달을 검증하지 않습니다. 화면은 켜져 있고 잠금 해제되어 있어야 합니다. 테스트는 앱 UI로 이전 테스트 아이 등록/알림을 정리한 뒤 같은 fake 아이를 등록·해제합니다.

장시간 실행 중 화면 꺼짐을 막기 위해 외부 observer 앱만 제한 시간 15분의 `SCREEN_BRIGHT_WAKE_LOCK`을 획득하고 테스트 종료 시 해제합니다. 화면이 켜져 있고 잠금 해제된 상태인지 먼저 확인하며, 기기 절전 설정·잠금·제품 APK는 변경하지 않습니다. `TICKET03_STAY_AWAKE` 기록에는 획득 전후 foreground package/event cursor와 실제 서비스 lease를 남기며, 활성 package가 바뀌면 테스트를 실패합니다.

prepared manifest가 없거나 제품 APK 해시가 바뀌었다면 여기서 준비를 강행하지 않습니다. 제품 설치는 접근성 서비스를 끊을 수 있으므로 Ticket 02의 사람 주도 준비/서비스 재연결 절차를 먼저 마쳐야 합니다.

## 시나리오와 판정

테스트는 fake 계정 `ticket01@example.test`와 아이 `테스트 아이`를 사용하고 앱 UI에서 공통 간격을 1분으로 설정합니다. 이전 실행 기록은 등록 직전에 초기화하고, 등록 직후 실행되는 한글·수학·영어 전체 기준점 조회 7건을 로그에 보존해 그 완료와 이후 정기 주기를 구분합니다. 기준점에서 세 과목 모두 목표 미달 상태를 확인한 뒤 측정은 가짜 시계 없이 실제 elapsed time으로 진행합니다.

관찰 순서는 실제 한글 앱(2건), 수학 앱(2건), 영어 앱(2건), 한글 앱(목표 6건)입니다. 각 정기 cycle은 로그인, 세 catalog, 현재 전면 과목 report로 분리해 확인하고, report는 현재 실제 전면 패키지·debug provider의 전면 이벤트·해당 아이·과목 상태와 일치해야 합니다. 전면 과목이 아닌 report가 섞이면 실패합니다. 한글이 목표에 도달하면 한글 전면 상태에서 65초 동안 추가 요청이 없어야 하며, 미달성 수학은 계속 조회됩니다. Home 화면과 Android Settings에서는 각각 65초 동안 새 정기 요청이 없어야 합니다. 마지막으로 앱 UI에서 아이 감시를 해제한 뒤 실제 영어 앱을 전면에 두고 65초 동안 새 요청이 없는지 확인합니다. 진행 중 요청은 fixture의 `inFlightRequests`와 서버 sequence를 함께 검사해 새 요청으로 오인하지 않습니다.

서울 날짜가 바뀌거나 실제 서비스 lease가 끊기거나 대상 앱을 전면으로 가져오지 못하면 시나리오는 실패합니다. 테스트는 시간이나 전면 상태를 엔진에 주입하지 않으며 Android 접근성 연결을 강제로 중지·재시작하지 않습니다. 알림 실험은 무음 LOW 구성에 한정하며 실제 경보 전달을 검사했다고 보고하지 않습니다.

## 검증과 산출물

Android logcat의 단일 항목 크기 제한 때문에 structured result는 run ID, 순번, 전체 chunk 수와 Base64 payload를 가진 여러 항목으로 저장하고 runner가 누락·중복·혼합 run을 거부하며 재조립합니다. 긴 UTF-8 결과 조립기는 timed E2E 전에 다음 focused 검사로 확인할 수 있습니다.

```powershell
.\tools\e2e-ticket03\Test-ResultChunks.ps1
```

저장소 루트에서 전체 검증은 다음 명령으로 실행합니다.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:testE2eUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleE2e :app:assembleRelease :app:lintDebug :app:lintRelease :e2eobserver:assembleDebug :e2eobserver:assembleDebugAndroidTest
```

실행 결과는 `build/e2e-ticket03/<run-id>/` 아래에 저장됩니다. `ticket03-result.json`은 실제 foreground event sequence, 주기 요청, 초기 전체 기준점, 관찰 창, 사용한 기기 API·설정 간격, screen wake lock 근거 및 무음 한계를 담습니다. `observer-result-logcat.txt`에는 wake lock 획득·해제와 foreground cursor/package 기록이 포함됩니다. `server-requests.json`과 `server-requests-final.json`에는 fixture 요청과 in-flight 상태가 기록됩니다. `observer-lifecycle-samples.json` 및 product service 전후 dump는 observer 실행 내내 실제 제품 PID와 접근성 연결이 유지되었는지 보여 줍니다.
