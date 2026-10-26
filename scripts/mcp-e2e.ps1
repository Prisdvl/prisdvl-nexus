#requires -Version 5.1
<#
.SYNOPSIS
    MCP 全链路验收：注册 → 建 stdio server → 探测 → 授权 → Agent 提问 → 回答里出现 server 的固定文本。

.DESCRIPTION
    这是"能建行"与"能真拉到工具、能被 Agent 调起来"之间的那座桥。它不是单元测试：
    单元测试里 stdio 用假 server、审批用假授权、模型是假的——这条脚本三样都是真的：
    真的 npx 子进程、真的工具表、真的模型。

    它断言五件事，每件都对应一个会悄悄坏掉的环节：
    1. `probe` 真的起了子进程，并把 `tools/list` 覆盖进 `tools_json`（不是"探测成功但工具还是旧的"）；
    2. 工具按 `mcp__<slug>__echo` 注册成功（权限必须是 WRITE，这条由 McpClientProbeTest 断言）；
    3. Agent 真的把它调起来了：`usedTools=true`，工具痕迹里有这条 code，回答里有 `Echo: nexus-mcp-ok`；
    4. 调用结果落到了 server 行上（`lastCallTool/lastCallOk`），插件页才有东西可显示；
    5. 两个失败面给的是具体码：连不上的 http → 5005，没勾许可的 stdio → 9204。

    注意走的入口是 `POST /api/agent/ask`（AgentRuntime + 工具装配），
    不是 `POST /api/chat/conversations/{id}/messages`——后者走 ChatServiceImpl，不装配任何工具。

    前置：后端已起，且本机能跑 `npx`。stdio 需要 `nexus.mcp.stdio-enabled=true`
    （环境变量 `NEXUS_MCP_STDIO_ENABLED`），否则探到 9204 是正确行为。

        打包：  mvn -o -pl nexus-app -am -DskipTests package
        起服务（profile=local，端口 8081）：
            $env:SPRING_PROFILES_ACTIVE='local'; $env:SERVER_PORT='8081'
            java -Xmx1g "-Dfile.encoding=UTF-8" -jar nexus-app\target\nexus-app-0.0.1-SNAPSHOT.jar

.PARAMETER Base
    后端地址。默认 8081 —— 本机 8080 通常被 deploy/docker-compose.yml 的后端占着，
    那条链路是旧镜像，验不到工作区的改动。

.PARAMETER KeepServer
    跑完不删建出来的 MCP server（默认删掉，免得反复跑攒一堆同名行）。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mcp-e2e.ps1
#>
param(
    [string]$Base = 'http://127.0.0.1:8081',
    [switch]$KeepServer
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }

$script:Failed = 0

function Step([string]$Title) {
    Write-Host ''
    Write-Host "== $Title" -ForegroundColor Cyan
}

function Ok([string]$Message) {
    Write-Host "   OK  $Message" -ForegroundColor Green
}

function Bad([string]$Message) {
    $script:Failed++
    Write-Host "   !!  $Message" -ForegroundColor Red
}

<#
  自己解 UTF-8，不用 Invoke-RestMethod 的隐式解码：
  Spring 回的是 `application/json`（不带 charset），Windows PowerShell 5.1 会按本地代码页解，
  中文错误原因就成了乱码——而"探测失败的具体原因"正是这条脚本要看的东西。
#>
function Api([string]$Method, [string]$Path, $Body, [string]$Token) {
    $headers = @{ Accept = 'application/json' }
    if ($Token) { $headers['Authorization'] = "Bearer $Token" }
    $request = @{ Uri = "$Base$Path"; Method = $Method; Headers = $headers; TimeoutSec = 120; UseBasicParsing = $true }
    if ($null -ne $Body) {
        $request['ContentType'] = 'application/json; charset=utf-8'
        $request['Body'] = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 10 -Compress))
    }

    try {
        $response = Invoke-WebRequest @request
    } catch {
        $web = $_.Exception.Response
        if ($web) {
            $reader = New-Object IO.StreamReader($web.GetResponseStream(), [Text.Encoding]::UTF8)
            $payload = $reader.ReadToEnd()
            throw "$Method $Path 返回 HTTP $([int]$web.StatusCode)：$payload"
        }
        throw
    }

    $text = [Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
    $parsed = $text | ConvertFrom-Json
    if ($parsed.code -ne 0) {
        throw "$Method $Path 失败：code=$($parsed.code) message=$($parsed.message)"
    }
    return $parsed.data
}

