package com.lky.nexus.knowledge.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 导入暂存区：上传的 ZIP 先落到这里，消息里只传 taskId。
 *
 * <p><b>为什么不把 ZIP 直接塞进消息</b>：一个 Vault 压缩包可能几十 MB，
 * 而消息中间件不是文件传输通道——超大消息会撑爆 broker 内存与队列、
 * 触发 max_message_size 限制，而且在消费者出错重试时会被反复投递。
 * 正确做法是"文件落盘 + 消息只传引用"，这也是消息队列的通用实践。
 *
 * <p>生命周期：入队时写入 → 消费时读取 → <b>任务完全成功后才删除</b>。
 * 部分失败时保留，以便重试接口直接复用同一个压缩包（用户不必再传一次）。
 */
@Component
public class ImportStagingStore {

    private static final Logger log = LoggerFactory.getLogger(ImportStagingStore.class);

    private final Path root;

    public ImportStagingStore(@Value("${nexus.storage.root:./data}") String rootDir) {
        this.root = Paths.get(rootDir).toAbsolutePath().normalize().resolve("staging");
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new IllegalStateException("无法创建导入暂存目录：" + root, ex);
        }
    }

    /** 保存上传的压缩包，返回暂存路径。 */
    public String store(Long taskId, byte[] content) {
        Path target = resolve(taskId);
        try {
            Files.write(target, content);
            log.debug("导入暂存写入 taskId={}, size={} KB", taskId, content.length / 1024);
            return target.toString();
        } catch (IOException ex) {
            throw new IllegalStateException("写入导入暂存失败：taskId=" + taskId, ex);
        }
    }

    /** 读取暂存压缩包；不存在返回 null（任务会被标记为失败而不是抛异常）。 */
    public byte[] load(Long taskId) {
        Path target = resolve(taskId);
        try {
            return Files.exists(target) ? Files.readAllBytes(target) : null;
        } catch (IOException ex) {
            throw new IllegalStateException("读取导入暂存失败：taskId=" + taskId, ex);
        }
    }

    public boolean exists(Long taskId) {
        return Files.exists(resolve(taskId));
    }

    /** 删除暂存文件（任务成功后调用；不存在则忽略）。 */
    public void delete(Long taskId) {
        try {
            boolean deleted = Files.deleteIfExists(resolve(taskId));
            if (deleted) {
                log.debug("导入暂存已清理 taskId={}", taskId);
            }
        } catch (IOException ex) {
            log.warn("清理导入暂存失败（忽略）taskId={}", taskId, ex);
        }
    }

    private Path resolve(Long taskId) {
        // taskId 是雪花 id（纯数字），不存在路径穿越风险；仍做一次 normalize + 前缀校验作为纵深防御
        Path target = root.resolve(taskId + ".zip").normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法的暂存路径：taskId=" + taskId);
        }
        return target;
    }
}
