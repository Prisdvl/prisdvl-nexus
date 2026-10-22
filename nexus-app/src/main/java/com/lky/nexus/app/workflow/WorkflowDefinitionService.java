package com.lky.nexus.app.workflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lky.nexus.app.workflow.mapper.WorkflowMapper;
import com.lky.nexus.app.workflow.mapper.WorkflowVersionMapper;
import com.lky.nexus.app.workflow.model.WorkflowGraph;
import com.lky.nexus.app.workflow.model.dto.CreateWorkflowRequest;
import com.lky.nexus.app.workflow.model.dto.SaveWorkflowDraftRequest;
import com.lky.nexus.app.workflow.model.dto.UpdateWorkflowRequest;
import com.lky.nexus.app.workflow.model.dto.WorkflowPresetResponse;
import com.lky.nexus.app.workflow.model.dto.WorkflowResponse;
import com.lky.nexus.app.workflow.model.dto.WorkflowValidationResponse;
import com.lky.nexus.app.workflow.model.dto.WorkflowVersionResponse;
import com.lky.nexus.app.workflow.model.entity.WorkflowPO;
import com.lky.nexus.app.workflow.model.entity.WorkflowVersionPO;
import com.lky.nexus.app.workspace.WorkspaceService;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.api.PageQuery;
import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.common.util.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;

