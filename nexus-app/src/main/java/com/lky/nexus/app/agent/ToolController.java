package com.lky.nexus.app.agent;

import com.lky.nexus.app.agent.model.dto.ToolResponse;
import com.lky.nexus.app.agent.model.dto.UpdateToolRequest;
import com.lky.nexus.app.agent.model.entity.ToolRegistryPO;
import com.lky.nexus.app.agent.tool.ToolRegistry;
import com.lky.nexus.app.agent.tool.ToolRegistrationService;
import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.common.api.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工具目录接口（平台化 4.3，§十九）。
 *
 * <p>只有两个动作，且都直接对应工具这一层的真实机制：
 * <ul>
 *   <li>{@code GET /api/tools} —— 该用户能看到哪些工具、各自是开是关；</li>
 *   <li>{@code PUT /api/tools/{code}} —— 启停（§十八 的"启停"落在工具上的那一半）。</li>
 * </ul>
 *
 * <p><b>刻意没有</b>的接口：新建 / 删除 / 改名 / 改描述。工具的定义来自代码里的
 * {@code @Tool} 方法（名字与描述就是模型看到的那一份），库里那些"编辑描述"的字段
 * 在本增量里压根不存在——做一个改不动的编辑器比没有这个功能更糟（§六十七 "假旋钮"）。
 * 外部工具（MCP / 自定义 HTTP）落地时，"工具从哪来"才会变成真正的写操作。
 *
 * <p>与 Agent 侧的关系：白名单在 {@code PUT /api/agents/{id}} 的 {@code toolCodes} 里改，
 * 这里只管"用户级开关"。
 */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final ToolRegistry toolRegistry;
    private final ToolRegistrationService registrationService;

    public ToolController(ToolRegistry toolRegistry, ToolRegistrationService registrationService) {
        this.toolRegistry = toolRegistry;
        this.registrationService = registrationService;
    }

    /**
     * 该用户的工具目录（没有覆盖行的工具 enabled=true，即"默认开着"）。
     *
     * <p>{@code permission} 取的是<b>数据库里实际生效的值</b>（代码声明只是首次注册的默认），
     * 这样界面上看到的风险等级与执行拦截用的是同一个来源。
     */
    @GetMapping
    public Result<List<ToolResponse>> list() {
        Long userId = CurrentUser.requireId();
        return Result.ok(toolRegistry.catalogOf(userId).stream()
                .map(definition -> ToolResponse.of(definition,
                        registrationService.findByCode(definition.code())
                                .map(ToolRegistryPO::getPermission)
                                .orElse(null)))
                .toList());
    }

    /**
     * 启停一个工具。
     *
     * <p>停用的效果是"任何 Agent 都不再装它"——模型连这个工具的存在都看不到，
     * 因此它不会再被调用（而不是调用后被拒）。拒绝语义（模型看得见但被告知不能用）
     * 属 §二十一 的审批，落 P1。
     */
    @PutMapping("/{code}")
    public Result<ToolResponse> setEnabled(@PathVariable String code,
                                           @Valid @RequestBody UpdateToolRequest request) {
        return Result.ok(ToolResponse.of(
                toolRegistry.setEnabled(CurrentUser.requireId(), code, request.enabled())));
    }
}
