# 토도 학습시간 앱

토도 학습시간 확인 앱

## 동작

- 첫 실행에서 토도스쿨 이메일, 비밀번호를 입력합니다.
- 비밀번호는 Android Keystore의 AES/GCM 키로 암호화해서 이 기기에 저장합니다.
- 앱을 실행할 때 토도스쿨 API에 로그인합니다.
- 한글/영어/수학의 오늘 학습시간을 아이 이름별로 합산합니다.
- `etc` 카테고리는 제외합니다.
- 초 단위 총합을 올림하여 분 단위로 표시합니다.
- 인증 실패 시 저장된 비밀번호를 지우고 다시 입력받습니다.

## 빌드

Android Studio에서 이 폴더를 열고 APK를 빌드하거나, Gradle 9.6+와 Android SDK가 설치된 환경에서 다음을 실행합니다.

```bash
gradle :app:assembleDebug
```

생성 APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```
