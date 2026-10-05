package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.getjobs.application.entity.BlacklistEntity;
import com.getjobs.application.mapper.BlacklistMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 平台无关的黑名单服务。
 * <p>
 * 表 {@code boss_blacklist} 其实就是一张通用的 {@code (type, value)} 表 —— 名字带 boss
 * 只是历史包袱。新增平台不必再建表，只要约定一组新的 type 即可（例如智联用
 * {@code zhilian_company} / {@code zhilian_job} / {@code zhilian_recruiter}）。
 * <p>
 * ⚠️ 语义是「投递前跳过」，不是「搜索层过滤」：命中的岗位不会被投递，
 * 但站点搜索本身不受影响（多数招聘站的搜索也根本不支持排除关键词）。
 * <p>
 * 注意匹配规则由各平台自己实现（见 {@code ZhiLian.matchedTerm}）：这里是「子串包含 +
 * 忽略大小写」；而 Boss 侧的旧实现是 Java {@code contains}，**区分大小写**，
 * 所以填 {@code pdd} 命中不了 {@code PDD科技}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlacklistService {

    private final BlacklistMapper blacklistMapper;

    /**
     * 按类型取黑名单关键词集合（已去空白、去重，保持插入顺序）。
     *
     * @param type 类型，如 company / job / recruiter / zhilian_company ...
     * @return 永不为 null 的集合
     */
    public Set<String> getValues(String type) {
        if (type == null || type.isBlank()) return Collections.emptySet();
        try {
            LambdaQueryWrapper<BlacklistEntity> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(BlacklistEntity::getType, type);
            List<BlacklistEntity> list = blacklistMapper.selectList(wrapper);
            Set<String> out = new LinkedHashSet<>();
            for (BlacklistEntity e : list) {
                if (e != null && e.getValue() != null && !e.getValue().isBlank()) {
                    out.add(e.getValue().trim());
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("读取黑名单失败 type={}: {}", type, e.getMessage());
            return Collections.emptySet();
        }
    }

    /** 全部黑名单（按类型分组展示用） */
    public List<BlacklistEntity> getAll() {
        return blacklistMapper.selectList(null);
    }

    /**
     * 添加一条黑名单。
     *
     * @return 落库后的实体（含自增 id）；已存在或失败返回 null
     */
    public BlacklistEntity add(String type, String value) {
        if (type == null || type.isBlank() || value == null || value.isBlank()) return null;
        String v = value.trim();
        try {
            LambdaQueryWrapper<BlacklistEntity> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(BlacklistEntity::getType, type).eq(BlacklistEntity::getValue, v);
            Long exists = blacklistMapper.selectCount(wrapper);
            if (exists != null && exists > 0) return null;

            BlacklistEntity entity = new BlacklistEntity();
            entity.setType(type);
            entity.setValue(v);
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            blacklistMapper.insert(entity);
            return entity;
        } catch (Exception e) {
            log.warn("添加黑名单失败 type={} value={}: {}", type, v, e.getMessage());
            return null;
        }
    }

    /** 按主键删除 */
    public boolean removeById(Long id) {
        if (id == null) return false;
        return blacklistMapper.deleteById(id) > 0;
    }

    /** 按类型 + 值删除 */
    public boolean remove(String type, String value) {
        if (type == null || value == null) return false;
        LambdaQueryWrapper<BlacklistEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BlacklistEntity::getType, type).eq(BlacklistEntity::getValue, value);
        return blacklistMapper.delete(wrapper) > 0;
    }
}
