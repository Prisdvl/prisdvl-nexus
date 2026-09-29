package com.lky.nexus.knowledge.imports;

import com.lky.nexus.knowledge.model.entity.DocumentPO;

import java.util.List;

/**
 * 同步计划（第一阶段判定结果，仅基于 {@code file_hash}，未解析正文）。
 *
 * @param unchanged 未变化 → 直接跳过（<b>不解析、不写库</b>）
 * @param changed   字节变了 → 需解析后判定是"只改元数据"还是"正文也变了"
 * @param added     库里没有这个路径 → 可能是新增，也可能是"改名后的新路径"（需与 missing 按 hash 配对）
 * @param missing   库里有、本次清单没有 → 可能是删除，也可能被改名认领
 */
public record VaultSyncPlan(
        List<ScannedFile> unchanged,
        List<ScannedFile> changed,
        List<ScannedFile> added,
        List<DocumentPO> missing) {

    public int totalScanned() {
        return unchanged.size() + changed.size() + added.size();
    }
}
