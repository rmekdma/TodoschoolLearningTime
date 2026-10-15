# `tools/` 통합 테스트 지침

이 폴더의 runner는 실제 앱과 별도 관찰 앱을 Android 기기에서 실행하고, TodoSchool 응답은 `127.0.0.1`의 임시 fixture로 제공합니다.

## 실행

전체 옵션은 `Get-Help .\tools\Run-IntegrationTests.ps1 -Full`에서 확인합니다. 필수 인자가 빠진 호출은 사용법만 출력하고 ADB를 실행하지 않습니다. `-Mode build`는 빌드·단위 검사·lint만 수행하며 기기에 연결하지 않습니다.

| `-Scenario` | 검증 기능 |
| --- | --- |
| `screen` | 실제 로그인 UI와 fixture 응답을 통해 과목별 활동 수가 보고서 화면에 표시되는지 확인 |
| `baseline` | 감시 등록 뒤 초기 완료 상태와 이후 완료 알림 갱신 확인 |
| `foreground` | 실제 전경 앱의 과목에 맞춰 조회하고 감시 상태가 갱신되는지 확인 |
| `authentication` | HTTP 실패 재시도와 만료 인증의 화면 재로그인·복구 확인 |
| `persistence` | 자동 재부팅 뒤 저장된 감시와 실제 서비스·조회 복원 확인 |

화면 시나리오는 다음처럼 실행합니다.

```powershell
.\tools\Run-IntegrationTests.ps1 -Scenario screen -Mode run
```

관찰 시나리오는 한 번 `baseline -Mode prepare`로 debug·observer APK를 준비하고 Android 설정에서 알림과 감시 서비스를 허용한 뒤 `-Mode run`으로 실행합니다. observer 코드만 바뀐 경우 `-Mode refresh-observer`로 observer APK만 갱신합니다. `persistence`는 이 baseline 준비물을 재사용합니다.

```powershell
.\tools\Run-IntegrationTests.ps1 -Scenario baseline -Mode prepare
.\tools\Run-IntegrationTests.ps1 -Scenario baseline -Mode run
.\tools\Run-IntegrationTests.ps1 -Scenario persistence -Mode run
```

## 경계와 변경 위치

- 모든 fixture 요청과 `adb reverse`는 `127.0.0.1`만 사용합니다. fixture 오류를 실제 TodoSchool API로 우회하지 않으며 release 앱은 설치하거나 실행하지 않습니다.
- `screen`과 `prepare`는 debug APK를 설치 또는 갱신하므로 기존 observer 세션과 분리된 테스트 기기에서 사용합니다. 준비된 실행은 설치된 product APK의 동일성을 확인합니다.
- 알림(POST_NOTIFICATIONS)·접근성 허용은 최초 기기 준비 조건입니다. 자동 관찰을 실행하기 전 사용자가 `학습 상태` 알림 채널을 켜고 중요도를 MIN 또는 LOW, 진동을 끔으로 설정해야 합니다. Android 채널 중요도에서 LOW 이하는 소리가 나지 않으며, observer는 OS가 보고하는 채널 활성 상태·중요도·진동을 확인하고 준비되지 않았으면 시나리오를 거부합니다. 채널의 소리 URI가 남아 있는 것만으로는 준비 실패가 아닙니다. 이 테스트 준비는 제품의 기본 HIGH 알림 정책과 별개이며 제품 기본값을 낮추지 않습니다. 테스트 중 권한 OFF/ON이나 수동 카드 제거를 요구하는 시나리오는 제공하지 않습니다. `persistence -Mode run`은 기기를 재부팅하며 잠금 해제가 필요하면 중단합니다.
- fixture 초기화는 현재 실행의 HTTP fixture 상태만 초기화합니다. Android 앱 데이터와 사용자 데이터는 초기화하지 않습니다.
- Kotlin 계측 테스트는 Android 화면·서비스의 동작을 단언합니다. PowerShell은 빌드, 기기 단계, 실행별 결과 검증을 담당합니다. Python `server.py`는 응답 fixture이며 `test_server.py`는 그 HTTP 동작을 검증합니다. 각 검증은 해당 계층에 둡니다.
- runner와 공통 PowerShell 모듈은 `tools/` 바로 아래에 둡니다. 현재 다섯 시나리오가 쓰는 HTTP 계약은 `server.py`, Kotlin, 실행기에서 함께 갱신하고 실제 localhost 요청·응답으로 검증합니다. 기능 이름은 실행 선택자이고 활성 fixture wire ID는 내부 값입니다.
- Windows PowerShell 5.1에서 한글이 포함된 `.ps1`을 수정할 때 UTF-8 BOM을 유지합니다. ADB 로그는 현재 실행의 run ID와 필요한 tag로 필터링해 필요한 끝부분만 기록합니다.
