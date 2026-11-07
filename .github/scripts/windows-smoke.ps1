# 用打好的可执行 jar 和真的连接程序走一遍设计“手工检查”里 CI 能做的部分（windows-server 任务 17，只在 windows-latest 上跑）：
#   1. users add 从管道读中文密码，浏览器那样用同一个密码登录（控制台编码那条只能手工测：CI 上没有中文控制台）
#   2. 数据目录含空格和中文；上传附件、写记忆、关一个工具、提一次问；直接结束进程（同任务管理器）再启动，都还在；check 子命令
#   3. 连接程序（目录含空格和中文）连上服务器，网页上撤销后它退出码 2
#   4. 数据目录在 D:\ 下：启动日志有 WARN 和 icacls 命令；照着运行后，目录和里面的文件没有给别的用户的条目
#   5. 数据目录是 UNC：服务器拒绝启动、users 子命令退出码 1，都写明原因
# 不用 API 密钥：默认路线换成连不上的本机路线，提问时模型调用失败，照样写进 journal。
# 含中文的数据目录经环境变量 TANTALUM_DATADIR 给（Spring 的宽松绑定）：Java 启动器按系统的 ANSI 代码页解码命令行参数，
# 英文系统的 runner 上中文参数会变成 ?；环境变量和当前目录走的是 Unicode 接口。连接程序照 README 先进目录再 --allow .
param(
  [Parameter(Mandatory)][string]$Jar,
  [Parameter(Mandatory)][string]$ConnectorClasspath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3
$Jar = (Resolve-Path $Jar).Path
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$root = Join-Path ([IO.Path]::GetTempPath()) "tantalum-smoke-$PID"
$data = Join-Path $root '数据 目录\tantalum'
$keyless = @('--tantalum.provider=local', '--tantalum.openai-routes.local.base-url=http://127.0.0.1:9/v1',
  '--tantalum.openai-routes.local.model=smoke')
$password = '中文 密码 pw-1'
$started = @()

# 写到主机，不进函数的返回值（否则 Login 返回的就是 [字符串, 会话]）
function Check([bool]$ok, [string]$what) {
  if (-not $ok) { throw "FAILED: $what" }
  Write-Host "ok: $what"
}

function Free-Port {
  $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
  $listener.Start(); $port = $listener.LocalEndpoint.Port; $listener.Stop()
  return $port
}

function Start-Server([string]$dir, [int]$port, [string]$log, [bool]$ownWorkspace = $true) {
  $env:TANTALUM_DATADIR = $dir
  if ($ownWorkspace) { $env:TANTALUM_WORKSPACE = "$dir\workspaces" }
  try {
    $process = Start-Process -PassThru -NoNewWindow -FilePath $java -ArgumentList (@('-jar', "`"$Jar`"", "--server.port=$port") + $keyless) `
      -RedirectStandardOutput $log -RedirectStandardError "$log.err"
  } finally {
    Remove-Item Env:TANTALUM_DATADIR, Env:TANTALUM_WORKSPACE -ErrorAction SilentlyContinue
  }
  $script:started += $process
  return $process
}

# 回复按 UTF-8 解码，不靠 PowerShell 猜编码
function Text($response) { [Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray()) }

function Wait-Up([int]$port, $process) {
  $deadline = (Get-Date).AddSeconds(90)
  while ((Get-Date) -lt $deadline -and -not $process.HasExited) {
    try { Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$port/api/config" -TimeoutSec 2 | Out-Null; return $true }
    catch { if ($_.Exception.PSObject.Properties['Response'] -and $_.Exception.Response) { return $true } }
    Start-Sleep -Milliseconds 500
  }
  return $false
}

# 浏览器那样：JSON 按 UTF-8 发，带 Origin，Cookie 留在会话里
function Call([string]$method, [string]$path, $body, $session, [string]$type = 'application/json; charset=utf-8') {
  $base = "http://127.0.0.1:$script:port"
  $request = @{ Method = $method; Uri = "$base$path"; WebSession = $session; Headers = @{ Origin = $base };
    UseBasicParsing = $true; SkipHttpErrorCheck = $true }
  if ($null -ne $body) {
    # 直接赋值：写成 $x = if (…) { $bytes } 会把 byte[] 展开成 object[]
    if ($body -is [byte[]]) { $request.Body = $body } else { $request.Body = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Compress)) }
    $request.ContentType = $type
  }
  return Invoke-WebRequest @request
}

function Login {
  $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
  $response = Call 'POST' '/api/auth/login' @{ username = 'yi'; password = $password } $session
  Check ($response.StatusCode -eq 200) "login with the Chinese password (HTTP $($response.StatusCode))"
  return $session
}

try {
  New-Item -ItemType Directory -Force $root | Out-Null

  # ---- 0. 记下 Java 怎么解码含中文的命令行参数（只打印，README 的说明以它为准） ----
  $echo = Join-Path $root 'Echo.java'
  Set-Content -Encoding utf8 $echo 'class Echo { public static void main(String[] a) { System.out.println(a[0].equals("\u6570\u636e") ? "arguments keep Chinese" : "arguments lose Chinese: " + a[0].chars().boxed().toList()); } }'
  "code page $([Console]::OutputEncoding.CodePage), $(& $java -XshowSettings:properties -version 2>&1 | Select-String 'sun.jnu.encoding')"
  & $java $echo '数据'

  # ---- 1. users add：中文密码从管道读（UTF-8） ----
  $env:TANTALUM_DATA_DIR = $data
  $password | & $java -jar $Jar users add yi --lang en
  Check ($LASTEXITCODE -eq 0) 'users add yi'
  Remove-Item Env:TANTALUM_DATA_DIR

  # ---- 2. 启动、写东西、直接结束、再启动 ----
  $script:port = Free-Port
  $first = Start-Server $data $port "$root\first.log"
  Check (Wait-Up $port $first) "the jar starts with the data directory $data"
  $web = Login
  $png = [IO.File]::ReadAllBytes((Resolve-Path 'tantalum-shell\src\test\resources\attachments\clean.png'))
  $upload = Call 'POST' '/api/sessions/s-win/attachments?name=clean.png&type=image/png' $png $web 'application/octet-stream'
  Check ($upload.StatusCode -eq 200) "attachment upload (HTTP $($upload.StatusCode))"
  $attachment = (Text $upload | ConvertFrom-Json).id
  $memory = Call 'POST' '/api/memory' @{ name = 'windows-note'; type = 'user'; description = '从 Windows 写的'; content = '记住：路径有空格和中文' } $web
  Check ($memory.StatusCode -eq 201) "memory write (HTTP $($memory.StatusCode))"
  $switch = Call 'PUT' '/api/plugins/tool/memory_write' @{ enabled = $false } $web
  Check ($switch.StatusCode -eq 200) "plugin switch off (HTTP $($switch.StatusCode))"
  $asked = Call 'POST' '/api/sessions/s-win/submissions' @{ requestId = 'r-win'; mode = 'prompt'; text = '你好 Windows' } $web
  Check ($asked.StatusCode -eq 200) "submission accepted (HTTP $($asked.StatusCode))"
  Start-Sleep 3
  Stop-Process -Id $first.Id -Force
  $first.WaitForExit()
  Check (Test-Path (Join-Path $data 'sessions')) 'the session file was written before the process was ended'

  $second = Start-Server $data $port "$root\second.log"
  Check (Wait-Up $port $second) 'the jar starts again after being ended'
  $web = Login
  $bytes = (Call 'GET' "/api/attachments/$attachment" $null $web)
  Check ($bytes.StatusCode -eq 200 -and $bytes.RawContentLength -gt 0) 'the attachment is still there'
  $note = Call 'GET' '/api/memory/windows-note' $null $web
  Check ($note.StatusCode -eq 200 -and (Text $note | ConvertFrom-Json).content -eq '记住：路径有空格和中文') 'the memory is still there'
  $tools = (Text (Call 'GET' '/api/plugins' $null $web) | ConvertFrom-Json).tools
  Check ((($tools | Where-Object name -eq 'memory_write').enabled) -eq $false) 'the plugin switch is still off'
  $messages = Text (Call 'GET' '/api/sessions/s-win/messages' $null $web)
  Check ($messages.Contains('你好 Windows')) 'the prompt is still in the session'

  # ---- 3. 连接程序 ----
  $work = Join-Path $root '项目 目录'
  New-Item -ItemType Directory -Force $work | Out-Null
  $token = (Text (Call 'POST' '/api/local/tokens' @{ validity = '30m' } $web) | ConvertFrom-Json).token
  $env:TANTALUM_TOKEN = $token
  $connector = Start-Process -PassThru -NoNewWindow -FilePath $java -WorkingDirectory $work -RedirectStandardOutput "$root\connect.log" `
    -RedirectStandardError "$root\connect.err" -ArgumentList @('-cp', "`"$ConnectorClasspath`"", 'tantalum.connect.Main',
      '--server', "http://127.0.0.1:$port", '--allow', '.', '--lang', 'en')
  Remove-Item Env:TANTALUM_TOKEN
  $script:started += $connector
  $link = $null
  for ($i = 0; $i -lt 60 -and -not $link; $i++) {
    Start-Sleep -Milliseconds 500
    $link = (Text (Call 'GET' '/api/local' $null $web) | ConvertFrom-Json).link
  }
  if (-not $link) { Get-Content "$root\connect.log", "$root\connect.err" -ErrorAction SilentlyContinue }
  Check ($null -ne $link) "the connector connected: $($link | ConvertTo-Json -Compress -Depth 5)"
  Check ((Call 'DELETE' '/api/local/tokens' $null $web).StatusCode -eq 200) 'revoke the token'
  Check ($connector.WaitForExit(15000) -and $connector.ExitCode -eq 2) "the connector ended with exit code 2 after the revoke"
  Check (-not (Select-String -Path "$root\connect.log", "$root\connect.err" -SimpleMatch $token -Quiet)) 'the token is not in the connector output'
  Stop-Process -Id $second.Id -Force
  $second.WaitForExit()

  $env:TANTALUM_DATADIR = $data
  & $java -jar $Jar check --lang en
  $code = $LASTEXITCODE
  Remove-Item Env:TANTALUM_DATADIR
  Check ($code -eq 0) 'check replays every session to its recorded results'

  # ---- 4. 用户目录外：WARN、照着运行 icacls ----
  $outside = "D:\tantalum-smoke-$PID"
  $env:TANTALUM_DATA_DIR = $outside
  $password | & $java -jar $Jar users add yi --lang en
  $code = $LASTEXITCODE
  Remove-Item Env:TANTALUM_DATA_DIR
  Check ($code -eq 0) "users add yi in $outside"
  $third = Start-Server $outside $port "$root\outside.log"
  Check (Wait-Up $port $third) "the jar starts with $outside"
  $warning = Select-String -Path "$root\outside.log" -SimpleMatch 'is outside your user profile'
  Check ($null -ne $warning) 'the outside-profile WARN is logged'
  $icacls = (Get-Content "$root\outside.log" | Where-Object { $_ -match '^\s+icacls ' } | Select-Object -First 1).Trim()
  "running: $icacls"
  cmd.exe /c $icacls
  Check ($LASTEXITCODE -eq 0) 'the icacls command from the WARN runs'
  $others = 'Everyone', 'BUILTIN\Users', 'NT AUTHORITY\Authenticated Users'
  foreach ($path in @($outside, (Join-Path $outside 'users.db'), (Join-Path $outside 'store.lock'))) {
    $open = @((Get-Acl $path).Access | Where-Object { $others -contains $_.IdentityReference.Value })
    Check ($open.Count -eq 0) "after icacls nobody else is in the ACL of $path"
  }
  Stop-Process -Id $third.Id -Force
  $third.WaitForExit()

  # ---- 5. 网络共享：拒绝 ----
  $unc = "\\localhost\C$\tantalum-unc-$PID"
  $refused = Start-Server $unc (Free-Port) "$root\unc.log" $false
  Check ($refused.WaitForExit(90000) -and $refused.ExitCode -ne 0) 'the jar refuses to start on a UNC data directory'
  Check ($null -ne (Select-String -Path "$root\unc.log", "$root\unc.log.err" -SimpleMatch 'is on a network share')) 'the refusal says why'
  $env:TANTALUM_DATA_DIR = $unc
  $said = 'x' | & $java -jar $Jar users add bob --lang en
  $code = $LASTEXITCODE
  Remove-Item Env:TANTALUM_DATA_DIR
  Check ($code -eq 1 -and ($said -join "`n").Contains('is on a network share')) "users refuses the UNC data directory: $said"
  Check (-not (Test-Path "C:\tantalum-unc-$PID")) 'nothing was created on the share'
  'windows smoke passed'
} catch {
  # 失败时把各进程的日志尾巴打出来，临时目录随后就删
  Get-ChildItem $root -File -Filter '*.log*' -ErrorAction SilentlyContinue | ForEach-Object { "== $($_.Name)"; Get-Content $_.FullName -Tail 60 }
  throw
} finally {
  foreach ($process in $started) { if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue } }
  Remove-Item -Recurse -Force $root, "D:\tantalum-smoke-$PID" -ErrorAction SilentlyContinue
}