/** 工作流定义服务：归属校验、草稿保存、图校验和不可变发布。 */
@Service
public class WorkflowDefinitionService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDefinitionService.class);
    private static final int SCHEMA_VERSION = 1;

    private final WorkflowMapper workflowMapper;
    private final WorkflowVersionMapper versionMapper;
    private final WorkflowGraphValidator graphValidator;
    private final WorkspaceService workspaceService;
    private final JsonMapper jsonMapper;
    private final WorkflowPresets presets;

    public WorkflowDefinitionService(WorkflowMapper workflowMapper,
                                     WorkflowVersionMapper versionMapper,
                                     WorkflowGraphValidator graphValidator,
                                     WorkspaceService workspaceService,
                                     JsonMapper jsonMapper,
                                     WorkflowPresets presets) {
        this.workflowMapper = workflowMapper;
        this.versionMapper = versionMapper;
        this.graphValidator = graphValidator;
        this.workspaceService = workspaceService;
        this.jsonMapper = jsonMapper;
        this.presets = presets;
    }

    /** 内置模板清单（不含图）。 */
    public List<WorkflowPresetResponse> presetList() {
        return presets.all().stream()
                .map(preset -> new WorkflowPresetResponse(preset.code(), preset.name(),
                        preset.category(), preset.description(), preset.tags(),
                        preset.graph().nodes().size()))
                .toList();
    }

    /**
     * 用内置模板新建一个工作流：先按模板名建库，再把模板图落成草稿（不自动发布）。
     *
     * <p>刻意不自动发布：模板里的 `KNOWLEDGE_RETRIEVAL.kbIds` 是空的，用户得先选自己的知识库，
     * 否则一发布就是个跑不起来的流程。
     */
    @Transactional
    public WorkflowResponse instantiatePreset(Long userId, String code) {
        WorkflowPresets.Preset preset = presets.require(code);
        WorkflowResponse created = create(userId, new CreateWorkflowRequest(preset.name(), preset.description()));
        saveDraft(userId, created.id(),
                new SaveWorkflowDraftRequest(preset.graph().nodes(), preset.graph().edges()));
        return detail(userId, created.id());
    }

    @Transactional
    public WorkflowResponse create(Long userId, CreateWorkflowRequest request) {
        WorkflowPO workflow = new WorkflowPO();
        workflow.setUserId(userId);
        workflow.setWorkspaceId(workspaceService.requirePersonalWorkspace(userId));
        workflow.setName(request.name().trim());
        workflow.setDescription(request.description());
        workflow.setStatus("DRAFT");
        LocalDateTime now = LocalDateTime.now();
        workflow.setCreatedAt(now);
        workflow.setUpdatedAt(now);
        workflowMapper.insert(workflow);
        return toResponse(workflow);
    }

    public PageResult<WorkflowResponse> list(Long userId, int pageNum, int pageSize) {
        int safePageNum = PageQuery.normalizePageNum(pageNum);
        int safePageSize = PageQuery.normalizePageSize(pageSize);
        Page<WorkflowPO> page = new Page<>(safePageNum, safePageSize);
        Page<WorkflowPO> result = workflowMapper.selectPage(page, Wrappers.<WorkflowPO>lambdaQuery()
                .eq(WorkflowPO::getUserId, userId)
                .orderByDesc(WorkflowPO::getUpdatedAt));
        return PageResult.of(result.getRecords().stream().map(this::toResponse).toList(),
                result.getTotal(), safePageNum, safePageSize);
    }

    public WorkflowResponse detail(Long userId, Long workflowId) {
        return toResponse(requireOwned(userId, workflowId));
    }

    /**
     * 修改元信息（名称 / 描述）：局部更新，字段为 null 表示不改；description 传空串表示清空。
     *
     * <p>刻意<b>不产生新版本</b>：图与元信息是两件事，只有 {@link #saveDraft} 才生成不可变版本快照。
     * 否则「改个名字」也会让 draftVersionNo 递增，版本列表会被改名噪声填满。
     */
    @Transactional
    public WorkflowResponse update(Long userId, Long workflowId, UpdateWorkflowRequest request) {
        WorkflowPO workflow = requireOwned(userId, workflowId);
        if (StringUtils.hasText(request.name())) {
            workflow.setName(request.name().trim());
        }
        if (request.description() != null) {
            workflow.setDescription(StringUtils.hasText(request.description())
                    ? request.description().trim() : null);
        }
        workflow.setUpdatedAt(LocalDateTime.now());
        workflowMapper.updateById(workflow);
        return toResponse(workflow);
    }

    @Transactional
    public WorkflowResponse saveDraft(Long userId, Long workflowId, SaveWorkflowDraftRequest request) {
        WorkflowPO workflow = requireOwned(userId, workflowId);
        WorkflowGraph graph = new WorkflowGraph(SCHEMA_VERSION, request.nodes(), request.edges());

        WorkflowVersionPO version = new WorkflowVersionPO();
        version.setWorkflowId(workflowId);
        version.setVersionNo(nextVersionNo(workflowId));
        version.setSchemaVersion(SCHEMA_VERSION);
        version.setGraphJson(writeJson(graph));
        version.setGraphHash(HashUtils.sha256Hex(version.getGraphJson()));
        version.setStatus("DRAFT");
        version.setCreatedBy(userId);
        version.setCreatedAt(LocalDateTime.now());
        versionMapper.insert(version);

        workflow.setDraftVersionId(version.getId());
        workflow.setStatus("DRAFT");
        workflow.setUpdatedAt(LocalDateTime.now());
        workflowMapper.updateById(workflow);
        return toResponse(workflow);
    }

    public WorkflowValidationResponse validateDraft(Long userId, Long workflowId) {
        try {
            graphValidator.validate(loadGraph(requireOwned(userId, workflowId).getDraftVersionId()));
            return WorkflowValidationResponse.ok();
        } catch (BusinessException ex) {
            return new WorkflowValidationResponse(false, List.of(ex.getMessage()));
        }
    }

    @Transactional
    public WorkflowResponse publish(Long userId, Long workflowId) {
        WorkflowPO workflow = requireOwned(userId, workflowId);
        if (workflow.getDraftVersionId() == null) {
            throw invalid("工作流还没有可发布的草稿");
        }
        WorkflowGraph graph = loadGraph(workflow.getDraftVersionId());
        graphValidator.validate(graph);

        WorkflowVersionPO draft = requireVersion(workflow.getDraftVersionId());
        if (!"DRAFT".equals(draft.getStatus())) {
            throw invalid("只有草稿版本可以发布");
        }
        draft.setStatus("PUBLISHED");
        draft.setPublishedAt(LocalDateTime.now());
        versionMapper.updateById(draft);

        workflow.setPublishedVersionId(draft.getId());
        workflow.setStatus("PUBLISHED");
        workflow.setUpdatedAt(LocalDateTime.now());
        workflowMapper.updateById(workflow);
        log.info("工作流已发布 workflowId={} versionId={}", workflowId, draft.getId());
        return toResponse(workflow);
    }

    public List<WorkflowVersionResponse> versions(Long userId, Long workflowId) {
        requireOwned(userId, workflowId);
        return versionMapper.selectList(Wrappers.<WorkflowVersionPO>lambdaQuery()
                        .eq(WorkflowVersionPO::getWorkflowId, workflowId)
                        .orderByDesc(WorkflowVersionPO::getVersionNo))
                .stream()
                .map(this::toVersionResponse)
                .toList();
    }

    @Transactional
    public void delete(Long userId, Long workflowId) {
        requireOwned(userId, workflowId);
        workflowMapper.deleteById(workflowId);
    }

    public WorkflowPO requireOwned(Long userId, Long workflowId) {
        if (workflowId == null) {
            throw notFound();
        }
        WorkflowPO workflow = workflowMapper.selectOne(Wrappers.<WorkflowPO>lambdaQuery()
                .eq(WorkflowPO::getId, workflowId)
                .eq(WorkflowPO::getUserId, userId));
        if (workflow == null) {
            throw notFound();
        }
        return workflow;
    }

    public WorkflowGraph loadGraph(Long versionId) {
        if (versionId == null) {
            throw invalid("工作流还没有图定义");
        }
        return parseJson(requireVersion(versionId).getGraphJson());
    }

    public WorkflowVersionPO requireVersion(Long versionId) {
        WorkflowVersionPO version = versionMapper.selectById(versionId);
        if (version == null) {
            throw invalid("工作流版本不存在");
        }
        return version;
    }

    private int nextVersionNo(Long workflowId) {
        Integer current = versionMapper.selectList(Wrappers.<WorkflowVersionPO>lambdaQuery()
                        .eq(WorkflowVersionPO::getWorkflowId, workflowId)
                        .orderByDesc(WorkflowVersionPO::getVersionNo)
                        .last("LIMIT 1"))
                .stream().findFirst().map(WorkflowVersionPO::getVersionNo).orElse(0);
        return current + 1;
    }

    private WorkflowResponse toResponse(WorkflowPO workflow) {
        WorkflowGraph draft = workflow.getDraftVersionId() == null ? null : loadGraph(workflow.getDraftVersionId());
        WorkflowGraph published = workflow.getPublishedVersionId() == null
                ? null : loadGraph(workflow.getPublishedVersionId());
        WorkflowGraph current = draft != null ? draft : published;
        return new WorkflowResponse(
                workflow.getId(),
                workflow.getName(),
                workflow.getDescription(),
                workflow.getStatus(),
                workflow.getDraftVersionId(),
                workflow.getPublishedVersionId(),
                versionNoOf(workflow.getDraftVersionId()),
                versionNoOf(workflow.getPublishedVersionId()),
                current == null ? List.of() : current.nodes(),
                current == null ? List.of() : current.edges(),
                workflow.getCreatedAt(),
                workflow.getUpdatedAt()
        );
    }

    private Integer versionNoOf(Long versionId) {
        return versionId == null ? null : requireVersion(versionId).getVersionNo();
    }

    private WorkflowVersionResponse toVersionResponse(WorkflowVersionPO version) {
        return new WorkflowVersionResponse(version.getId(), version.getVersionNo(), version.getStatus(),
                version.getGraphHash(), version.getCreatedAt(), version.getPublishedAt());
    }

    private WorkflowGraph parseJson(String json) {
        try {
            return jsonMapper.readValue(json, WorkflowGraph.class);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.WORKFLOW_DEFINITION_INVALID, "工作流图定义解析失败");
        }
    }

    private String writeJson(WorkflowGraph graph) {
        try {
            return jsonMapper.writeValueAsString(graph);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.WORKFLOW_DEFINITION_INVALID, "工作流图定义序列化失败");
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.WORKFLOW_DEFINITION_INVALID, message);
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND);
    }
}
