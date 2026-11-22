<#
.SYNOPSIS
  glxss CLI 的 Windows PyInstaller 构建脚本。

.DESCRIPTION
  默认 onefile；输出 linux\dist\glxss.exe。
  Windows 需要 libusb-1.0.dll：用 -LibusbDll 指定后会被打进包；
  不指定则要求运行机器上能在 PATH / System32 / exe 同目录找到它。
  设备仍需用 Zadig 把目标接口绑定到 WinUSB。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File packaging\build.ps1
  powershell -ExecutionPolicy Bypass -File packaging\build.ps1 -OneDir
  powershell -ExecutionPolicy Bypass -File packaging\build.ps1 -LibusbDll C:\libusb\libusb-1.0.dll
  powershell -ExecutionPolicy Bypass -File packaging\build.ps1 -InstallDeps
#>
param(
    [switch]$OneDir,
    [switch]$NoClean,
    [switch]$InstallDeps,
    [string]$Python = "python",
    [string]$LibusbDll = ""
)

$ErrorActionPreference = "Stop"
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = Split-Path -Parent $Here
Set-Location $Root

function Invoke-Checked {
    param([string[]]$Arguments)
    # Windows PowerShell 5.1 会把原生程序的 stderr 包成 ErrorRecord；
    # 临时关掉 Stop，改为只检查 $LASTEXITCODE。
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $Python @Arguments
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($code -ne 0) {
        throw "命令失败（exit $code）: $Python $($Arguments -join ' ')"
    }
}

Write-Host "== 环境 =="
Write-Host "  python : $(& $Python --version 2>&1)"
Write-Host "  root   : $Root"
Write-Host "  mode   : $(if ($OneDir) { 'onedir' } else { 'onefile' })"

if ($InstallDeps) {
    Write-Host "== 安装构建依赖 =="
    Invoke-Checked @("-m", "pip", "install", "-U", "pip")
    Invoke-Checked @("-m", "pip", "install", "-e", ".[dev]", "pyinstaller")
}

$previous = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& $Python -c "import PyInstaller" 2>$null | Out-Null
$hasPyInstaller = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = $previous
if (-not $hasPyInstaller) {
    Write-Error "未安装 PyInstaller。先运行：`n  $Python -m pip install pyinstaller`n或：packaging\build.ps1 -InstallDeps"
    exit 2
}

if ($LibusbDll -and -not (Test-Path $LibusbDll)) {
    Write-Warning "libusb DLL 不存在: $LibusbDll"
}

$env:GLXSS_ONEFILE = if ($OneDir) { "0" } else { "1" }
if ($LibusbDll) {
    $env:GLXSS_LIBUSB_DLL = (Resolve-Path $LibusbDll).Path
}

$PyArgs = @(
    "-m", "PyInstaller",
    "--noconfirm",
    "--distpath", (Join-Path $Root "dist"),
    "--workpath", (Join-Path $Root "build\pyinstaller")
)
if (-not $NoClean) {
    $PyArgs += "--clean"
}
$PyArgs += (Join-Path $Here "glxss.spec")

Write-Host "== PyInstaller =="
Write-Host "  spec   : $Here\glxss.spec"
Invoke-Checked $PyArgs

$Exe = if ($OneDir) {
    Join-Path $Root "dist\glxss\glxss.exe"
} else {
    Join-Path $Root "dist\glxss.exe"
}
if (-not (Test-Path $Exe)) {
    Write-Error "构建结束但找不到 $Exe"
    exit 1
}

Write-Host ""
Write-Host "== 产物 =="
if ($OneDir) {
    $Size = (Get-ChildItem (Join-Path $Root "dist\glxss") -Recurse -File |
             Measure-Object -Property Length -Sum).Sum
} else {
    $Size = (Get-Item $Exe).Length
}
Write-Host ("  {0}  ({1:N1} MB)" -f $Exe, ($Size / 1MB))
Write-Host "冒烟测试: $Exe --help"
$previous = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& $Exe --help | Out-Null
$smoke = $LASTEXITCODE
$ErrorActionPreference = $previous
if ($smoke -ne 0) {
    Write-Error "产物 --help 退出码 $smoke"
    exit $smoke
}

Write-Host ""
Write-Host "提示："
Write-Host "  * 运行机器仍需 libusb-1.0.dll（若构建时未用 -LibusbDll 打进包）。"
Write-Host "  * 用 Zadig 把眼镜目标接口绑到 WinUSB；不要绑整个复合设备。"
Write-Host "  * onefile 首次启动会解压 numpy/Pillow，慢 1-3 秒；可用 -OneDir 换启动速度。"
Write-Host ""
Write-Host "完成：$Exe"
