<#
.SYNOPSIS
  Run Tantalum in the background on Windows as a scheduled task of the current user (windows-server N13).

.DESCRIPTION
  install    Register a task that starts at your logon, has no time limit, does not start a second copy while one runs,
             restarts 1 minute after an abnormal exit (at most 10 times) and shows no console window (javaw.exe).
             Logs go to <data directory>\logs\tantalum.log.
  status     Show the task state, the last result, whether the port answers and where the log is.
  uninstall  Stop and remove the task. The data directory and the logs stay.
  selftest   For CI: install with a temporary data directory and a free port, start, wait for the server, end the
             process and wait for the restart, stop, uninstall.

  No administrator rights and no password are needed. API keys are not written into the task: the server reads them
  from your user environment variables, for example
    [Environment]::SetEnvironmentVariable("ANTHROPIC_API_KEY", "...", "User")

.EXAMPLE
  .\tantalum-task.ps1 install -Jar C:\tantalum\tantalum.jar
#>
param(
  [Parameter(Mandatory, Position = 0)][ValidateSet('install', 'status', 'uninstall', 'selftest')][string]$Action,
  [string]$Jar,
  [int]$Port = 8080,
  [string]$DataDir,
  [string]$Name = 'Tantalum',
  # 另加的服务器参数，例如 "--tantalum.provider=local --tantalum.openai-routes.local.base-url=..."
  [string]$Arguments = '',
  [ValidateSet('', 'zh', 'en', 'ja')][string]$Lang = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3

# 输出三种语言，同命令行（console-i18n）：-Lang，再看 Windows 的显示语言，都不是就用英文。
$Texts = @{
  en = @{
    exists     = 'Task {0} already exists; run "uninstall" first.'
    nojar      = 'Give the jar with -Jar; not found: {0}'
    nojavaw    = 'javaw.exe not found. Install JDK 25 or set JAVA_HOME.'
    installed  = 'Registered task {0}: starts at your logon, port {1}, log {2}'
    startnow   = 'Start it now with: Start-ScheduledTask -TaskName {0}'
    notask     = 'There is no task {0}.'
    state      = 'Task {0}: {1}; last run {2}, last result {3}'
    portup     = 'Port {0}: answering'
    portdown   = 'Port {0}: not answering'
    logat      = 'Log: {0}'
    removed    = 'Removed task {0}. The data directory and the logs are kept.'
    waiting    = 'Waiting for the server on port {0} ...'
    ready      = 'The server answered after {0} s.'
    notready   = 'The server did not answer within {0} s.'
    stopped    = 'Stopped; the server process is gone.'
    stillthere = 'The server process is still running after the task was stopped.'
    passed     = 'selftest passed'
    killed     = 'Ended the server process as Task Manager would; waiting for the task to restart it ...'
    restarted  = 'The task restarted the server {0} s after it was ended.'
    norestart  = 'The task did not restart the server within {0} s.'
  }
  zh = @{
    exists     = '任务 {0} 已经存在；先运行 "uninstall"。'
    nojar      = '用 -Jar 给出 jar 文件；找不到：{0}'
    nojavaw    = '找不到 javaw.exe。请安装 JDK 25 或设置 JAVA_HOME。'
    installed  = '已登记任务 {0}：登录后启动，端口 {1}，日志 {2}'
    startnow   = '现在就启动：Start-ScheduledTask -TaskName {0}'
    notask     = '没有任务 {0}。'
    state      = '任务 {0}：{1}；上次运行 {2}，结果 {3}'
    portup     = '端口 {0}：有响应'
    portdown   = '端口 {0}：没有响应'
    logat      = '日志：{0}'
    removed    = '已删除任务 {0}。数据目录和日志都还在。'
    waiting    = '等待端口 {0} 上的服务器 …'
    ready      = '服务器在 {0} 秒后有了响应。'
    notready   = '服务器 {0} 秒内没有响应。'
    stopped    = '已停止，服务器进程已经结束。'
    stillthere = '任务停止后服务器进程还在运行。'
    passed     = 'selftest 通过'
    killed     = '像任务管理器那样结束了服务器进程，等计划任务重启它 …'
    restarted  = '服务器被结束 {0} 秒后，计划任务把它重启了。'
    norestart  = '计划任务 {0} 秒内没有重启服务器。'
  }
  ja = @{
    exists     = 'タスク {0} はすでにあります。先に "uninstall" を実行してください。'
    nojar      = '-Jar で jar ファイルを指定してください。見つかりません：{0}'
    nojavaw    = 'javaw.exe が見つかりません。JDK 25 をインストールするか JAVA_HOME を設定してください。'
    installed  = 'タスク {0} を登録しました：サインイン時に起動、ポート {1}、ログ {2}'
    startnow   = '今すぐ起動するには：Start-ScheduledTask -TaskName {0}'
    notask     = 'タスク {0} はありません。'
    state      = 'タスク {0}：{1}。前回の実行 {2}、結果 {3}'
    portup     = 'ポート {0}：応答あり'
    portdown   = 'ポート {0}：応答なし'
    logat      = 'ログ：{0}'
    removed    = 'タスク {0} を削除しました。データディレクトリとログは残っています。'
    waiting    = 'ポート {0} のサーバーを待っています …'
    ready      = 'サーバーは {0} 秒後に応答しました。'
    notready   = 'サーバーは {0} 秒以内に応答しませんでした。'
    stopped    = '停止しました。サーバーのプロセスは終了しています。'
    stillthere = 'タスクを停止したあともサーバーのプロセスが動いています。'
    passed     = 'selftest 成功'
    killed     = 'タスク マネージャーと同じようにサーバーのプロセスを終了しました。タスクが再起動するのを待っています …'
    restarted  = 'サーバーは終了から {0} 秒後にタスクによって再起動されました。'
    norestart  = 'タスクは {0} 秒以内にサーバーを再起動しませんでした。'
  }
}
if (-not $Lang) {
  $Lang = (Get-UICulture).TwoLetterISOLanguageName
  if (-not $Texts.ContainsKey($Lang)) { $Lang = 'en' }
}
function Say([string]$key) { $Texts[$Lang][$key] -f $args }

function Find-Javaw {
  if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javaw.exe'))) {
    return (Join-Path $env:JAVA_HOME 'bin\javaw.exe')
  }
  $found = Get-Command javaw.exe -ErrorAction SilentlyContinue
  if ($found) { return $found.Source }
  throw (Say nojavaw)
}

