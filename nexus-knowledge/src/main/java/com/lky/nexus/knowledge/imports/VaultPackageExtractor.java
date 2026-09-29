package com.lky.nexus.knowledge.imports;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.parser.ParserRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Vault 压缩包安全解压器。
 *
 * <p>它是导入链路的第一道关口，职责只有一个：<b>把不可信的压缩包变成可信的文件列表</b>。
 * 具体做四件事：
 * <ol>
 *   <li><b>拒绝非法路径</b>（Zip Slip）：绝对路径、盘符、{@code ..} 一律拒绝并终止整批导入——</li>
 *   <li><b>跳过噪音</b>：隐藏目录（{@code .obsidian}、{@code .trash}、{@code .git}）与不支持的类型；</li>
 *   <li><b>边读边限额</b>：条目数、单文件字节、总解压字节，防 ZIP 炸弹；</li>
 *   <li><b>保留来源信息</b>：相对路径、目录、修改时间（供增量同步与元数据展示）。</li>
 * </ol>
 *
 * <p><b>为什么在内存里解压而不是落盘</b>：第一版面对的是个人笔记（几千个 Markdown，共几 MB），
 * 内存足够且实现简单；限额保证了最坏情况也可控。等 Phase 3 引入 PDF/大附件时，
 * 再改成流式落盘（届时有对象存储与异步 worker），这个取舍写在 notes 里而不是藏在代码里。
 */
@Component
public class VaultPackageExtractor {

    private static final Logger log = LoggerFactory.getLogger(VaultPackageExtractor.class);

    private final ParserRegistry parserRegistry;

    public VaultPackageExtractor(ParserRegistry parserRegistry) {
        this.parserRegistry = parserRegistry;
    }

