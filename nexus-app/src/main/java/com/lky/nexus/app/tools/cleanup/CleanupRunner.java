package com.lky.nexus.app.tools.cleanup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 测试数据清理 CLI 的入口（仅 {@code cleanup} profile 生效）。
 *
 * <p><b>为什么是"命令行工具"而不是接口</b>：清理对象是凭证已丢失的一次性测试账号，
 * 走 HTTP 就得先有 token——恰恰是这些账号没有的东西。因此它必须能"越过账号体系"
 * 直接作用于数据存储。为了不让这种越权能力变成产品的一部分：
 * <ul>
 *   <li>它<b>不是</b> Controller，没有任何 HTTP 端点；</li>
 *   <li>只在 {@code cleanup} profile 下装配，正常运行时代码路径根本不存在；</li>
 *   <li>默认 dry-run，真删必须显式 {@code --nexus.cleanup.execute=true}。</li>
 * </ul>
 *
 * <p>运行方式见 {@code scripts/cleanup-test-data.ps1}。
 */
@Component
@Profile("cleanup")
public class CleanupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CleanupRunner.class);

    private final CleanupProperties properties;
    private final TestDataCleanupService cleanupService;
    private final ConfigurableApplicationContext context;

    public CleanupRunner(CleanupProperties properties,
                         TestDataCleanupService cleanupService,
                         ConfigurableApplicationContext context) {
        this.properties = properties;
        this.cleanupService = cleanupService;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = 0;
        try {
            exitCode = properties.isExecute() ? execute() : dryRun();
        } catch (TestDataCleanupService.CleanupPartialFailureException e) {
            printFailures(e.failures());
            exitCode = 1;
        } catch (Exception e) {
            log.error("清理过程异常终止：{}", e.getMessage(), e);
            exitCode = 2;
        }
        int code = exitCode;
        SpringApplication.exit(context, () -> code);
        System.exit(code);
    }

    private int dryRun() {
        TestDataCleanupService.CleanupPlan plan = cleanupService.plan();
        header("DRY-RUN（只读，未删除任何数据）");
        printStorageRootCheck();
        printPlan(plan);
        System.out.println("无主导入任务明细：" + cleanupService.countOrphanImportTaskItems()
                + " 行（父任务已不存在，实际执行时会一并回收）");
        System.out.println();
        System.out.println("本次为 dry-run。要真正执行删除，追加参数：--nexus.cleanup.execute=true");
        System.out.println("账号处理方式："
                + (cleanupService.purgeAccountRows()
                    ? "**物理删除** sys_user 行（不可恢复）"
                    : "软删（deleted=1，可回溯）；要物理删除加 --nexus.cleanup.purge-account-rows=true"));
        footer();
        return 0;
    }

    /**
     * 存储根前置校验：不可用就明确告知"磁盘原文本次不会被删除"。
     *
     * <p>这一步存在的意义：容器化部署下后端写的是容器内路径（如 /app/data，挂在 Docker 卷上），
     * 而本工具默认在宿主机用 ./data——两者不同目录时，磁盘删除会静默失效。
     * 与其等跑完再看警告，不如开工前就把话说清楚。
     */
    private void printStorageRootCheck() {
        TestDataCleanupService.StorageRootCheck check = cleanupService.checkStorageRoot();
        System.out.println("存储根自检：" + (check.usable() ? "可用 ✓" : "不可用 ✗"));
        System.out.println("  存储根目录：" + check.storageRoot());
        System.out.println("  抽样核对：库中 " + check.sampledPaths() + " 条 stored_path，"
                + "在存储根下找到 " + check.foundPaths() + " 条");
        if (!check.usable()) {
            for (String reason : check.reasons()) {
                System.out.println("  ! " + reason);
            }
            System.out.println("  → 本次将只清理数据库（切片/向量/ES/文档行），磁盘原文不会被删除。");
            System.out.println("  → 若要一并删除磁盘原文：用 -StorageRoot 指向写入方的存储根目录，"
                    + "例如容器部署下 Docker 卷 backend-data 的宿主机挂载点。");
        }
        System.out.println();
    }

    private int execute() {
        TestDataCleanupService.CleanupPlan plan = cleanupService.plan();
        header("EXECUTE（将真实删除下列数据，不可逆）");
        printStorageRootCheck();
        printPlan(plan);
        System.out.println();

        // 最终一致性回收必须放在"候选账号为 0 就返回"之前：它处理的是"父行早就没了"的存量无主行，
        // 与这一轮有没有候选账号无关。放在后面的话，一旦某轮没有可清理的账号，
        // 这些谁都枚举不到的残留就永远等不到回收（真机第一次跑就撞上了：这轮候选账号为 0，
        // 而库里正躺着 69 行无主明细）。
        int orphansReclaimed = cleanupService.reclaimOrphanImportTaskItems();

        // 历史残留的软删账号：**独立于活跃候选**处理。
        // 它们没有资源可清（软删那一刻已经清过），但如果 purgeAccountRows 开着，
        // 就应该把行也删掉——否则"清一次少一批"永远追不上 E2E 的新增。
        int purgedHistory = 0;
        if (!plan.alreadyDeleted().isEmpty() && cleanupService.purgeAccountRows()) {
            purgedHistory = cleanupService.purgeAlreadyDeletedRows(plan.alreadyDeleted());
            System.out.printf("历史残留软删账号：物理删除 %d 个账号（%d 行）%n",
                    plan.alreadyDeleted().size(), purgedHistory);
        } else if (!plan.alreadyDeleted().isEmpty()) {
            System.out.printf("历史残留软删账号 %d 个（未物理删除；加 --nexus.cleanup.purge-account-rows=true 可清）%n",
                    plan.alreadyDeleted().size());
        }

        if (plan.targets().isEmpty()) {
            System.out.println("活跃候选账号为 0，无需级联清理。");
            System.out.printf("  最终一致性回收：无主导入任务明细 %d 行%n", orphansReclaimed);
            footer();
            return 0;
        }

        System.out.println("开始清理…");
        List<TestDataCleanupService.PurgeResult> results = cleanupService.purge(plan);

        int kbs = results.stream().mapToInt(TestDataCleanupService.PurgeResult::knowledgeBases).sum();
        int convs = results.stream().mapToInt(TestDataCleanupService.PurgeResult::conversations).sum();
        int agents = results.stream().mapToInt(TestDataCleanupService.PurgeResult::agents).sum();
        int builtin = results.stream().mapToInt(TestDataCleanupService.PurgeResult::builtinAgents).sum();
        int ws = results.stream().mapToInt(TestDataCleanupService.PurgeResult::workspaces).sum();
        int tasks = results.stream().mapToInt(TestDataCleanupService.PurgeResult::importTasks).sum();
        int qa = results.stream().mapToInt(TestDataCleanupService.PurgeResult::qaRecords).sum();
        int memory = results.stream().mapToInt(TestDataCleanupService.PurgeResult::chatMemoryRows).sum();
        int storedFiles = results.stream().mapToInt(TestDataCleanupService.PurgeResult::storedFiles).sum();
        int storedFound = results.stream().mapToInt(TestDataCleanupService.PurgeResult::storedFilesFound).sum();
        int storedUnreadable = results.stream()
                .mapToInt(TestDataCleanupService.PurgeResult::storedFilesUnreadable).sum();

        System.out.println();
        int purgedUsers = results.stream().mapToInt(TestDataCleanupService.PurgeResult::purgedUsers).sum();
        System.out.println("清理完成：账号 " + results.size() + " 个"
                + (purgedUsers > 0
                    ? "（**物理删除** " + purgedUsers + " 行）"
                    : "（软删 deleted=1；如需物理删除请加 --nexus.cleanup.purge-account-rows=true）"));
        System.out.printf("  知识库 %d（含切片/关联边/标签/向量/ES索引/磁盘原文级联）%n", kbs);
        System.out.printf("  会话 %d；会话记忆 %d 行%n", convs, memory);
        System.out.printf("  自建 Agent %d；内置 Agent %d%n", agents, builtin);
        System.out.printf("  工作区 %d；导入任务 %d 条；问答记录 %d 条%n", ws, tasks, qa);
        System.out.printf("  最终一致性回收：无主导入任务明细 %d 行%n", orphansReclaimed);
        if (storedFiles > 0) {
            System.out.printf("  原始文件 %d 个（本进程存储根目录下命中 %d 个）%n", storedFiles, storedFound);
        }
        if (storedUnreadable > 0) {
            System.out.printf("  注意：有 %d 条 stored_path 含非法路径字符，未能核对磁盘存在性"
                    + "（不影响数据库级联，仅无法判断磁盘原文是否删除）%n", storedUnreadable);
        }
        if (storedFiles > 0 && storedFound == 0) {
            System.out.println();
            System.out.println("!! 警告：库里有 " + storedFiles + " 个原始文件记录，但在本进程的存储根目录下"
                    + "一个都没找到。");
            System.out.println("   本进程存储根目录：" + cleanupService.storageRoot());
            System.out.println("   这意味着磁盘原文本次没有被删除（该步失败是被静默吞掉的）。");
            System.out.println("   若服务跑在容器里，请用 -StorageRoot 指定写入方的存储根，"
                    + "或用 NEXUS_STORAGE_ROOT 指向它（例如容器部署下为 Docker 卷 backend-data 的挂载点）。");
        }
        footer();
        return 0;
    }

    private void printPlan(TestDataCleanupService.CleanupPlan plan) {
        System.out.println("硬保护账号（永不匹配）：" + String.join(", ", plan.protectedUsernames()));
        System.out.println("匹配前缀：" + String.join(", ", plan.prefixes()));
        System.out.println("原始文件存储根目录：" + cleanupService.storageRoot()
                + "（必须与写入方一致，否则磁盘原文不会被真正删除）");
        System.out.println();
        System.out.println("候选账号 " + plan.targets().size() + " 个：");
        if (!plan.alreadyDeleted().isEmpty()) {
            System.out.println("历史残留（已软删）" + plan.alreadyDeleted().size() + " 个："
                    + (cleanupService.purgeAccountRows() ? "将被物理删除" : "保持软删"));
        }
        System.out.printf("  %-32s %6s %6s %6s %6s %6s %6s %6s%n",
                "username", "kb", "conv", "agent", "bAgt", "ws", "task", "qa");
        System.out.println("  （agent = 自建 Agent；bAgt = 内置 Agent，默认一并清理，"
                + "可用 nexus.cleanup.purge-builtin-agents=false 保留）");
        for (TestDataCleanupService.AccountSnapshot t : plan.targets()) {
            System.out.printf("  %-32s %6d %6d %6d %6d %6d %6d %6d%n",
                    t.username(), t.knowledgeBases(), t.conversations(), t.agents(),
                    t.builtinAgents(), t.workspaces(), t.importTasks(), t.qaRecords());
        }
        System.out.println();
        Map<String, Integer> summary = plan.summary();
        StringBuilder line = new StringBuilder("合计将删除：");
        summary.forEach((key, value) -> line.append(key).append(" ").append(value).append("；"));
        System.out.println(line);
        System.out.println("（知识库删除会级联清空切片 / 关联边 / 标签 / pgvector 向量 / ES 索引 / 磁盘原文）");
    }

    private void printFailures(Map<String, String> failures) {
        System.out.println();
        System.out.println("!! 有 " + failures.size() + " 个账号清理失败（其余账号已处理）：");
        failures.forEach((username, reason) -> System.out.println("   - " + username + "：" + reason));
        footer();
    }

    private void header(String mode) {
        System.out.println();
        System.out.println("================================================================");
        System.out.println(" prisdvl-nexus 测试数据清理工具 —— " + mode);
        System.out.println(" 作用域：仅测试账号（前缀白名单）；Prisdvl / demo 硬保护");
        System.out.println("================================================================");
    }

    private void footer() {
        System.out.println("================================================================");
    }
}
