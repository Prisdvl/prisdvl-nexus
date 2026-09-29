package com.lky.nexus.knowledge.storage;

import com.lky.nexus.common.util.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 本地磁盘实现。
 *
 * <p><b>文件名策略</b>：不用原始的 {@code sourcePath} 直接拼路径——那等于信任压缩包里的名字
 * （即使前面校验过 `..`，也还要面对 Windows 非法字符、超长路径、大小写不敏感冲突等问题）。
 * 这里把路径"清洗成安全名"：目录分隔符换成 {@code __}，去掉非法字符，
 * 超长时截断并补一段哈希，从而<b>既人类可读、又绝对安全、且天然幂等</b>
 * （同一路径重复导入会覆盖同一个文件，不会产生垃圾）。
 */
@Component
public class LocalDocumentStorage implements DocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalDocumentStorage.class);

    /** Windows 非法文件名字符 + 控制字符。 */
    private static final Pattern UNSAFE_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");

    private static final int MAX_NAME_LENGTH = 120;

    private final Path root;

    public LocalDocumentStorage(@Value("${nexus.storage.root:./data}") String rootDir) {
        this.root = Paths.get(rootDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            log.info("原始文件存储根目录：{}", root);
        } catch (IOException ex) {
            throw new IllegalStateException("无法创建存储目录：" + root, ex);
        }
    }

    @Override
    public String store(Long knowledgeBaseId, String sourcePath, byte[] content) {
        String relative = "kb/" + knowledgeBaseId + "/" + safeName(sourcePath);
        Path target = resolveSafely(relative);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            return relative;
        } catch (IOException ex) {
            throw new IllegalStateException("写入原始文件失败：" + relative, ex);
        }
    }

    @Override
    public byte[] load(String storedPath) {
        if (storedPath == null) {
            return null;
        }
        Path target = resolveSafely(storedPath);
        try {
            return Files.exists(target) ? Files.readAllBytes(target) : null;
        } catch (IOException ex) {
            throw new IllegalStateException("读取原始文件失败：" + storedPath, ex);
        }
    }

    @Override
    public void delete(String storedPath) {
        if (storedPath == null) {
            return;
        }
        try {
            Files.deleteIfExists(resolveSafely(storedPath));
        } catch (IOException ex) {
            log.warn("删除原始文件失败（忽略）：{}", storedPath, ex);
        }
    }

    /**
     * 清洗成安全文件名。
     *
     * <p>超长时截断并追加 {@code sourcePath} 的哈希前缀：仅截断会导致不同长路径互相覆盖，
     * 加哈希后既保留可读前缀又保证唯一。
     */
    private String safeName(String sourcePath) {
        String name = UNSAFE_CHARS.matcher(sourcePath).replaceAll("_").replace('/', '_');
        name = name.replaceAll("_+", "_");
        if (name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH) + "-" + HashUtils.sha256Hex(sourcePath).substring(0, 8);
        }
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * 解析并确认仍在存储根目录之内（纵深防御：即使前面的清洗漏了什么，也不会写到根目录之外）。
     */
    private Path resolveSafely(String relative) {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法的存储路径：" + relative);
        }
        return target;
    }
}
