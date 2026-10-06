# ============================================================
# prisdvl-nexus 测试数据清理工具（Test Data Cleanup Utility）
#
# 用途：清理验收脚本留下的一次性测试账号及其名下数据。
#       它不是产品功能、不是管理接口、不给前端使用，
#       只服务：自动化测试 / 本地验收 / CI / 开发环境数据清理。
#
# 为什么需要它：验收脚本每轮都会注册一次性账号，而账号没有删除接口；
#       凭证一丢，普通 API 就再也删不动它们名下的资源，于是残留只会越积越多。
#       本工具是唯一能"越过账号体系"完成级联清理的通道。
#
# 安全设计：
#   * 默认 dry-run（只读、只打印将删清单），必须显式 -Execute 才真删；
#   * Prisdvl（真实数据）与 demo（演示数据）是硬保护名单，永不匹配，
#     且若前缀配置把它们也命中，工具会整体中止而不是静默跳过；
#   * 只处理"前缀白名单"命中的账号，不命中的一律不动——失败模式偏向"少删"而非"多删"；
#   * 删除一律复用后端已测试过的级联（切片/关联边/标签/pgvector 向量/ES 索引/磁盘原文）。
#
# 用法（任意目录均可，脚本会自动定位仓库根）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\cleanup-test-data.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\cleanup-test-data.ps1 -Execute
#
# 参数：
#   -Execute          真正执行删除（默认仅 dry-run）
#   -SkipBuild        跳过 Maven 打包（jar 已是最新时）
#   -JavaHome <path>  指定 JDK 21 目录（默认自动探测 D:\develop\jdk-21）
#   -Profiles <p>     Spring profile，默认 local,cleanup
#   -StorageRoot <p>  写入方使用的存储根目录（容器部署必传，否则磁盘原文不会被删除）
#   -PurgeAccountRows 物理删除账号行（默认只软删 deleted=1，可回溯）
#
# 前置：基础设施需在运行中（deploy/docker-compose.yml 的 mysql/postgres/es/redis）。
#       standalone（H2 内存库）不适用——那里重启即清空，本就没有残留问题。
# ============================================================
[CmdletBinding()]
param(
    [switch]$Execute,
    [switch]$SkipBuild,
    [string]$JavaHome,
    [string]$Profiles = 'local,cleanup',
    # 存储根目录：必须与写入方一致，否则磁盘原文不会被删除（工具会在开工前自检并告知）。
    # 容器部署下后端用 NEXUS_STORAGE_ROOT=/app/data（Docker 卷 backend-data），
    # 此时传入该卷在宿主机上的对应目录即可。
    [string]$StorageRoot,
    # 把账号行**物理删除**（默认只软删 deleted=1）。
    # 软删行会永久累积：实测 141 条，虽不影响功能，但会让 sys_user
    # 混着大量一次性账号、干扰排查真实用户问题。
    [switch]$PurgeAccountRows
)

$ErrorActionPreference = 'Stop'

# 控制台按 UTF-8 输出，避免 Java 侧的中文清单显示成乱码
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$root = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $root 'nexus-app\target\nexus-app-0.0.1-SNAPSHOT.jar'

# JDK 21 探测：项目要求 Java 21，而本机默认 JAVA_HOME 往往是 17
if (-not $JavaHome) {
    foreach ($candidate in @('D:\develop\jdk-21', $env:JAVA_HOME)) {
        if ($candidate -and (Test-Path (Join-Path $candidate 'bin\java.exe'))) {
            $JavaHome = $candidate
            break
        }
    }
}
if (-not $JavaHome) {
    throw 'JDK 21 not found. Pass -JavaHome <path>.'
}
$javaExe = Join-Path $JavaHome 'bin\java.exe'

if (-not $SkipBuild) {
    Write-Host '==> Building nexus-app (skip tests)...'
    $env:JAVA_HOME = $JavaHome
    # 必须 clean：spring-boot:repackage 检测到目标 jar 已是 fat jar 时会直接跳过，
    # 于是"改了代码但 fat jar 没变、内嵌的兄弟模块 jar 还是旧的"，症状是运行期
    # NoClassDefFoundError（踩过：nexus-common 里的新类没进 fat jar）。
    # -am 同样必须：只写 -pl nexus-app 会让兄弟模块从本地仓库取到旧产物。
    & mvn -B -q -Dmaven.test.skip=true -pl nexus-app -am clean package -f (Join-Path $root 'pom.xml')
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed (exit $LASTEXITCODE)." }
}

if (-not (Test-Path $jar)) {
    throw "Jar not found: $jar  (run without -SkipBuild first)"
}

# 强制 UTF-8 输出，保证中文清单在控制台不乱码
$executeFlag = if ($Execute) { 'true' } else { 'false' }

if ($StorageRoot) {
    # nexus.storage.root 与后端 LocalDocumentStorage 用的是同一个属性
    $env:NEXUS_STORAGE_ROOT = $StorageRoot
    Write-Host "存储根目录：$StorageRoot（由 -StorageRoot 指定）" -ForegroundColor Cyan
}

$javaArgs = @(
    '-Dstdout.encoding=UTF-8',
    '-Dfile.encoding=UTF-8',
    '-jar', $jar,
    "--spring.profiles.active=$Profiles",
    "--nexus.cleanup.execute=$executeFlag"
)
if ($PurgeAccountRows) {
    # 与 CleanupProperties.purgeAccountRows 对应
    $javaArgs += '--nexus.cleanup.purge-account-rows=true'
}

if ($Execute) {
    Write-Host ''
    Write-Host '*** EXECUTE MODE：将真实删除数据（软删记录 + 物理级联），不可逆。***' -ForegroundColor Yellow
    Write-Host ''
}

& $javaExe @javaArgs
exit $LASTEXITCODE
