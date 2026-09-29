package com.lky.nexus.knowledge.imports;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.parser.MarkdownDocumentParser;
import com.lky.nexus.knowledge.parser.ParserRegistry;
import com.lky.nexus.knowledge.parser.TxtDocumentParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Vault 安全解压测试（纯单元测试）。
 *
 * <p>这是导入链路的第一道安全关口，因此测试重点不是"能不能解压"，
 * 而是<b>面对恶意压缩包时的行为</b>：路径穿越、ZIP 炸弹、超大单文件、条目数炸弹。
 * 这些场景在真实世界里都出现过，且一旦漏掉后果严重（覆盖系统文件 / 打满磁盘与内存）。
 */
class VaultPackageExtractorTest {

    private final ParserRegistry parserRegistry =
            new ParserRegistry(List.of(new MarkdownDocumentParser(), new TxtDocumentParser()));
    private final VaultPackageExtractor extractor = new VaultPackageExtractor(parserRegistry);

    @Test
    @DisplayName("正常 Vault：剥离顶层目录、保留相对路径与目录层级")
    void extractsVaultAndStripsRootFolder() throws Exception {
        byte[] zip = zip(Map.of(
                "MyVault/Java/SpringBoot.md", "# Spring Boot\n\n自动配置说明。",
                "MyVault/Java/JVM/GC.md", "# GC\n\n垃圾回收。",
                "MyVault/README.txt", "根目录说明"));

        VaultScanResult result = extractor.extract(zip, VaultScanOptions.defaults());

        assertThat(result.files()).hasSize(3);
        assertThat(result.files()).extracting(VaultFile::sourcePath)
                .containsExactlyInAnyOrder("Java/SpringBoot.md", "Java/JVM/GC.md", "README.txt");
        assertThat(result.files()).filteredOn(f -> f.sourcePath().equals("Java/JVM/GC.md"))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.fileName()).isEqualTo("GC.md");
                    assertThat(f.folder()).isEqualTo("Java/JVM");
                    assertThat(f.extension()).isEqualTo("md");
                });
    }

    @Test
    @DisplayName("跳过噪音：隐藏目录/文件、不支持的类型、空文件、目录条目")
    void skipsNoiseEntries() throws Exception {
        byte[] zip = zip(new LinkedHashMap<>(Map.of(
                "Vault/note.md", "# 笔记",
                "Vault/.obsidian/workspace.json", "{}",
                "Vault/.trash/old.md", "被删除的笔记",
                "Vault/.DS_Store", "junk",
                "Vault/Attachments/diagram.png", "binary",
                "Vault/empty.md", "")));

        VaultScanResult result = extractor.extract(zip, VaultScanOptions.defaults());

        assertThat(result.files()).extracting(VaultFile::sourcePath).containsExactly("note.md");
        assertThat(result.skipped()).hasSize(5);
        assertThat(result.skipped()).extracting(VaultScanResult.SkippedEntry::reason)
                .contains(VaultScanResult.SkipReason.HIDDEN,
                        VaultScanResult.SkipReason.UNSUPPORTED_TYPE,
                        VaultScanResult.SkipReason.EMPTY);
    }

    @Test
    @DisplayName("Zip Slip：含 ../ 的路径直接拒绝整批导入（不静默跳过）")
    void rejectsPathTraversal() throws Exception {
        byte[] zip = zip(Map.of("../evil.md", "# 恶意文件"));

        assertThatThrownBy(() -> extractor.extract(zip, VaultScanOptions.defaults()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("路径穿越")
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.INVALID_ZIP_STRUCTURE.getCode());
    }

    @Test
    @DisplayName("Zip Slip：嵌套的 ../../ 同样被拒绝")
    void rejectsNestedPathTraversal() throws Exception {
        byte[] zip = zip(Map.of("Vault/sub/../../../etc/passwd", "x"));

        assertThatThrownBy(() -> extractor.extract(zip, VaultScanOptions.defaults()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("路径穿越");
    }

    @Test
    @DisplayName("Zip Slip：绝对路径被拒绝")
    void rejectsAbsolutePath() throws Exception {
        byte[] zip = zip(Map.of("/etc/passwd", "x"));

        assertThatThrownBy(() -> extractor.extract(zip, VaultScanOptions.defaults()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("绝对路径");
    }

    @Test
    @DisplayName("ZIP 炸弹：解压后总量超限被拒绝（看解压后字节，而不是压缩包大小）")
    void rejectsZipBombByTotalSize() throws Exception {
        // 高度可压缩的重复内容：压缩包很小，解压后很大
        byte[] zip = zip(Map.of("big.md", "A".repeat(200_000)));
        VaultScanOptions tiny = new VaultScanOptions(100, 10L * 1024 * 1024, 100_000);

        assertThatThrownBy(() -> extractor.extract(zip, tiny))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("解压后总大小超过上限");
    }

    @Test
    @DisplayName("单文件超限：读的过程中就会中断（不必先读完再判断）")
    void rejectsOversizedSingleFile() throws Exception {
        byte[] zip = zip(Map.of("big.md", "A".repeat(10_000)));
        VaultScanOptions tiny = new VaultScanOptions(100, 1_000, 10L * 1024 * 1024);

        assertThatThrownBy(() -> extractor.extract(zip, tiny))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超过单文件上限")
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.FILE_SIZE_EXCEEDED.getCode());
    }

    @Test
    @DisplayName("条目数炸弹：超过上限被拒绝")
    void rejectsTooManyEntries() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            entries.put("Vault/f" + i + ".md", "内容 " + i);
        }
        byte[] zip = zip(entries);
        VaultScanOptions tiny = new VaultScanOptions(3, 1_000_000, 10_000_000);

        assertThatThrownBy(() -> extractor.extract(zip, tiny))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("条目数超过上限");
    }

    @Test
    @DisplayName("空压缩包 / 非 ZIP 内容：报 3007，且不会把垃圾文件当成'成功导入 0 篇'")
    void handlesInvalidInput() {
        assertThatThrownBy(() -> extractor.extract(new byte[0], VaultScanOptions.defaults()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("压缩包为空");

        // 注意：ZipInputStream 遇到非 ZIP 字节不会抛异常，只是读不到条目；
        // 因此这里断言的是"显式拒绝"，而不是依赖底层异常
        assertThatThrownBy(() -> extractor.extract("not a zip".getBytes(StandardCharsets.UTF_8),
                VaultScanOptions.defaults()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有任何条目")
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.INVALID_ZIP_STRUCTURE.getCode());
    }

    @Test
    @DisplayName("压缩包内文件不共享顶层目录时，不做剥离（避免误砍真实目录）")
    void doesNotStripWhenNoCommonRoot() throws Exception {
        byte[] zip = zip(Map.of(
                "Java/A.md", "# A",
                "Redis/B.md", "# B"));

        VaultScanResult result = extractor.extract(zip, VaultScanOptions.defaults());

        assertThat(result.files()).extracting(VaultFile::sourcePath)
                .containsExactlyInAnyOrder("Java/A.md", "Redis/B.md");
    }

    // ------------------------------------------------------------------

    private byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
