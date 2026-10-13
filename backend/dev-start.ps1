# 本地验证用启动脚本（连本机 MongoDB 快照库），仅测试环境使用
# 需先单独停止旧进程：powershell -File backend\target\stop-patient-card.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$arg = '-jar "' + (Join-Path $PSScriptRoot 'patient-card-0.0.2.jar') + '"'
$arg += ' --spring.config.additional-location=file:' + ($root -replace '\\', '/') + '/config/'
$arg += ' --mongo.host=127.0.0.1 --mongo.port=27017 --mongo.username= --mongo.password='
$arg += ' --mongo.center.database=DataCenter --vte.enabled=false'
Start-Process -FilePath 'java' -ArgumentList $arg `
  -RedirectStandardOutput (Join-Path $PSScriptRoot 'run-verify.log') `
  -RedirectStandardError (Join-Path $PSScriptRoot 'run-verify-err.log') `
  -WindowStyle Hidden
for ($i = 0; $i -lt 30; $i++) {
  Start-Sleep -Seconds 1
  try {
    Invoke-WebRequest -Uri 'http://127.0.0.1:18088/api/v1/icu-overview/context/3439' `
      -UseBasicParsing -TimeoutSec 2 | Out-Null
    Write-Output 'server ready'
    return
  } catch { }
}
Write-Output 'server failed to start'
Get-Content (Join-Path $PSScriptRoot 'run-verify.log') -Tail 30