# ---------------------------------------------------------------- 0. 健康检查
Step "等待后端 $Base"
$deadline = (Get-Date).AddSeconds(180)
while ($true) {
    try {
        $health = Invoke-RestMethod -Uri "$Base/actuator/health" -TimeoutSec 5
        if ($health.status -eq 'UP') { Ok '后端 UP'; break }
    } catch {
        if ((Get-Date) -gt $deadline) { throw "后端 180 秒内没有起来：$Base" }
    }
    Start-Sleep -Seconds 2
}

# ---------------------------------------------------------------- 1. 注册登录
Step '注册并登录一个干净账号'
$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 8)
$username = "mcp-e2e-$suffix"
$password = 'E2e-Passw0rd!'
$null = Api POST '/api/auth/register' @{
    username = $username
    password = $password
    email    = "$username@example.com"
    nickname = "MCP $suffix"
} $null
$login = Api POST '/api/auth/login' @{ username = $username; password = $password } $null
$token = $login.accessToken
if (-not $token) { throw '登录没有返回 accessToken' }
Ok "用户 $username"

# ---------------------------------------------------------------- 2. 建 stdio server
Step '建一条 stdio MCP server（显式允许在本机执行命令）'
$server = Api POST '/api/mcp-servers' @{
    name       = "Everything $suffix"
    transport  = 'stdio'
    command    = 'npx -y @modelcontextprotocol/server-everything'
    allowStdio = $true
} $token
if (-not $server.allowStdio) { Bad 'allowStdio 没有落成 true：下次探测会被自己的闸门挡住' }
else { Ok "server id=$($server.id) slug=$($server.slug) allowStdio=true" }

# ---------------------------------------------------------------- 3. probe
Step 'probe：真的起子进程、真的拉清单'
$probe = Api POST "/api/mcp-servers/$($server.id)/probe" $null $token
if (-not $probe.ok) {
    Bad "probe 失败：errorCode=$($probe.errorCode) error=$($probe.error)"
} else {
    Ok "probe 成功，$(@($probe.tools).Count) 个工具，耗时 $($probe.tookMs)ms"
    if ($probe.tookMs -gt 20000) { Bad "耗时 $($probe.tookMs)ms 超过 20s 的启动预算" }
    $names = @($probe.tools | ForEach-Object { $_.name })
    if ($names -notcontains 'echo') { Bad "工具清单里没有 echo（拿到的是：$($names -join ', ')）" }
    else { Ok '清单里有 echo' }
    $echo = $probe.tools | Where-Object { $_.name -eq 'echo' } | Select-Object -First 1
    if ($echo.inputSchema -and "$($echo.inputSchema)" -match 'message') { Ok 'echo 的入参 schema 原样带出来了' }
    else { Bad 'echo 的 inputSchema 丢了 message：模型只能瞎猜参数' }
}

# ---------------------------------------------------------------- 4. 落库与注册
Step '清单落库 + 工具注册成 mcp__<slug>__<tool>'
$list = Api GET '/api/mcp-servers' $null $token
$stored = $list | Where-Object { $_.id -eq $server.id } | Select-Object -First 1
if (-not $stored) { throw '列表里找不到刚建的 server' }
if (@($stored.tools).Count -eq 0) { Bad 'server 行上没有工具：只有成功才覆盖的语义被破坏了' }
else { Ok "server 行上有 $(@($stored.tools).Count) 个工具" }
$toolCode = "mcp__$($stored.slug)__echo"

# 能授权就说明注册行存在且启用（grantAlways 会先校验这两件事）
$grant = Api PUT '/api/users/me/tool-grants' @{ toolCode = $toolCode; scope = 'always' } $token
if ($grant.toolCode -eq $toolCode) { Ok "$toolCode 已注册并授权（$($grant.scope)）" }
else { Bad "授权返回的不是这条工具：$($grant.toolCode)" }

