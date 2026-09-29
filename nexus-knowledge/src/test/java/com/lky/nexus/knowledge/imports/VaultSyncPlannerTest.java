package com.lky.nexus.knowledge.imports;

import com.lky.nexus.knowledge.model.entity.DocumentPO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 增量同步规划器测试（纯单元测试，不碰数据库、不解析正文）。
 *
 * <p>把它做成纯函数是刻意的：增量同步的判断逻辑最容易出错（漏判 = 该更新的没更新、
 * 误判 = 把没变的重新嵌入一遍，或者更糟——把不该删的删掉），
 * 而纯函数可以用极低的成本把边界穷举干净。
 */
class VaultSyncPlannerTest {

    private final VaultSyncPlanner planner = new VaultSyncPlanner();

    @Test
    @DisplayName("字节完全一致 → 归入 unchanged（不解析、不写库）")
    void detectsUnchangedFiles() {
        DocumentPO existing = document("Java/A.md", "hash-a");

        VaultSyncPlan plan = planner.plan(List.of(existing),
                List.of(scanned("Java/A.md", "hash-a")), true);

        assertThat(plan.unchanged()).hasSize(1);
        assertThat(plan.changed()).isEmpty();
        assertThat(plan.added()).isEmpty();
        assertThat(plan.missing()).isEmpty();
        assertThat(plan.totalScanned()).isEqualTo(1);
    }

    @Test
    @DisplayName("库里没有的新路径 → added")
    void detectsAddedFiles() {
        VaultSyncPlan plan = planner.plan(List.of(document("Java/A.md", "hash-a")),
                List.of(scanned("Java/A.md", "hash-a"), scanned("Redis/B.md", "hash-b")), true);

        assertThat(plan.added()).extracting(s -> s.file().sourcePath()).containsExactly("Redis/B.md");
    }

    @Test
    @DisplayName("路径相同但 file_hash 变了 → changed（需解析后才能细分）")
    void detectsChangedFiles() {
        VaultSyncPlan plan = planner.plan(List.of(document("Java/A.md", "hash-old")),
                List.of(scanned("Java/A.md", "hash-new")), true);

        assertThat(plan.changed()).hasSize(1);
        assertThat(plan.unchanged()).isEmpty();
    }

    @Test
    @DisplayName("库里有、清单里没有 → missing（完整同步时可判定为删除）")
    void detectsMissingFiles() {
        VaultSyncPlan plan = planner.plan(
                List.of(document("Java/A.md", "hash-a"), document("Redis/gone.md", "hash-gone")),
                List.of(scanned("Java/A.md", "hash-a")), true);

        assertThat(plan.missing()).extracting(DocumentPO::getSourcePath).containsExactly("Redis/gone.md");
    }

    @Test
    @DisplayName("不做删除判定时（例如单文件上传）missing 必须为空——否则会误删库里其余文档")
    void neverDetectsDeletionsWhenDisabled() {
        VaultSyncPlan plan = planner.plan(
                List.of(document("Java/A.md", "hash-a"), document("Redis/B.md", "hash-b")),
                List.of(scanned("Java/C.md", "hash-c")), false);

        assertThat(plan.missing()).isEmpty();
        assertThat(plan.added()).extracting(s -> s.file().sourcePath()).containsExactly("Java/C.md");
    }

    @Test
    @DisplayName("改名识别：新路径的 hash 命中消失文档的 hash → 认领成功并从待删集合移除")
    void claimsRenameByHash() {
        DocumentPO gone = document("Redis/old.md", "same-hash");
        Map<String, DocumentPO> missingByHash = planner.indexByFileHash(List.of(gone));
        ScannedFile added = scanned("Redis/new.md", "same-hash");

        DocumentPO claimed = planner.claimRename(missingByHash, added);

        assertThat(claimed).isSameAs(gone);
        assertThat(missingByHash).as("被认领的文档不应再被删除").isEmpty();
    }

    @Test
    @DisplayName("改名识别：hash 不匹配则认领失败，文档留在待删集合里")
    void doesNotClaimWhenHashDiffers() {
        Map<String, DocumentPO> missingByHash = planner.indexByFileHash(
                List.of(document("Redis/old.md", "hash-old")));

        DocumentPO claimed = planner.claimRename(missingByHash, scanned("Redis/new.md", "hash-new"));

        assertThat(claimed).isNull();
        assertThat(missingByHash).hasSize(1);
    }

    @Test
    @DisplayName("多个文件同时变化：四类判定互不干扰")
    void classifiesMixedBatch() {
        List<DocumentPO> existing = List.of(
                document("keep.md", "h1"),
                document("modified.md", "h2"),
                document("deleted.md", "h3"));

        VaultSyncPlan plan = planner.plan(existing, List.of(
                scanned("keep.md", "h1"),
                scanned("modified.md", "h2-new"),
                scanned("added.md", "h4")), true);

        assertThat(plan.unchanged()).extracting(s -> s.file().sourcePath()).containsExactly("keep.md");
        assertThat(plan.changed()).extracting(s -> s.file().sourcePath()).containsExactly("modified.md");
        assertThat(plan.added()).extracting(s -> s.file().sourcePath()).containsExactly("added.md");
        assertThat(plan.missing()).extracting(DocumentPO::getSourcePath).containsExactly("deleted.md");
    }

    // ------------------------------------------------------------------

    private DocumentPO document(String sourcePath, String fileHash) {
        DocumentPO document = new DocumentPO();
        document.setSourcePath(sourcePath);
        document.setFileHash(fileHash);
        return document;
    }

    private ScannedFile scanned(String sourcePath, String fileHash) {
        String fileName = sourcePath.substring(sourcePath.lastIndexOf('/') + 1);
        VaultFile file = new VaultFile(sourcePath, fileName, null,
                "content".getBytes(StandardCharsets.UTF_8), null);
        return new ScannedFile(file, fileHash);
    }
}
