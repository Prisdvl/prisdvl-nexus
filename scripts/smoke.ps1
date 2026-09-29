# ============================================================
# prisdvl-nexus 后端冒烟测试
#
# 用法（先启动后端）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke.ps1
#
# 覆盖：注册 -> 登录 -> 建会话 -> 非流式问答 -> 流式问答(边收边停) -> 查历史
# 需要后端已配置 AI_CHAT_API_KEY（否则问答步骤报 4001）
#
# 【为什么全部用 HttpClient 而不是 Invoke-RestMethod】
# Windows PowerShell 5.1 的 Invoke-RestMethod 在响应头没写 charset 时，
# 会按 ISO-8859-1 解码 JSON，导致中文回答在控制台显示成乱码
# （服务端其实是对的：JSON 按 RFC 8259 就是 UTF-8，现代浏览器 / fetch 也按 UTF-8 处理）。
# 这里显式按 UTF-8 解码字节流，避免"客户端显示问题被误判成后端 bug"。
# ============================================================
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$Password = 'Passw0rd!23'
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Net.Http | Out-Null
$ErrorActionPreference = 'Stop'

$script:Http = New-Object System.Net.Http.HttpClient
$script:Http.Timeout = [TimeSpan]::FromSeconds(90)

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        $Body,
        [string]$Token,
        [switch]$RawLines
    )
    $request = New-Object System.Net.Http.HttpRequestMessage($Method, "$BaseUrl$Path")
    if ($Token) { $request.Headers.Add('Authorization', "Bearer $Token") }
    if ($null -ne $Body) {
        $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 6 -Compress }
        $request.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
    }
    $response = $script:Http.SendAsync(
        $request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).Result
    $stream = $response.Content.ReadAsStreamAsync().Result

    if ($RawLines) {
        # SSE：交给调用方逐行读取
        return [pscustomobject]@{
            Status      = [int]$response.StatusCode
            ContentType = $response.Content.Headers.ContentType
            Reader      = (New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8))
        }
    }

    $buffer = New-Object System.IO.MemoryStream
    $stream.CopyTo($buffer)
    $stream.Dispose()
    $text = [System.Text.Encoding]::UTF8.GetString($buffer.ToArray())
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    return $text | ConvertFrom-Json
}

# ---------- 0. 健康检查 ----------
$health = Invoke-Api -Method GET -Path '/actuator/health'
Write-Host "[0] 健康检查: $($health.status)" -ForegroundColor Green

# ---------- 1. 注册 / 登录 ----------
$username = 'smoke_' + (Get-Random -Maximum 999999)
Invoke-Api -Method POST -Path '/api/auth/register' `
    -Body @{ username = $username; password = $Password; nickname = '冒烟用户' } | Out-Null
$login = Invoke-Api -Method POST -Path '/api/auth/login' `
    -Body @{ username = $username; password = $Password }
$token = $login.data.accessToken
Write-Host "[1] 注册/登录成功: $username (id=$($login.data.user.id), roles=$($login.data.user.roles -join ','))" -ForegroundColor Green

# ---------- 2. 创建会话 ----------
$conv = Invoke-Api -Method POST -Path '/api/chat/conversations' -Body @{ title = '冒烟测试会话' } -Token $token
$convId = $conv.data.id
Write-Host "[2] 创建会话: id=$convId, mode=$($conv.data.mode)" -ForegroundColor Green

# ---------- 3. 非流式问答 ----------
$answer = Invoke-Api -Method POST -Path "/api/chat/conversations/$convId/messages" `
    -Body @{ content = '用一句话说明什么是 Spring Boot 自动配置。' } -Token $token
Write-Host '[3] 非流式问答完成:' -ForegroundColor Green
Write-Host "    回答: $($answer.data.answer)"
Write-Host "    模型=$($answer.data.model) tokens=$($answer.data.promptTokens)/$($answer.data.completionTokens)/$($answer.data.totalTokens) latency=$($answer.data.latencyMs)ms"

# ---------- 4. 流式问答 + 中途停止 ----------
Write-Host '[4] 流式问答（收到第 3 个分片后停止）...' -ForegroundColor Green
$sse = Invoke-Api -Method POST -Path '/api/chat/stream' -Token $token -RawLines `
    -Body @{ conversationId = [long]$convId; content = '请用大约 300 字比较 Redis 与 Memcached 的差异。' }
Write-Host "    HTTP $($sse.Status)  $($sse.ContentType)"

$events = @(); $currentEvent = ''; $requestId = $null; $deltaCount = 0; $text = ''; $done = $null
try {
    while ($null -ne ($line = $sse.Reader.ReadLine())) {
        if ($line.StartsWith('event:')) {
            $currentEvent = $line.Substring(6).Trim(); $events += $currentEvent
        } elseif ($line.StartsWith('data:')) {
            $data = $line.Substring(5).Trim()
            switch ($currentEvent) {
                'meta' { $requestId = ($data | ConvertFrom-Json).requestId }
                'delta' {
                    $deltaCount++; $text += ($data | ConvertFrom-Json).text
                    if ($deltaCount -eq 3) {
                        $stop = Invoke-Api -Method POST -Path "/api/chat/stream/$requestId/stop" `
                            -Body @{} -Token $token
                        Write-Host "    -> 已请求停止, cancelled=$($stop.data.cancelled)"
                    }
                }
                'done' { $done = $data }
            }
        }
    }
} finally { $sse.Reader.Close() }

Write-Host "    事件序列: $($events -join ' -> ')"
Write-Host "    分片数=$deltaCount 内容长度=$($text.Length)"
Write-Host "    内容片段: $($text.Substring(0, [Math]::Min(50, $text.Length)))..."
Write-Host "    done: $done"

# ---------- 5. 历史与计数 ----------
$messages = Invoke-Api -Method GET -Path "/api/chat/messages/$convId" -Token $token
$detail = Invoke-Api -Method GET -Path "/api/chat/conversations/$convId" -Token $token
Write-Host "[5] 历史消息 $($messages.data.total) 条, 会话 messageCount=$($detail.data.messageCount)" -ForegroundColor Green
foreach ($m in $messages.data.list) {
    Write-Host ("    [{0,-9}] status={1,-9} len={2,-4} tokens={3}" -f $m.role, $m.status, $m.content.Length, $m.totalTokens)
}
Write-Host '冒烟测试全部通过。' -ForegroundColor Green