# ---------------------------------------------------------------- 5. Agent 提问
Step '让 Agent 真的调一次（/api/agent/ask 走的就是 AgentRuntime + 工具装配）'
$prompt = @'
请调用工具 __TOOL__，参数 message 传 nexus-mcp-ok。
把工具返回的原文一字不改地贴出来，不要改写、不要解释、不要补充。
'@
$prompt = $prompt.Replace('__TOOL__', $toolCode)
$answer = Api POST '/api/agent/ask' @{ question = $prompt } $token
$text = [string]$answer.answer
$calls = @($answer.toolCalls)
Write-Host "   usedTools=$($answer.usedTools) toolCalls=$(($calls | ForEach-Object { $_.tool }) -join ', ')" -ForegroundColor DarkGray
Write-Host "   回答：$text" -ForegroundColor DarkGray

if ($answer.usedTools) { Ok '这次问答真的发生了工具调用' }
else { Bad 'usedTools=false：模型没拿到工具，或者没调' }
$called = $calls | Where-Object { $_.tool -eq $toolCode } | Select-Object -First 1
if ($called) { Ok "调用的就是 $toolCode（status=$($called.status)）" }
else { Bad "工具痕迹里没有 $toolCode" }
if ($text -match 'Echo: nexus-mcp-ok') { Ok '回答里出现了 server 返回的固定文本' }
else { Bad "回答里没有 'Echo: nexus-mcp-ok'：工具调了但结果没回给模型，或者模型没照抄" }

# ---------------------------------------------------------------- 6. 调用被记下来
Step '最近一次调用结果落库（插件页靠它显示"上次调用成没成"）'
$after = Api GET '/api/mcp-servers' $null $token
$calledRow = $after | Where-Object { $_.id -eq $server.id } | Select-Object -First 1
if ($calledRow.lastCallTool -eq 'echo' -and $calledRow.lastCallOk -eq $true) {
    Ok "lastCallTool=echo lastCallOk=true at $($calledRow.lastCallAt)"
} else {
    Bad "调用结果没落库：lastCallTool=$($calledRow.lastCallTool) lastCallOk=$($calledRow.lastCallOk) lastCallError=$($calledRow.lastCallError)"
}

# ---------------------------------------------------------------- 7. 失败面
Step '失败要说清原因（不是一句"探测失败"）'
$broken = Api POST '/api/mcp-servers' @{ name = "Broken $suffix"; transport = 'http'; url = 'http://127.0.0.1:9/mcp' } $token
$brokenProbe = Api POST "/api/mcp-servers/$($broken.id)/probe" $null $token
if ($brokenProbe.ok) { Bad '连不上的 server 居然探测成功' }
elseif (-not $brokenProbe.errorCode) { Bad "失败没有错误码：$($brokenProbe.error)" }
else { Ok "连不上：errorCode=$($brokenProbe.errorCode) error=$($brokenProbe.error)" }

$noConsent = Api POST '/api/mcp-servers' @{
    name      = "NoConsent $suffix"
    transport = 'stdio'
    command   = 'npx -y @modelcontextprotocol/server-everything'
} $token
$consentProbe = Api POST "/api/mcp-servers/$($noConsent.id)/probe" $null $token
if ($consentProbe.ok) { Bad '没勾"允许执行命令"的 server 居然起了子进程' }
elseif ($consentProbe.errorCode -ne 9204) { Bad "没勾许可时错误码是 $($consentProbe.errorCode)，应当是 9204" }
else { Ok "没勾许可：9204 · $($consentProbe.error)" }

# ---------------------------------------------------------------- 8. 收尾
if (-not $KeepServer) {
    foreach ($item in @($server, $broken, $noConsent)) {
        try { $null = Api DELETE "/api/mcp-servers/$($item.id)" $null $token }
        catch { Write-Host "   清理 $($item.id) 失败：$($_.Exception.Message)" -ForegroundColor Yellow }
    }
}

Write-Host ''
if ($script:Failed -eq 0) {
    Write-Host '全链路通过：注册 → 建 server → probe → 注入 → Agent 调用 → 结果回显' -ForegroundColor Green
    exit 0
}
Write-Host "$($script:Failed) 项断言失败" -ForegroundColor Red
exit 1