# 和服务器同一个默认：-DataDir，再看 TANTALUM_DATA_DIR，都没有就是 %USERPROFILE%\.local\share\tantalum。
# 结尾的 \ 会把参数的右引号转义掉，所以去掉。
function Resolve-DataDir {
  if ($DataDir) { return [IO.Path]::GetFullPath($DataDir).TrimEnd('\') }
  if ($env:TANTALUM_DATA_DIR) { return [IO.Path]::GetFullPath($env:TANTALUM_DATA_DIR).TrimEnd('\') }
  return (Join-Path $env:USERPROFILE '.local\share\tantalum')
}

function Get-Task { Get-ScheduledTask -TaskName $Name -ErrorAction SilentlyContinue }

function Test-Port([int]$port) {
  $client = New-Object Net.Sockets.TcpClient
  try { $client.Connect('127.0.0.1', $port); return $true } catch { return $false } finally { $client.Dispose() }
}

function Install-Task([string]$dir, [string]$logonType) {
  if (Get-Task) { throw (Say exists $Name) }
  if (-not $Jar -or -not (Test-Path $Jar -PathType Leaf)) { throw (Say nojar $Jar) }
  $jarPath = (Resolve-Path $Jar).Path
  $javaw = Find-Javaw
  $log = Join-Path $dir 'logs\tantalum.log'
  $line = "-jar `"$jarPath`" --server.port=$Port `"--logging.file.name=$log`" `"--tantalum.data-dir=$dir`""
  if ($Arguments) { $line = "$line $Arguments" }
  $user = "$env:USERDOMAIN\$env:USERNAME"
  $taskAction = New-ScheduledTaskAction -Execute $javaw -Argument $line -WorkingDirectory (Split-Path $jarPath)
  $trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
  $principal = New-ScheduledTaskPrincipal -UserId $user -LogonType $logonType -RunLevel Limited
  $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew `
    -RestartCount 10 -RestartInterval (New-TimeSpan -Minutes 1) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
  Register-ScheduledTask -TaskName $Name -Action $taskAction -Trigger $trigger -Principal $principal -Settings $settings | Out-Null
  Say installed $Name $Port $log
}

# 端口和日志从任务自己的参数里读回来，status 不用再给一遍 install 的参数。
function Show-Status {
  $task = Get-Task
  if (-not $task) { Say notask $Name; return }
  $info = $task | Get-ScheduledTaskInfo
  Say state $Name $task.State $info.LastRunTime ('0x{0:X}' -f $info.LastTaskResult)
  $line = $task.Actions[0].Arguments
  $port = if ($line -match '--server\.port=(\d+)') { [int]$Matches[1] } else { 8080 }
  if (Test-Port $port) { Say portup $port } else { Say portdown $port }
  if ($line -match '--logging\.file\.name=([^"]+)') { Say logat $Matches[1] }
}

function Remove-Task {
  if (-not (Get-Task)) { Say notask $Name; return }
  Stop-ScheduledTask -TaskName $Name -ErrorAction SilentlyContinue
  Unregister-ScheduledTask -TaskName $Name -Confirm:$false
  Say removed $Name
}

function Get-ServerProcess([string]$dir) {
  Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($dir) }
}

function Invoke-Selftest {
  $script:Name = "TantalumSelftest$PID"
  $dir = Join-Path ([IO.Path]::GetTempPath()) "tantalum-task-$PID"
  New-Item -ItemType Directory -Force $dir | Out-Null
  $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
  $listener.Start(); $script:Port = $listener.LocalEndpoint.Port; $listener.Stop()
  # CI 上没有 API 密钥：默认路线换成不要密钥的本机路线，服务器照样起得来（连不上没关系，不调模型）。
  $script:Arguments = '--tantalum.provider=local --tantalum.openai-routes.local.base-url=http://127.0.0.1:9/v1 ' +
    '--tantalum.openai-routes.local.model=selftest'
  # 真实用户登录着，install 用 Interactive。CI 的 runner 可能没有交互会话，任务起不来，selftest 就改用 S4U 再登记一次
  # （不需要登录会话，照样不存密码；设计“tantalum-task.ps1”）。
  $answered = $false
  $started = Get-Date
  foreach ($logonType in @('Interactive', 'S4U')) {
    try {
      Install-Task $dir $logonType
      Start-ScheduledTask -TaskName $Name
    } catch {
      "logon type ${logonType}: $_"
      Remove-Task
      continue
    }
    Say waiting $Port
    $started = Get-Date
    while (((Get-Date) - $started).TotalSeconds -lt 60) {
      # 没登录时 /api/config 回 401：有 HTTP 回复就说明服务器起来了。
      try { Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$Port/api/config" -TimeoutSec 2 | Out-Null; $answered = $true }
      catch { if ($_.Exception.PSObject.Properties['Response'] -and $_.Exception.Response) { $answered = $true } }
      if ($answered) { break }
      if (((Get-Date) - $started).TotalSeconds -gt 15 -and (Get-Task).State -ne 'Running') { break }
      Start-Sleep -Milliseconds 500
    }
    if ($answered) { break }
    Show-Status
    "logon type ${logonType}: the task did not start the server"
    Remove-Task
  }
  try {
    $seconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
    if (-not $answered) { throw (Say notready 60) }
    Say ready $seconds
    "logon type: $logonType"
    Show-Status
    # 异常退出（任务管理器结束进程、崩溃）：计划任务按 1 分钟的重启间隔把服务器再起来（需求 6.1）。
    Get-ServerProcess $dir | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
    $killedAt = Get-Date
    Say killed
    Start-Sleep 5
    $restarted = $false
    while (((Get-Date) - $killedAt).TotalSeconds -lt 120) {
      if ((Get-ServerProcess $dir) -and (Test-Port $Port)) { $restarted = $true; break }
      Start-Sleep 2
    }
    if (-not $restarted) { Show-Status; throw (Say norestart 120) }
    Say restarted ([math]::Round(((Get-Date) - $killedAt).TotalSeconds))
    Stop-ScheduledTask -TaskName $Name
    $deadline = (Get-Date).AddSeconds(15)
    while ((Get-ServerProcess $dir) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 300 }
    if (Get-ServerProcess $dir) { throw (Say stillthere) }
    Say stopped
  } finally {
    Remove-Task
  }
  if (Get-Task) { throw "task $Name is still registered" }
  if (-not (Test-Path $dir)) { throw "the data directory $dir was removed" }
  if (-not (Test-Path (Join-Path $dir 'logs\tantalum.log'))) { throw "no log in $dir\logs" }
  Remove-Item -Recurse -Force $dir
  Say passed
}

switch ($Action) {
  'install' { Install-Task (Resolve-DataDir) 'Interactive'; Say startnow $Name }
  'status' { Show-Status }
  'uninstall' { Remove-Task }
  'selftest' { Invoke-Selftest }
}
