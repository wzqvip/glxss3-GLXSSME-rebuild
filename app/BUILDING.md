# 构建 / Building

本项目是标准 Android Gradle 工程（Kotlin + Jetpack Compose）。

## 依赖

| 项 | 版本 |
|---|---|
| JDK | 17（`JAVA_HOME` 必须指向 **JDK**，不是 JRE） |
| Gradle | 8.9+ |
| Android SDK | platform `android-35`、build-tools `35.0.0`、platform-tools |
| AGP | 8.7.3（`app/build.gradle.kts`） |
| Kotlin | 2.0.21 |

## 一次性准备（Windows 示例）

```powershell
# 1) Android 命令行工具
#    https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip
#    解压到 C:\Android\sdk\cmdline-tools\latest
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.10.7-hotspot"
C:\Android\sdk\cmdline-tools\latest\bin\sdkmanager.bat --sdk_root=C:\Android\sdk `
    "platform-tools" "platforms;android-35" "build-tools;35.0.0"

# 2) Gradle 8.9
#    https://services.gradle.org/distributions/gradle-8.9-bin.zip → C:\gradle\gradle-8.9

# 3) 指向 SDK
"sdk.dir=C\:\\Android\\sdk" | Out-File -FilePath app\local.properties -Encoding ascii -NoNewline
```

## 构建

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.10.7-hotspot"
cd app
C:\gradle\gradle-8.9\bin\gradle.bat assembleDebug --no-daemon --console=plain
# 产物: app/app/build/outputs/apk/debug/app-debug.apk
```

## 安装与测试

```powershell
adb install -r app\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.wzqvip.glxsscast/.MainActivity
```

1. 插上眼镜（`2e09:0030`），允许 USB 访问；
2. 点「连接眼镜」→ 点「发送测试色条」→ 眼镜应显示彩条；
3. 点「开始投屏」→ 系统录屏授权 → 眼镜实时显示手机画面；
4. 「亮度 +/−」「开屏/关屏」「读取状态」使用实测控制命令。

## 已知构建问题

- `This material API is experimental`：已在 `app/build.gradle.kts` 里加
  `-opt-in=androidx.compose.material3.ExperimentalMaterial3Api`。
- 找不到 SDK：确认 `app/local.properties` 的 `sdk.dir` 存在且路径转义正确。
