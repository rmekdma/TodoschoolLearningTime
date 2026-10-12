# Ticket 02: 무음 기준점과 완료 시 기존 알림 갱신

Ticket 02는 Ticket 01의 임시 loopback 서버, 실제 debug 앱, 계측 테스트, Gradle 및 APK 검사를 재사용합니다. 별도 가짜 엔진이나 임대 서비스, 알림 게시 대역은 사용하지 않습니다. fixture API 응답을 통해 실제 앱의 가입·조회·엔진·접근성 서비스·Android `NotificationManager` 경로를 확인하며 TodoSchool 실제 API는 호출하지 않습니다.

## 실행 전 사람이 준비할 항목

기존 `learning-monitor-status` 채널의 현재 중요도와 소리 설정은 변경할 수 없으므로, `토도 학습시간 · 로컬 테스트` 앱에서 다음을 직접 준비해야 합니다.

- 앱 알림 허용 및 Android 13 이상에서 `POST_NOTIFICATIONS` 허용
- `학습 상태` 채널을 무음으로 설정하고 소리와 진동을 끄기. 중요도가 낮음/최소이면 Android가 사운드 URI를 보존해도 소리와 heads-up을 억제하므로 실행할 수 있습니다. 기본 중요도에서는 채널 사운드 URI가 없어야 하며, 높음과 차단 상태는 거부합니다.
- `토도 학습 감시 · 로컬 테스트` 접근성 서비스를 직접 켜기

실행 전 테스트는 채널이 실제 존재하고 Android 중요도·사운드·진동 설정상 무음인지, 알림 권한과 서비스 연결이 유효한지 읽기 전용으로 확인합니다. 준비가 덜 되면 실패하며, 권한이나 채널 설정을 자동 변경하지 않습니다. 앱 데이터에는 가짜 계정 `ticket01@example.test`와 아이 `테스트 아이`만 사용합니다.

## 실행

`tools/e2e-ticket01/config.example.json`을 `config.json`으로 복사하고 필요한 경우 기기 serial을 지정한 뒤 PowerShell에서 실행합니다.

```powershell
Copy-Item tools/e2e-ticket01/config.example.json tools/e2e-ticket01/config.json
# 필요한 경우 config.json의 deviceSerial을 설정합니다.
.\tools\e2e-ticket02\Run-Ticket02.ps1
```

전체 검증이 같은 작업 트리에서 이미 통과했고 APK만 다시 빌드해야 할 때는 `-DeviceOnly`를 지정합니다. 이 모드는 debug, instrumentation, e2e, release APK를 빌드하지만 unit test와 lint를 다시 실행하지 않습니다.

외부 observer를 변경한 뒤에는 `-RefreshObserver`로 observer와 테스트 APK만 빌드·갱신합니다. 준비된 제품 APK 설치본이 그대로인지, 실제 접근성 서비스 PID와 연결이 유지되는지 확인하며 제품 앱을 재설치하거나 fixture 서버를 시작하지 않습니다.

실행기는 debug APK에 `토도 학습시간 · 로컬 테스트` 앱 레이블과 별도 접근성 레이블이 들어있는지 확인하고, 실제 앱과 계측 APK를 업데이트 설치합니다. Android API 33은 Android 13 대체 검증 결과로 기록됩니다. Android 15 결과로 간주하지 않습니다.

테스트는 먼저 실제 서버 데이터의 기준점을 확인합니다: 한글 2/6, 수학 5/5, 영어 7/8. 이전 실행에서 남은 등록은 debug 앱 UI의 감시 해제 동작으로 지운 뒤 같은 기준점에서 시작합니다. 등록 직후 수학 완료 표시는 체크 상태이며 완료 경보 요청이 없어야 합니다. 이후 fixture의 한글만 6/6으로 바꾸고 실제 `com.enuma.todohangeul` 앱을 전면에 열어 정상 주기 조회를 기다립니다. 완료 의도는 debug 관측 기록으로, 게시 결과는 시스템의 실제 활성 알림으로 각각 확인합니다. 같은 아이의 기존 알림 ID가 유지되고 최신 한글 완료와 세 과목 전체 상태가 보이며 활성 알림이 하나만 남아야 합니다.

이 실행은 소리나 heads-up 전달을 검사하지 않습니다. 알림 채널이 무음이어야 하며 `productAlertDeliveryTested=false`로 결과에 기록됩니다. 제품의 소리·헤드업 전달 검증은 별도 준비와 범위로 다룹니다.

## 산출물

각 실행 결과는 `build/e2e-ticket02/<run-id>/`에 저장됩니다. `server-requests.json`에는 fixture 요청 순서, fake 계정, 서울 날짜 및 서버가 제공한 리포트 수가 기록됩니다. `ticket02-result.json`에는 실제 엔진 의도, foreground service/앱 관찰, 기존 알림 ID 갱신 및 silent-only 범위가 기록됩니다. 서버는 `127.0.0.1`에만 바인딩되며 실행이 종료되면 실행기가 소유한 서버와 adb reverse 매핑만 정리합니다.