    public VaultScanResult extract(byte[] zipBytes, VaultScanOptions options) {
        if (zipBytes == null || zipBytes.length == 0) {
            throw new BusinessException(ErrorCode.INVALID_ZIP_STRUCTURE, "压缩包为空");
        }

        List<VaultFile> files = new ArrayList<>();
        List<VaultScanResult.SkippedEntry> skipped = new ArrayList<>();
        int totalEntries = 0;
        long totalBytes = 0;

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                totalEntries++;
                if (totalEntries > options.maxEntries()) {
                    throw new BusinessException(ErrorCode.INVALID_ZIP_STRUCTURE,
                            "压缩包条目数超过上限 %d".formatted(options.maxEntries()));
                }

                String rawName = entry.getName();
                if (entry.isDirectory()) {
                    skipped.add(new VaultScanResult.SkippedEntry(rawName, VaultScanResult.SkipReason.DIRECTORY));
                    continue;
                }

                String path = normalizePath(rawName);   // 非法路径在此直接抛错
                if (isHidden(path)) {
                    skipped.add(new VaultScanResult.SkippedEntry(path, VaultScanResult.SkipReason.HIDDEN));
                    continue;
                }
                String extension = extensionOf(path);
                if (!parserRegistry.supportedExtensions().contains(extension)) {
                    skipped.add(new VaultScanResult.SkippedEntry(path, VaultScanResult.SkipReason.UNSUPPORTED_TYPE));
                    continue;
                }

                byte[] content = readEntry(zip, options, path);
                totalBytes += content.length;
                if (totalBytes > options.maxTotalBytes()) {
                    // 用解压后总字节数判断，而不是压缩包大小 —— 这才是 ZIP 炸弹的真实指标
                    throw new BusinessException(ErrorCode.INVALID_ZIP_STRUCTURE,
                            "解压后总大小超过上限 %d MB".formatted(options.maxTotalBytes() / 1024 / 1024));
                }
                if (content.length == 0) {
                    skipped.add(new VaultScanResult.SkippedEntry(path, VaultScanResult.SkipReason.EMPTY));
                    continue;
                }

                files.add(new VaultFile(path, fileNameOf(path), folderOf(path), content,
                        entry.getLastModifiedTime() == null ? null : entry.getLastModifiedTime().toInstant()));
            }
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.INVALID_ZIP_STRUCTURE, "压缩包解析失败：" + ex.getMessage());
        }

        // 注意：ZipInputStream 面对"不是 ZIP 的字节"并不会抛异常，只是 getNextEntry() 直接返回 null。
        // 如果不显式判断，用户上传一个乱七八糟的文件会得到"导入成功，0 个文件"这种误导性结果。
        if (totalEntries == 0) {
            throw new BusinessException(ErrorCode.INVALID_ZIP_STRUCTURE,
                    "压缩包内没有任何条目（可能不是合法的 ZIP 文件）");
        }

        log.info("Vault 扫描完成：条目 {} 个，可用文件 {} 个，跳过 {} 个，解压总大小 {} KB",
                totalEntries, files.size(), skipped.size(), totalBytes / 1024);
        return new VaultScanResult(stripCommonRoot(files), skipped, totalEntries);
    }

    /**
     * 剥离所有文件共有的顶层目录。
     *
     * <p>为什么需要：导出 Vault 得到的 ZIP 通常是 {@code MyVault/Java/SpringBoot.md} 这种形态，
     * 而 Obsidian 内部的路径是<b>相对 Vault 根</b>的，即 {@code Java/SpringBoot.md}。
     * 若不剥离，库里所有文档都会多出一层 {@code MyVault/} 前缀，
     * 目录过滤（按 folder 检索）与内部链接匹配都会变得别扭。
     *
     * <p>只有在"所有文件确实共享同一个顶层目录"时才剥离；否则原样返回。
     */
    private List<VaultFile> stripCommonRoot(List<VaultFile> files) {
        if (files.isEmpty()) {
            return files;
        }
        String firstPath = files.get(0).sourcePath();
        int slash = firstPath.indexOf('/');
        if (slash <= 0) {
            return files;
        }
        String root = firstPath.substring(0, slash);
        String rootPrefix = root + "/";
        for (VaultFile file : files) {
            if (!file.sourcePath().startsWith(rootPrefix)) {
                return files;
            }
        }
        List<VaultFile> stripped = new ArrayList<>(files.size());
        for (VaultFile file : files) {
            String path = file.sourcePath().substring(rootPrefix.length());
            stripped.add(new VaultFile(path, fileNameOf(path), folderOf(path),
                    file.content(), file.lastModified()));
        }
        log.debug("已剥离 Vault 顶层目录：{}", root);
        return stripped;
    }

    /** 读取单个条目，并在读取过程中执行"单文件大小"限制（防止用超大单文件绕过总量限制）。 */
    private byte[] readEntry(ZipInputStream zip, VaultScanOptions options, String path) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long read = 0;
        int len;
        while ((len = zip.read(chunk)) > 0) {
            read += len;
            if (read > options.maxFileBytes()) {
                throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED,
                        "文件 %s 超过单文件上限 %d MB".formatted(path, options.maxFileBytes() / 1024 / 1024));
            }
            buffer.write(chunk, 0, len);
        }
        return buffer.toByteArray();
    }

    /**
     * 规范化并校验路径。
     *
     * <p>非法路径<b>直接终止整批导入</b>而不是跳过：一个带 {@code ../} 的压缩包，
     * 说明它不是正常的 Obsidian 导出，继续处理没有意义，而且应当让用户看到明确的拒绝原因。
     */
    private String normalizePath(String rawName) {
        String path = rawName.replace('\\', '/');
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (path.startsWith("/") || path.contains(":/")) {
            throw new BusinessException(ErrorCode.INVALID_ZIP_STRUCTURE,
                    "压缩包内含绝对路径，已拒绝导入：" + rawName);
        }
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) {
                throw new BusinessException(ErrorCode.INVALID_ZIP_STRUCTURE,
                        "压缩包内含路径穿越（..），已拒绝导入：" + rawName);
            }
        }
        return path;
    }

    /** 任意一段以点开头即视为隐藏（.obsidian / .trash / .git / .DS_Store）。 */
    private boolean isHidden(String path) {
        for (String segment : path.split("/")) {
            if (segment.startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private String extensionOf(String path) {
        String name = fileNameOf(path);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String fileNameOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private String folderOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? null : path.substring(0, slash);
    }

    /** 供上层把 "Instant" 安全转成 LocalDateTime。 */
    public static java.time.LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null
                : java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
    }

    /** 关闭流（避免 Sonar 之类的静态检查报未关闭）。 */
    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // 关闭失败无需处理
        }
    }
}
