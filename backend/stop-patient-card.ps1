# 只停止 patient-card-0.0.11-preview.6.jar 的 java 进程，不影响机器上其他 Java 程序
$procs = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -like '*patient-card-0.0.11-preview.6.jar*' }
if ($procs) {
  $procs | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
  Start-Sleep -Seconds 2
  Write-Output ("stopped: " + (($procs | Select-Object -ExpandProperty ProcessId) -join ','))
} else {
  Write-Output 'no patient-card process running'
}
