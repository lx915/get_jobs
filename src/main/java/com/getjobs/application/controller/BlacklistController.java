package com.getjobs.application.controller;

import com.getjobs.application.entity.BlacklistEntity;
import com.getjobs.application.service.BlacklistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台无关的黑名单接口 —— 供各平台配置页共用。
 * <p>
 * 与 {@code /api/boss/config/blacklist} 的区别：那条路径是 Boss 页面专用的历史入口，
 * 这里不区分平台，类型由调用方传（智联页面传 {@code zhilian_company} /
 * {@code zhilian_job} / {@code zhilian_recruiter}）。老的 boss 接口保持不动，
 * 避免影响已经能工作的 Boss 页面。
 */
@Slf4j
@RestController
@RequestMapping("/api/blacklist")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class BlacklistController {

    private final BlacklistService blacklistService;

    /**
     * 查询黑名单。
     *
     * @param type 可选，按类型过滤；不传返回全部
     */
    @GetMapping
    public List<BlacklistEntity> list(@RequestParam(value = "type", required = false) String type) {
        if (type == null || type.isBlank()) {
            return blacklistService.getAll();
        }
        // 复用同一个实体列表，保持前端拿到的结构与 getAll 一致（含 id / created_at）
        return blacklistService.getAll().stream()
                .filter(e -> type.equals(e.getType()))
                .toList();
    }

    /** 新增黑名单，body: {"type":"zhilian_company","value":"某某公司"} */
    @PostMapping
    public Map<String, Object> add(@RequestBody BlacklistEntity body) {
        Map<String, Object> out = new LinkedHashMap<>();
        String type = body == null ? null : body.getType();
        String value = body == null ? null : body.getValue();
        if (type == null || type.isBlank() || value == null || value.isBlank()) {
            out.put("success", false);
            out.put("message", "type 和 value 都不能为空");
            return out;
        }
        BlacklistEntity saved = blacklistService.add(type, value);
        if (saved == null) {
            out.put("success", false);
            out.put("message", "已存在或写入失败");
            return out;
        }
        out.put("success", true);
        out.put("data", saved);
        return out;
    }

    /** 按 id 删除黑名单 */
    @DeleteMapping("/{id}")
    public boolean delete(@PathVariable("id") Long id) {
        return blacklistService.removeById(id);
    }
}
