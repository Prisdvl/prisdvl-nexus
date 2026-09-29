package com.lky.nexus.app.knowledge;

import com.lky.nexus.knowledge.imports.VaultFile;
import com.lky.nexus.knowledge.imports.VaultPackageExtractor;
import com.lky.nexus.knowledge.imports.VaultScanOptions;
import com.lky.nexus.knowledge.imports.VaultScanResult;
import com.lky.nexus.knowledge.parser.MarkdownDocumentParser;
import com.lky.nexus.knowledge.parser.ParsedDocument;
import com.lky.nexus.knowledge.parser.ParserRegistry;
import com.lky.nexus.knowledge.parser.TxtDocumentParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定位用测试：单独跑「解压 + 解析」这两步，用与 DocumentQueryTest **完全相同**的 zip 内容。
 *
 * <p>目的：把"frontmatter 标签丢失"的范围钉死在链路的哪一段——
 * 若这里通过，说明解压与解析都没问题，问题在落库或测试环境；
 * 若这里失败，则问题就在解压或解析。
 */
class VaultParseDiagnosticTest {

    private static final ParserRegistry REGISTRY =
            new ParserRegistry(List.of(new MarkdownDocumentParser(), new TxtDocumentParser()));

    @Test
    @DisplayName("解压 + 解析：frontmatter 的 tags 与标题树都应被解析出来")
    void extractAndParseKeepsFrontmatter() throws Exception {
        VaultPackageExtractor extractor = new VaultPackageExtractor(REGISTRY);

        VaultScanResult scan = extractor.extract(zip(), VaultScanOptions.defaults());

        assertThat(scan.files()).hasSize(2);
        VaultFile springBoot = scan.files().stream()
                .filter(file -> file.sourcePath().equals("Java/SpringBoot.md"))
                .findFirst().orElseThrow();

        // 第一道关卡：zip 里的字节原样（换行没有被吃掉、没有 BOM 之类）
        String raw = new String(springBoot.content(), StandardCharsets.UTF_8);
        assertThat(raw).as("解压后的字节应原样保留").startsWith("---\ntags: [java, springboot]\n---");

        // 第二道关卡：解析出的 frontmatter 与标题
        ParsedDocument parsed = REGISTRY.requireParser(springBoot.fileName())
                .parse(springBoot.fileName(), springBoot.content());
        assertThat(parsed.tags()).as("frontmatter 的 tags 应被解析").containsExactlyInAnyOrder("java", "springboot");
        assertThat(parsed.headings()).as("标题应被解析").hasSize(2);
        assertThat(parsed.title()).isEqualTo("Spring Boot");
    }

    /** 与 DocumentQueryTest.vault() 逐字相同的内容。 */
    private byte[] zip() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("Vault/Java/SpringBoot.md", """
                ---
                tags: [java, springboot]
                ---

                # Spring Boot

                ## 自动配置

                条件装配决定加载哪些 Bean。
                """);
        entries.put("Vault/Java/JVM.md", """
                ---
                tags: [java, jvm]
                ---

                # JVM

                ## G1

                Region 划分堆内存。
                """);

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
