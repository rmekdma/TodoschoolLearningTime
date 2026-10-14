# Ticket 02: 무음 기준점과 완료 시 기존 알림 갱신

Ticket 02는 Ticket 01의 임시 loopback 서버, 실제 debug 앱, 외부 observer, Gradle 및 APK 검사를 재사용합니다. 별도 가짜 엔진이나 임대 서비스, 알림 게시 대역은 사용하지 않습니다. fixture API 응답으로 실제 앱의 로그인·조회·엔진·접근성 서비스·Android `NotificationManager` 경로를 확인하며 TodoSchool 실제 API는 호출하지 않습니다.

## 첫 실행 순서

먼저 `tools/e2e-ticket01/config.example.json`을 `config.json`으로 복사하고, 필요하면 `deviceSerial`을 지정합니다. 저장소 루트에서 아래 순서로 실행합니다.

```powershell
Copy-Item tools/e2e-ticket01/config.example.json tools/e2e-ticket01/config.json
# 필요한 경우 config.json의 deviceSerial을 설정합니다.
.\tools\e2e-ticket02\Run-Ticket02.ps1 -PrepareDevice
```

`-PrepareDevice`는 필요한 debug, instrumentation, release 및 외부 observer APK를 빌드하고 debug 앱·observer·observer 테스트를 기기에 설치한 뒤 준비 manifest를 기록합니다. debug/release lint도 실행하지만 전체 unit test suite는 실행하지 않습니다. 이 단계에서는 임시 서버를 시작하거나 시나리오를 실행하지 않습니다.

설치가 끝난 뒤 기기에서 직접 다음을 준비합니다. 앱 레이블은 `토도 학습시간 · 로컬 테스트`, 접근성 서비스 레이블은 `토도 학습 감시 · 로컬 테스트`입니다.

- 앱 알림과 Android 13 이상 `POST_NOTIFICATIONS` 권한을 허용합니다.
- `학습 상태` 채널을 무음으로 설정하고 진동을 끕니다. 중요도가 낮음/최소이면 Android가 사운드 URI를 보존해도 소리와 heads-up을 억제하므로 실행할 수 있습니다. 기본 중요도에서는 채널 사운드 URI가 없어야 하며, 높음과 차단 상태는 거부합니다.
- `토도 학습 감시 · 로컬 테스트` 접근성 서비스를 켭니다.

이후 준비 manifest와 설치 APK 해시를 확인하고 실행합니다.

```powershell
.\tools\e2e-ticket02\Run-Ticket02.ps1 -RunPrepared
```

`-RunPrepared`는 APK를 빌드하거나 제품 앱을 재설치하지 않습니다. 실제 알림 채널·권한·접근성 서비스 lease를 읽기 전용으로 검사한 뒤 임시 fixture 서버와 시나리오를 실행합니다. 인자 없이 실행하는 기본 동작도 `-RunPrepared`이므로 첫 실행에는 반드시 먼저 `-PrepareDevice`를 실행해야 합니다. 준비가 덜 되어 있으면 등록이나 fixture 요청 전에 실패하며 권한과 채널을 자동 변경하지 않습니다.

## 이후 실행과 APK 갱신

준비 manifest와 APK가 그대로라면 `-RunPrepared`를 다시 실행합니다. observer 코드만 바뀐 경우에는 `-RefreshObserver`를 사용합니다. 이 단계는 observer와 observer 테스트 APK만 빌드·갱신하고, 제품 APK 설치본과 접근성 서비스 PID·lease가 유지되는지 확인합니다. 제품 앱을 재설치하거나 fixture 서버를 시작하지 않습니다. 제품 debug APK가 바뀌어 다시 설치해야 하면 `-PrepareDevice`를 실행한 뒤 접근성 서비스를 기기 설정에서 직접 다시 연결해야 합니다.

기존 `-DeviceOnly` 옵션은 `-PrepareDevice`의 legacy alias입니다. APK 빌드와 debug/release lint를 실행하고 unit test suite는 생략합니다. debug 앱·observer·observer 테스트를 설치하고 준비 manifest를 만들며 시나리오나 임시 서버는 시작하지 않습니다.

## 시나리오와 판정

테스트는 가짜 계정 `ticket01@example.test`와 아이 `테스트 아이`만 사용합니다. 먼저 실제 앱 UI에서 로그인하고 서버 기준점을 확인합니다: 한글 2/6, 수학 5/5, 영어 7/8. 이전 실행에서 남은 동일 시나리오 등록은 debug 앱 UI의 감시 해제 동작으로 지운 뒤 같은 기준점에서 시작합니다. 등록 직후 수학 완료 표시는 체크 상태이며 완료 경보 요청이 없어야 합니다.

이후 fixture의 한글만 6/6으로 바꾸고 실제 `com.enuma.todohangeul` 앱을 전면에 열어 적격 정기 조회를 기다립니다. 완료 의도는 debug 관측 기록으로, 게시 결과는 시스템의 실제 활성 알림으로 각각 확인합니다. 같은 아이의 기존 알림 ID가 유지되고 최신 한글 완료와 세 과목 전체 상태가 보이며 활성 알림이 하나만 남아야 합니다.

관찰은 제품과 별도 프로세스의 외부 observer가 수행하며, 테스트는 실제 `AccessibilityService` lease와 전면 앱 감지를 함께 확인합니다. 결과 필드 `accessibilityServiceLeaseAttached`는 제품의 foreground service를 뜻하지 않고 실제 접근성 서비스 lease를 기록합니다.

이 실행은 소리나 heads-up 전달을 검사하지 않습니다. 채널은 무음이어야 하며 결과에 `productAlertDeliveryTested=false`로 기록됩니다. 제품의 소리·헤드업 전달 검증은 별도 준비와 범위로 다룹니다. Android API 33은 Android 13 대체 검증 결과로 기록되며 Android 15 결과로 간주하지 않습니다.

## 산출물

각 실행 결과는 `build/e2e-ticket02/<run-id>/`에 저장됩니다. `server-requests.json`에는 fixture 요청 순서, fake 계정, 서울 날짜 및 서버가 제공한 리포트 수가 기록됩니다. `ticket02-result.json`에는 실제 엔진 의도, `accessibilityServiceLeaseAttached`, 전면 앱 관찰, 기존 알림 ID 갱신 및 silent-only 범위가 기록됩니다. 서버는 `127.0.0.1`에만 바인딩되며 실행이 종료되면 실행기가 소유한 서버와 adb reverse 매핑만 정리합니다.
