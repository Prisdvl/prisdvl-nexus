package com.lky.prisma.app.infra.web;

import com.lky.prisma.common.api.ErrorCode;
import com.lky.prisma.common.api.Result;
import com.lky.prisma.common.exception.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅供测试使用的控制器：用最小代价覆盖 Web 契约（统一返回体 / 错误码映射 / 参数校验 / 序列化策略）。
 *
 * <p>放在 test 源码目录，不会打进生产 jar。
 */
@RestController
@RequestMapping("/test-only")
class TestOnlyController {

    /** 同时包含：Long 包装类型（ID，应序列化为字符串）与基本类型（计数，应保持数字）。 */
    record DemoData(Long id, long totalTokens, Integer chunkCount, String name) {
    }

    record DemoRequest(@NotBlank(message = "name 不能为空") String name) {
    }

    @GetMapping("/ok/{id}")
    Result<DemoData> ok(@PathVariable Long id) {
        return Result.ok(new DemoData(id, 42L, 7, "demo"));
    }

    @GetMapping("/business-error")
    Result<Void> businessError() {
        throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
    }

    @GetMapping("/business-error-custom")
    Result<Void> businessErrorWithCustomMessage() {
        throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED, "文件 51MB 超出 50MB 限制");
    }

    @GetMapping("/unexpected-error")
    Result<Void> unexpectedError() {
        throw new IllegalStateException("模拟未预期异常");
    }

    @PostMapping("/validate")
    Result<Void> validate(@Valid @RequestBody DemoRequest request) {
        return Result.ok();
    }
}
