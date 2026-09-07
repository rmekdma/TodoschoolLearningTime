# 토도 학습시간 앱

토도스쿨 학습시간 및 일일 과목별 완료 현황 확인 안드로이드 앱

## 주요 기능

- **보안 로그인**:
  - 첫 실행 시 토도스쿨 이메일, 비밀번호를 입력받습니다.
  - 비밀번호는 Android Keystore의 AES/GCM 키로 암호화되어 기기에 안전하게 저장됩니다.
  - 인증 실패 시 저장된 자격 증명을 초기화하고 재입력을 요청합니다.
- **날짜별 학습시간 조회**:
  - 상단 날짜 네비게이션(`<`, `M월 d일 (E)`, `>`) 및 달력(DatePicker)으로 과거 학습 기록을 자유롭게 조회할 수 있습니다.
  - 달력 내 '오늘' 버튼으로 현재 일자로 즉시 복귀할 수 있습니다.
  - 오늘 날짜에서는 화면을 아래로 당겨 새로고침(SwipeRefresh)을 지원합니다.
- **학습시간 및 과목 완료 현황 (3줄 카드 뷰)**:
  - 1행: 아이 이름 및 목표 설정 아이콘(`⚙️`)
  - 2행: 당일 총 학습시간 (분 단위 올림 표시, `etc` 카테고리 제외)
  - 3행: 구독 과목별 활동 수 및 완료 현황 (`✔ 한글 (8/6), 수학 (3/5), ✔ 영어 (13/8)`)
- **아이별 일일 목표 개수 설정**:
  - 아이 이름 옆 `⚙️` 버튼을 눌러 과목별 목표 활동 수를 맞춤 설정할 수 있습니다. (기본값: 한글 6, 수학 5, 영어 8)
  - 목표 개수를 `0`으로 설정하면 당일 활동이 없어도 항상 완료 처리됩니다.

## 빌드 및 설치

Android Studio에서 프로젝트를 열거나 터미널에서 다음 명령어를 실행합니다.

### Debug APK 빌드
```bash
./gradlew assembleDebug
```
생성 경로: `app/build/outputs/apk/debug/app-debug.apk`

### Release APK 빌드
```bash
./gradlew assembleRelease
```
생성 경로: `app/build/outputs/apk/release/app-release.apk`

### 최신 릴리즈 다운로드
[GitHub Releases](https://github.com/rmekdma/TodoschoolLearningTime/releases)에서 사전 빌드된 최신 APK 파일을 다운로드받아 기기에 바로 설치할 수 있습니다.
