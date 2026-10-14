package com.lky.nexus.knowledge.imports;

import com.lky.nexus.knowledge.model.entity.DocumentPO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 同步计划器：用 {@code (kb_id, source_path)} + {@code file_hash} 把"库里已有的"与"本次扫描到的"对比出四类。
 *
 * <p>它刻意<b>不解析正文、也不访问数据库</b>——输入输出都是普通对象，
 * 因此可以用纯单元测试把各种边界（改名、只改元数据、整批删除）覆盖干净。
 * 这也是"把判断逻辑从编排流程里抽出来"的实际收益：编排里剩下的只是"照着计划做事"。
 *
 * <p><b>删除判定的前提</b>：只有当本次导入是"完整的 Vault 同步"（{@code detectDeletions=true}）时，
 * "清单里没有"才等于"文件被删了"。单文件上传时清单只有一个文件，
 * 若也按此判定就会把库里其余文档全部误删——这是增量同步最危险的失误点。
 */
@Component
public class VaultSyncPlanner {

    /**
     * 对比"库里已有的"与"本次扫描到的"，得出四类结果。
     *
     * <p>判定顺序：路径不在库里 → {@code added}；路径在库里且 {@code file_hash} 一致 →
     * {@code unchanged}；路径在库里但哈希不同 → {@code changed}；库里有而清单没有 →
     * 仅当 {@code detectDeletions=true} 时计入 {@code missing}（否则视为未删除）。
     *
     * @param existingDocuments 该知识库现有文档（来自数据库）
     * @param scannedFiles      本次扫描出的文件（含各自 file_hash）
     * @param detectDeletions   是否允许判定删除（仅完整 Vault 同步时为 true）
     * @return 四类分类结果；{@code missing} 在 {@code detectDeletions=false} 时为空
     */
    public VaultSyncPlan plan(List<DocumentPO> existingDocuments,
                              List<ScannedFile> scannedFiles,
                              boolean detectDeletions) {
        Map<String, DocumentPO> existingByPath = new HashMap<>();
        for (DocumentPO document : existingDocuments) {
            existingByPath.put(document.getSourcePath(), document);
        }

        List<ScannedFile> unchanged = new ArrayList<>();
        List<ScannedFile> changed = new ArrayList<>();
        List<ScannedFile> added = new ArrayList<>();

        for (ScannedFile scanned : scannedFiles) {
            DocumentPO existing = existingByPath.remove(scanned.file().sourcePath());
            if (existing == null) {
                added.add(scanned);
            } else if (scanned.fileHash().equals(existing.getFileHash())) {
                // 字节完全一致 → 连解析都不需要
                unchanged.add(scanned);
            } else {
                changed.add(scanned);
            }
        }

        // 还剩在 map 里的就是"库里有、清单里没有"
        List<DocumentPO> missing = detectDeletions ? new ArrayList<>(existingByPath.values()) : List.of();
        return new VaultSyncPlan(unchanged, changed, added, missing);
    }

    /**
     * 按 {@code file_hash} 把"新增路径"与"消失文档"配对，识别改名。
     *
     * @param missingByHash 可变映射：被认领的条目会从中移除，剩余的就是真正要删除的
     * @return 与 {@code scanned} 配对的旧文档；没有则返回 null
     */
    public DocumentPO claimRename(Map<String, DocumentPO> missingByHash, ScannedFile scanned) {
        return missingByHash.remove(scanned.fileHash());
    }

    /** 构建"file_hash → 旧文档"的映射（同一个哈希理论上只对应一个文档；重复时保留第一个）。 */
    public Map<String, DocumentPO> indexByFileHash(List<DocumentPO> documents) {
        Map<String, DocumentPO> byHash = new HashMap<>();
        for (DocumentPO document : documents) {
            if (document.getFileHash() != null) {
                byHash.putIfAbsent(document.getFileHash(), document);
            }
        }
        return byHash;
    }
}
