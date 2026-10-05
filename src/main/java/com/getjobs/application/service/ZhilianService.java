package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.getjobs.application.entity.ZhilianConfigEntity;
import com.getjobs.application.entity.ZhilianOptionEntity;
import com.getjobs.application.entity.ZhilianJobDataEntity;
import com.getjobs.application.mapper.ZhilianConfigMapper;
import com.getjobs.application.mapper.ZhilianOptionMapper;
import com.getjobs.application.mapper.ZhilianJobDataMapper;
import com.getjobs.worker.zhilian.ZhilianConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ZhilianService {
    private final ZhilianConfigMapper zhilianConfigMapper;
    private final ZhilianOptionMapper zhilianOptionMapper;
    private final ZhilianJobDataMapper zhilianJobDataMapper;
    private final DataSource dataSource;

    /** 获取第一条配置（通常只有一条） */
    public ZhilianConfigEntity getFirstConfig() {
        QueryWrapper<ZhilianConfigEntity> wrapper = new QueryWrapper<>();
        wrapper.last("LIMIT 1");
        return zhilianConfigMapper.selectOne(wrapper);
    }

    /** 从专表构建 ZhilianConfig */
    public ZhilianConfig loadZhilianConfig() {
        ZhilianConfigEntity entity = getFirstConfig();
        ZhilianConfig config = new ZhilianConfig();
        if (entity == null) {
            log.warn("zhilian_config 表为空，使用默认空配置");
            config.setKeywords(new ArrayList<>());
            config.setCityCode("0");
            config.setSalary("0");
            config.setEducationCodes(new ArrayList<>());
            config.setCompanySizeCodes(new ArrayList<>());
            config.setDebugger(false);
            return config;
        }

        // 关键词解析：支持逗号或括号列表
        config.setKeywords(parseListString(entity.getKeywords()));

        // 城市：中文名映射到代码；缺省或“不限”映射为 0
        String city = safeTrim(entity.getCityCode());
        if (city == null || city.isEmpty() || "不限".equals(city)) {
            config.setCityCode("0");
        } else if (city.chars().allMatch(Character::isDigit)) {
            config.setCityCode(city);
        } else {
            String code = getCodeByTypeAndName("city", city);
            if (code == null) {
                log.warn("智联配置中的城市 {} 未在 zhilian_option 表中找到，回退为 0", city);
                config.setCityCode("0");
            } else {
                config.setCityCode(code);
            }
        }

        // 薪资：库里存的可能是字典代码（"25001,35000"）或中文档位（"25K-35K"），
        // 也可能还是旧版的裸数字（"20000"）。统一解析成智联 URL 要的区间代码，
        // 解析不出来就返回 "0" = 不加该条件（详见 resolveSalaryCode 的注释）。
        config.setSalary(resolveSalaryCode(entity.getSalary()));

        // 学历 / 公司人数：库里存代码或中文名都认，统一解析成智联 URL 用的代码。
        // 留空 = 不加该筛选条件（≠ 传 "0"）。
        config.setEducationCodes(resolveOptionCodes("education", entity.getEducation()));
        config.setCompanySizeCodes(resolveOptionCodes("companySize", entity.getCompanySize()));
        config.setDebugger(entity.getDebugger() != null && entity.getDebugger() == 1);

        return config;
    }

    /**
     * 把库里存的「多选筛选值」解析成智联要的代码列表。
     * <p>
     * 同时兼容用户直接填代码（"4,3"）和填中文名（"本科、硕士"）两种写法 ——
     * 和 cityCode 的处理口径保持一致。解析不出来的值直接丢弃，
     * 宁可少一个筛选条件，也不要把脏值拼进 URL 让智联返回 0 个岗位。
     */
    public List<String> resolveOptionCodes(String type, String raw) {
        List<String> tokens = parseListString(raw);
        List<String> codes = new ArrayList<>();
        for (String token : tokens) {
            if (token == null || token.isEmpty()) continue;
            if (token.chars().allMatch(Character::isDigit)) {
                if (!"0".equals(token) && !codes.contains(token)) codes.add(token);
                continue;
            }
            String code = getCodeByTypeAndName(type, token);
            if (code == null) {
                // 再试一次：万一表里存的就是代码而用户误填了名字大小写
                ZhilianOptionEntity byCode = getOptionByTypeAndCode(type, token);
                if (byCode != null && !codes.contains(byCode.getCode())) codes.add(byCode.getCode());
                else log.warn("智联配置中的 {} 取值 {} 未在 zhilian_option 表中找到，已忽略", type, token);
            } else if (!"0".equals(code) && !codes.contains(code)) {
                codes.add(code);
            }
        }
        return codes;
    }

    /**
     * 解析薪资档位，返回智联 URL 用的区间代码（形如 {@code "25001,35000"}）。
     * <p>
     * ⚠️ 与学历 / 公司人数不同，**智联薪资档的代码本身含逗号**，所以绝不能走
     * {@link #parseListString} —— 那会把一个档位切成两个残缺数字，拼进 URL 后
     * 静默失效（这正是"薪资范围没有生效"的根因）。
     * <p>
     * 接受的写法（按优先级）：
     * <ol>
     *   <li>已经是区间代码：{@code "25001,35000"}（两段都必须是数字才放行）；</li>
     *   <li>字典里的代码但只有一个值：查 {@code zhilian_option}；</li>
     *   <li>中文档位名：{@code "25K-35K"}；</li>
     *   <li>旧版遗留的裸数字：{@code "20000"} → 当作"月薪下限 20K 以上"，
     *       即 {@code "20000,9999999"}（实测服务端接受任意区间码）。</li>
     * </ol>
     * 都解析不出来时返回 {@code "0"} = 不加该筛选条件。
     */
    public String resolveSalaryCode(String raw) {
        if (raw == null) return "0";
        String v = raw.trim();
        if (v.isEmpty() || "不限".equals(v) || "0".equals(v)) return "0";

        if (v.contains(",")) {
            String[] parts = v.split(",", 2);
            String lo = parts[0].trim(), hi = parts[1].trim();
            if (!lo.isEmpty() && !hi.isEmpty()
                    && lo.chars().allMatch(Character::isDigit)
                    && hi.chars().allMatch(Character::isDigit)) {
                return lo + "," + hi;
            }
            log.warn("智联配置中的薪资取值 {} 不是合法的区间代码，已忽略", v);
            return "0";
        }

        ZhilianOptionEntity byCode = getOptionByTypeAndCode("salary", v);
        if (byCode != null) return byCode.getCode();
        String byName = getCodeByTypeAndName("salary", v);
        if (byName != null) return byName;

        if (v.chars().allMatch(Character::isDigit)) {
            return v + ",9999999";
        }
        log.warn("智联配置中的薪资取值 {} 未在 zhilian_option 表中找到，已忽略", v);
        return "0";
    }

    public List<String> parseListString(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new ArrayList<>();
        // 分隔符要认全：中文顿号「、」和分号「；」在国内用户里非常常用。
        // 以前只认 , 和 ，于是界面上填的 "JAVA、后端、Spring" 会被整串当成**一个**关键词，
        // 原样塞进智联搜索框 —— 必然搜不到任何岗位，表现又是"投递0个岗位"。
        // 同时把方括号和引号一次性抹掉：若先按顿号切开，`["JAVA、后端、Spring"]`
        // 的第一个片段会变成 `"JAVA`（只剩前半个引号），stripWrapperQuotes 就兜不住了。
        String s = raw.trim()
                .replace('，', ',').replace('、', ',').replace('；', ',').replace(';', ',')
                .replace("[", "").replace("]", "")
                .replace("\"", "").replace("'", "");
        if (s.trim().isEmpty()) return new ArrayList<>();
        return java.util.Arrays.stream(s.split(","))
                .map(String::trim)
                .map(this::stripWrapperQuotes)
                .filter(str -> !str.isEmpty())
                .collect(Collectors.toList());
    }

    private String safeTrim(String s) { return s == null ? null : s.trim(); }

    /**
     * 构建智联新搜索页的 URL（投递与诊断接口共用同一份实现，避免两处漂移）。
     * <p>
     * 参数：{@code kw} 关键词 / {@code jl} 城市 / {@code sl} 薪资 / {@code el} 学历 /
     * {@code cs} 公司人数 / {@code p} 页码。学历与公司人数多选用逗号分隔。
     * <p>
     * 三个必须记住的坑（均为实测结论）：
     * <ol>
     *   <li>关键词必须放在 URL 里。旧实现是"打开空搜索页再用输入框打字"，
     *       而新页面在跳转后会把关键词丢掉。</li>
     *   <li>"不限城市"必须**整个不传 jl**，不能传 {@code jl=0} ——
     *       {@code ?kw=Java&jl=0} 会返回 0 个岗位，而完全不传 jl 才是全国。</li>
     *   <li>{@code sl} 是**"最低,最高"的区间对**（如 {@code 25001,35000}），
     *       裸数字会被静默忽略。它的筛选语义是"岗位薪资区间与所选档位**有交集**"，
     *       不是"完全落在档位内"。</li>
     * </ol>
     */
    public String buildZhilianSearchUrl(ZhilianConfig config, String keyword, int pageNum) {
        StringBuilder sb = new StringBuilder("https://www.zhaopin.com/jobs?kw=");
        sb.append(java.net.URLEncoder.encode(keyword == null ? "" : keyword,
                java.nio.charset.StandardCharsets.UTF_8));

        String city = config == null ? null : config.getCityCode();
        if (city != null) city = city.trim();
        if (city != null && !city.isEmpty() && !"0".equals(city) && !"不限".equals(city)) {
            sb.append("&jl=").append(city);
        }

        String salary = config == null ? null : config.getSalary();
        if (salary != null && !salary.trim().isEmpty()
                && !"0".equals(salary.trim()) && !"不限".equals(salary.trim())) {
            sb.append("&sl=").append(salary.trim());
        }

        if (config != null && config.getEducationCodes() != null && !config.getEducationCodes().isEmpty()) {
            sb.append("&el=").append(String.join(",", config.getEducationCodes()));
        }
        if (config != null && config.getCompanySizeCodes() != null && !config.getCompanySizeCodes().isEmpty()) {
            sb.append("&cs=").append(String.join(",", config.getCompanySizeCodes()));
        }

        sb.append("&p=").append(pageNum);
        return sb.toString();
    }

    private String stripWrapperQuotes(String value) {
        if (value == null || value.length() < 2) return value;
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    /**
     * 选择性更新：若传入 ID 则按 ID 更新；否则更新第一条记录（不存在则插入）
     */
    public ZhilianConfigEntity updateConfig(ZhilianConfigEntity config) {
        if (config == null) return null;
        if (config.getId() != null) {
            zhilianConfigMapper.updateById(config);
            return zhilianConfigMapper.selectById(config.getId());
        }
        return saveOrUpdateFirstSelective(config);
    }

    /**
     * 保存或选择性更新第一条记录（仅覆盖非空字段）
     */
    public ZhilianConfigEntity saveOrUpdateFirstSelective(ZhilianConfigEntity incoming) {
        ZhilianConfigEntity first = getFirstConfig();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        if (first == null) {
            ZhilianConfigEntity toInsert = new ZhilianConfigEntity();
            toInsert.setKeywords(incoming.getKeywords());
            toInsert.setCityCode(incoming.getCityCode());
            toInsert.setSalary(incoming.getSalary());
            toInsert.setEducation(incoming.getEducation());
            toInsert.setCompanySize(incoming.getCompanySize());
            toInsert.setDebugger(incoming.getDebugger() == null ? 0 : incoming.getDebugger());
            toInsert.setCreatedAt(now);
            toInsert.setUpdatedAt(now);
            zhilianConfigMapper.insert(toInsert);
            return getFirstConfig();
        } else {
            ZhilianConfigEntity toUpdate = new ZhilianConfigEntity();
            toUpdate.setId(first.getId());
            if (incoming.getKeywords() != null) toUpdate.setKeywords(incoming.getKeywords());
            if (incoming.getCityCode() != null) toUpdate.setCityCode(incoming.getCityCode());
            if (incoming.getSalary() != null) toUpdate.setSalary(incoming.getSalary());
            // 学历/公司人数允许被清空（空串 = 取消该筛选），所以只要非 null 就落库
            if (incoming.getEducation() != null) toUpdate.setEducation(incoming.getEducation());
            if (incoming.getCompanySize() != null) toUpdate.setCompanySize(incoming.getCompanySize());
            // 调试模式是 0/1 的开关，必须能被关掉，所以同样只要非 null 就落库
            if (incoming.getDebugger() != null) toUpdate.setDebugger(incoming.getDebugger());
            toUpdate.setCreatedAt(first.getCreatedAt());
            toUpdate.setUpdatedAt(now);
            zhilianConfigMapper.updateById(toUpdate);
            return zhilianConfigMapper.selectById(first.getId());
        }
    }

    // ========== Option 辅助 ==========

    public List<ZhilianOptionEntity> getOptionsByType(String type) {
        return zhilianOptionMapper.selectList(
                new QueryWrapper<ZhilianOptionEntity>()
                        .eq("type", type)
                        .orderByAsc("sort_order")
        );
    }

    public ZhilianOptionEntity getOptionByTypeAndCode(String type, String code) {
        return zhilianOptionMapper.selectOne(
                new QueryWrapper<ZhilianOptionEntity>()
                        .eq("type", type)
                        .eq("code", code)
                        .last("LIMIT 1")
        );
    }

    public String getCodeByTypeAndName(String type, String name) {
        ZhilianOptionEntity e = zhilianOptionMapper.selectOne(
                new QueryWrapper<ZhilianOptionEntity>()
                        .eq("type", type)
                        .eq("name", name)
                        .last("LIMIT 1")
        );
        return e == null ? null : e.getCode();
    }

    public String getNameByTypeAndCode(String type, String code) {
        ZhilianOptionEntity e = getOptionByTypeAndCode(type, code);
        return e == null ? null : e.getName();
    }

    // ==================== 数据表初始化与数据操作 ====================

    @PostConstruct
    public void ensureZhilianDataTableExists() {
        String createSql = "CREATE TABLE IF NOT EXISTS zhilian_data (" +
                " id INTEGER PRIMARY KEY AUTOINCREMENT," +
                " job_id VARCHAR(64)," +
                " job_title VARCHAR(200)," +
                " job_link VARCHAR(300)," +
                " salary VARCHAR(100)," +
                " location VARCHAR(100)," +
                " experience VARCHAR(100)," +
                " degree VARCHAR(100)," +
                " company_name VARCHAR(200)," +
                " delivery_status VARCHAR(20) DEFAULT '未投递'," +
                " create_time DATETIME," +
                " update_time DATETIME" +
                ")";
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(createSql);
            log.info("确保 zhilian_data 表已存在");
        } catch (Exception e) {
            log.warn("创建 zhilian_data 表失败: {}", e.getMessage());
        }

        // zhilian_config 由 sqlite 脚本初始化，早期建表时没有学历/公司人数/调试模式这几列。
        // 这里做一次幂等补列，避免"升级代码但没升级库"导致启动后立即报 no such column。
        ensureConfigColumns();
        seedFilterOptions();
    }

    /** 幂等补列：SQLite 对已存在的列会抛异常，直接吞掉即可。 */
    private void ensureConfigColumns() {
        String[][] columns = {
                {"education", "ALTER TABLE zhilian_config ADD COLUMN education VARCHAR(100)"},
                {"company_size", "ALTER TABLE zhilian_config ADD COLUMN company_size VARCHAR(100)"},
                {"debugger", "ALTER TABLE zhilian_config ADD COLUMN debugger INTEGER DEFAULT 0"},
        };
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            for (String[] col : columns) {
                try {
                    stmt.execute(col[1]);
                    log.info("zhilian_config 已补充列: {}", col[0]);
                } catch (Exception alreadyExists) {
                    // 列已存在，属于正常情况
                }
            }
        } catch (Exception e) {
            log.warn("补充 zhilian_config 列失败: {}", e.getMessage());
        }
    }

    /**
     * 灌入薪资 / 学历 / 公司人数筛选项。
     * <p>
     * 代码来源：智联的前端 bundle 里只有参数名（{@code sl}/{@code el}/{@code cs}）
     * 没有取值表，而完整字典在
     * {@code https://fe-api.zhaopin.com/c/i/search/base/data}（2.1MB，顶层键
     * {@code salaryType / educationType / companySize} …）。抓下来直接照抄即可，
     * 比逐个码值去反推可靠得多。
     * <p>
     * 三个必须留意的点：
     * <ol>
     *   <li><b>薪资的 code 是"最低,最高"的区间对</b>（如 {@code 25001,35000}），
     *       不是单个数字 —— 早期版本把配置里的裸数字直接当参数值发出去，
     *       结果被服务端静默忽略，"薪资范围没有生效"就是这个原因。
     *       实测服务端接受**任意**区间码（{@code 20000,9999999} 也生效），
     *       所以旧的裸数字配置可以平滑迁移。</li>
     *   <li>公司人数的排序不等于代码顺序：300-499人 的代码是 8，排在 100-299人 之后。</li>
     *   <li>"不限"在字典里是 -1 / 0000,9999999，本项目统一用**留空**表示不加筛选，
     *       所以这里不灌"不限"这一项。</li>
     * </ol>
     */
    private void seedFilterOptions() {
        Object[][] salary = {
                {"4K以下", "0000,4000"}, {"4K-6K", "4001,6000"}, {"6K-8K", "6001,8000"},
                {"8K-10K", "8001,10000"}, {"10K-15K", "10001,15000"}, {"15K-25K", "15001,25000"},
                {"25K-35K", "25001,35000"}, {"35K-50K", "35001,50000"}, {"50K以上", "50001,9999999"},
        };
        Object[][] education = {
                {"不限", "0"}, {"大专", "5"}, {"本科", "4"}, {"硕士", "3"}, {"博士", "1"},
        };
        Object[][] companySize = {
                {"20人以下", "1"}, {"20-99人", "2"}, {"100-299人", "3"}, {"300-499人", "8"},
                {"500-999人", "4"}, {"1000-9999人", "5"}, {"10000人以上", "6"},
        };
        seedOptionType("salary", salary);
        seedOptionType("education", education);
        seedOptionType("companySize", companySize);
    }

    private void seedOptionType(String type, Object[][] rows) {
        try {
            Long existing = zhilianOptionMapper.selectCount(
                    new QueryWrapper<ZhilianOptionEntity>().eq("type", type));
            if (existing != null && existing > 0) return; // 已有则不覆盖，避免冲掉用户改动

            LocalDateTime now = LocalDateTime.now();
            int sort = 0;
            for (Object[] row : rows) {
                ZhilianOptionEntity e = new ZhilianOptionEntity();
                e.setType(type);
                e.setName(String.valueOf(row[0]));
                e.setCode(String.valueOf(row[1]));
                e.setSortOrder(sort++);
                e.setCreatedAt(now);
                e.setUpdatedAt(now);
                zhilianOptionMapper.insert(e);
            }
            log.info("zhilian_option 已初始化 {} 筛选项 {} 条", type, rows.length);
        } catch (Exception e) {
            log.warn("初始化 {} 筛选项失败: {}", type, e.getMessage());
        }
    }

    public boolean existsByJobId(String jobId) {
        if (jobId == null || jobId.trim().isEmpty()) return false;
        QueryWrapper<ZhilianJobDataEntity> w = new QueryWrapper<>();
        w.eq("job_id", jobId).last("LIMIT 1");
        Long c = zhilianJobDataMapper.selectCount(w);
        return c != null && c > 0;
    }

    public boolean existsByTitleAndCompany(String jobTitle, String companyName) {
        if (jobTitle == null || companyName == null) return false;
        QueryWrapper<ZhilianJobDataEntity> w = new QueryWrapper<>();
        w.eq("job_title", jobTitle).eq("company_name", companyName).last("LIMIT 1");
        Long c = zhilianJobDataMapper.selectCount(w);
        return c != null && c > 0;
    }

    public void insertJob(ZhilianJobDataEntity entity) {
        if (entity == null) return;
        LocalDateTime now = LocalDateTime.now();
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        if (entity.getDeliveryStatus() == null) entity.setDeliveryStatus("未投递");
        zhilianJobDataMapper.insert(entity);
    }

    public void markDeliveredByJobId(String jobId) {
        if (jobId == null || jobId.trim().isEmpty()) return;
        ZhilianJobDataEntity upd = new ZhilianJobDataEntity();
        upd.setDeliveryStatus("已投递");
        upd.setUpdateTime(LocalDateTime.now());
        com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ZhilianJobDataEntity> uw =
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<>();
        uw.eq("job_id", jobId);
        zhilianJobDataMapper.update(upd, uw);
    }

    public void markDeliveredByTitleAndCompany(String jobTitle, String companyName) {
        if (jobTitle == null || companyName == null) return;
        ZhilianJobDataEntity upd = new ZhilianJobDataEntity();
        upd.setDeliveryStatus("已投递");
        upd.setUpdateTime(LocalDateTime.now());
        com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ZhilianJobDataEntity> uw =
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<>();
        uw.eq("job_title", jobTitle).eq("company_name", companyName);
        zhilianJobDataMapper.update(upd, uw);
    }

    /**
     * 标记为「已过滤」—— 命中黑名单、主动放弃投递的岗位。
     * <p>
     * ⚠️ <b>只在岗位还没投递过时才改状态。</b> 已经「已投递」的记录不能被降级成「已过滤」：
     * 黑名单往往是事后才加的，把历史投递记录改成「已过滤」会丢掉真实投递事实
     * （实测库里就有 {@code 客户公司：华为技术有限公司} 这种在加黑名单之前就投递成功的行）。
     * 同时这个条件也让本方法天然幂等。
     */
    public void markFilteredByJobId(String jobId) {
        if (jobId == null || jobId.trim().isEmpty()) return;
        markFiltered(new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ZhilianJobDataEntity>()
                .eq("job_id", jobId));
    }

    public void markFilteredByTitleAndCompany(String jobTitle, String companyName) {
        if (jobTitle == null || companyName == null) return;
        markFiltered(new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ZhilianJobDataEntity>()
                .eq("job_title", jobTitle).eq("company_name", companyName));
    }

    private void markFiltered(
            com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ZhilianJobDataEntity> uw) {
        // 只覆盖"尚未投递"的行：null / 空串 / 未投递
        uw.and(w -> w.isNull("delivery_status")
                .or().eq("delivery_status", "")
                .or().eq("delivery_status", "未投递"));
        ZhilianJobDataEntity upd = new ZhilianJobDataEntity();
        upd.setDeliveryStatus("已过滤");
        upd.setUpdateTime(LocalDateTime.now());
        zhilianJobDataMapper.update(upd, uw);
    }

    // ==================== 投递分析（Dashboard）与列表 ====================

    /** 薪资解析结果 */
    public static class SalaryInfo {
        public Integer minK;      // 最小K（单位：K/月）
        public Integer maxK;      // 最大K（单位：K/月）
        public Integer months;    // 月数（默认12）
        public Double medianK;    // 中位数K
        public Long annualTotal;  // 年包（单位：元）
    }

    /**
     * 解析薪资字符串，统一换算成「K/月」。
     * <p>
     * 同时支持智联新旧两种写法：
     * <ul>
     *   <li>旧页面：{@code 20-40K}、{@code 35-65K·16薪}、{@code 30K·15薪}</li>
     *   <li>新页面：{@code 7000-10000元}、{@code 1.6-1.8万}、{@code 1-1.5万·13薪}</li>
     * </ul>
     * 旧实现只认 {@code ^\d+-\d+K$}，而新版搜索页返回的是「万 / 元」，
     * 于是改版后投递分析里的薪资桶会整个空掉 —— 采集回来的数据等于白采。
     * 「面议」这类没有数字的写法返回 null。
     */
    public static SalaryInfo parseSalary(String salary) {
        if (salary == null) return null;
        String s = salary.trim().replace(" ", "");
        if (s.isEmpty() || s.contains("面议")) return null;

        Integer months = 12;
        java.util.regex.Matcher mMonths = java.util.regex.Pattern.compile("[·\\.\\-]?([0-9]+)薪").matcher(s);
        if (mMonths.find()) {
            try { months = Integer.parseInt(mMonths.group(1)); } catch (Exception ignore) {}
            s = s.substring(0, mMonths.start());
        }

        // 单位统一到 K/月：万 -> ×10；元 -> ÷1000；K / 千 -> ×1
        double unit = 1.0;
        if (s.contains("万")) unit = 10.0;
        else if (s.contains("元")) unit = 0.001;

        Double lo = null, hi = null;
        java.util.regex.Matcher mRange = java.util.regex.Pattern
                .compile("([0-9]+(?:\\.[0-9]+)?)[-~—至]([0-9]+(?:\\.[0-9]+)?)").matcher(s);
        if (mRange.find()) {
            try {
                lo = Double.parseDouble(mRange.group(1)) * unit;
                hi = Double.parseDouble(mRange.group(2)) * unit;
            } catch (Exception ignore) {}
        } else {
            java.util.regex.Matcher mSingle =
                    java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)").matcher(s);
            if (mSingle.find()) {
                try {
                    lo = Double.parseDouble(mSingle.group(1)) * unit;
                    hi = lo;
                } catch (Exception ignore) {}
            }
        }
        if (lo == null || hi == null) return null;

        SalaryInfo info = new SalaryInfo();
        info.minK = (int) Math.round(Math.min(lo, hi));
        info.maxK = (int) Math.round(Math.max(lo, hi));
        info.months = months != null ? months : 12;
        info.medianK = (info.minK + info.maxK) / 2.0;
        info.annualTotal = Math.round(info.medianK * 1000 * info.months);
        return info;
    }

    /** KPI 指标 */
    public static class Kpi {
        public long total;
        public long delivered;
        public long pending;
        public long filtered; // 智联暂不区分，置0或来源于 delivery_status
        public long failed;   // 智暂不区分，置0或来源于 delivery_status
        public Double avgMonthlyK; // 平均中位数K
    }

    /** 通用 name-value 项 */
    public static class NameValue { public String name; public long value; public NameValue(){} public NameValue(String n,long v){name=n;value=v;} }
    /** 薪资桶 */
    public static class BucketValue { public String bucket; public long value; public BucketValue(){} public BucketValue(String b,long v){bucket=b;value=v;} }

    /** 图表集合 */
    public static class Charts {
        public List<NameValue> byStatus;
        public List<NameValue> byCity;
        public List<NameValue> byCompany;
        public List<NameValue> byExperience;
        public List<NameValue> byDegree;
        public List<BucketValue> salaryBuckets;
        public List<NameValue> dailyTrend; // date 作为 name
    }

    /** 统计响应 */
    public static class StatsResponse { public Kpi kpi; public Charts charts; }

    /** 获取智联投递统计（带筛选） */
    public StatsResponse getZhilianStats(
            List<String> statuses,
            String location,
            String experience,
            String degree,
            Double minK,
            Double maxK,
            String keyword
    ) {
        QueryWrapper<ZhilianJobDataEntity> wrapper = new QueryWrapper<>();
        if (statuses != null && !statuses.isEmpty()) {
            wrapper.in("delivery_status", statuses.stream().filter(Objects::nonNull).map(String::trim).collect(Collectors.toSet()));
        }
        if (location != null && !location.trim().isEmpty()) wrapper.eq("location", location.trim());
        if (experience != null && !experience.trim().isEmpty()) wrapper.eq("experience", experience.trim());
        if (degree != null && !degree.trim().isEmpty()) wrapper.eq("degree", degree.trim());
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like("company_name", kw)
                    .or().like("job_title", kw));
        }

        wrapper.orderByDesc("create_time");
        List<ZhilianJobDataEntity> all = zhilianJobDataMapper.selectList(wrapper);

        List<ZhilianJobDataEntity> filtered = new ArrayList<>();
        for (ZhilianJobDataEntity e : all) {
            if (minK == null && maxK == null) {
                filtered.add(e);
            } else {
                SalaryInfo info = parseSalary(e.getSalary());
                if (info == null || info.medianK == null) continue;
                boolean ok = true;
                if (minK != null) ok = ok && (info.medianK >= minK);
                if (maxK != null) ok = ok && (info.medianK <= maxK);
                if (ok) filtered.add(e);
            }
        }

        Kpi kpi = new Kpi();
        kpi.total = filtered.size();
        kpi.delivered = filtered.stream().filter(e -> "已投递".equals(nullSafe(e.getDeliveryStatus()))).count();
        kpi.pending = filtered.stream().filter(e -> "未投递".equals(nullSafe(e.getDeliveryStatus()))).count();
        kpi.filtered = filtered.stream().filter(e -> "已过滤".equals(nullSafe(e.getDeliveryStatus()))).count();
        kpi.failed = filtered.stream().filter(e -> "投递失败".equals(nullSafe(e.getDeliveryStatus()))).count();
        {
            List<Double> medians = new ArrayList<>();
            for (ZhilianJobDataEntity e : filtered) {
                SalaryInfo info = parseSalary(e.getSalary());
                if (info == null || info.medianK == null) continue;
                medians.add(info.medianK);
            }
            kpi.avgMonthlyK = medians.isEmpty() ? null : medians.stream().mapToDouble(d -> d).average().orElse(0.0);
        }

        Charts charts = new Charts();
        charts.byStatus = new ArrayList<>();
        charts.byCity = new ArrayList<>();
        charts.byCompany = new ArrayList<>();
        charts.byExperience = new ArrayList<>();
        charts.byDegree = new ArrayList<>();
        charts.salaryBuckets = new ArrayList<>();
        charts.dailyTrend = new ArrayList<>();

        Map<String, Long> byStatus = filtered.stream()
                .collect(Collectors.groupingBy(e -> nullSafe(e.getDeliveryStatus()), Collectors.counting()));
        byStatus.forEach((k, v) -> charts.byStatus.add(new NameValue(k, v)));

        Map<String, Long> byCity = filtered.stream()
                .filter(e -> e.getLocation() != null && !e.getLocation().trim().isEmpty())
                .collect(Collectors.groupingBy(e -> nullSafe(e.getLocation()), Collectors.counting()));
        byCity.forEach((k, v) -> charts.byCity.add(new NameValue(k, v)));

        Map<String, Long> byCompany = filtered.stream()
                .filter(e -> e.getCompanyName() != null && !e.getCompanyName().trim().isEmpty())
                .collect(Collectors.groupingBy(e -> nullSafe(e.getCompanyName()), Collectors.counting()));
        byCompany.forEach((k, v) -> charts.byCompany.add(new NameValue(k, v)));

        Map<String, Long> byExp = filtered.stream()
                .filter(e -> e.getExperience() != null && !e.getExperience().trim().isEmpty())
                .collect(Collectors.groupingBy(e -> nullSafe(e.getExperience()), Collectors.counting()));
        byExp.forEach((k, v) -> charts.byExperience.add(new NameValue(k, v)));

        Map<String, Long> byDegree = filtered.stream()
                .filter(e -> e.getDegree() != null && !e.getDegree().trim().isEmpty())
                .collect(Collectors.groupingBy(e -> nullSafe(e.getDegree()), Collectors.counting()));
        byDegree.forEach((k, v) -> charts.byDegree.add(new NameValue(k, v)));

        // 每日趋势（按日期聚合 create_time）
        Map<String, Long> byDay = filtered.stream()
                .filter(e -> e.getCreateTime() != null)
                .collect(Collectors.groupingBy(e -> e.getCreateTime().toLocalDate().toString(), Collectors.counting()));
        byDay.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(en -> charts.dailyTrend.add(new NameValue(en.getKey(), en.getValue())));

        // 薪资桶
        long b0_10=0,b10_15=0,b15_20=0,b20_top=0,b_ge_top=0;
        double maxMedian = 0.0;
        List<Double> medians = new ArrayList<>();
        for (ZhilianJobDataEntity e : filtered) {
            SalaryInfo info = parseSalary(e.getSalary());
            if (info == null || info.medianK == null) continue;
            double m = info.medianK;
            medians.add(m);
            if (m > maxMedian) maxMedian = m;
        }
        int topEdge = (int) Math.ceil(maxMedian / 5.0) * 5;
        if (topEdge <= 20) topEdge = 25;
        for (double m : medians) {
            if (m < 10) b0_10++;
            else if (m < 15) b10_15++;
            else if (m < 20) b15_20++;
            else if (m < topEdge) b20_top++;
            else b_ge_top++;
        }
        charts.salaryBuckets.add(new BucketValue("0-10K", b0_10));
        charts.salaryBuckets.add(new BucketValue("10-15K", b10_15));
        charts.salaryBuckets.add(new BucketValue("15-20K", b15_20));
        charts.salaryBuckets.add(new BucketValue("20-" + topEdge + "K", b20_top));
        charts.salaryBuckets.add(new BucketValue(">=" + topEdge + "K", b_ge_top));

        StatsResponse resp = new StatsResponse();
        resp.kpi = kpi;
        resp.charts = charts;
        return resp;
    }

    /** 列表查询（分页 + 筛选 + 关键词 + 薪资区间基于中位数K） */
    public PagedResult listZhilianJobs(
            List<String> statuses,
            String location,
            String experience,
            String degree,
            Double minK,
            Double maxK,
            String keyword,
            int page,
            int size
    ) {
        if (page <= 0) page = 1;
        if (size <= 0) size = 20;

        QueryWrapper<ZhilianJobDataEntity> wrapper = new QueryWrapper<>();
        if (statuses != null && !statuses.isEmpty()) {
            wrapper.in("delivery_status", statuses.stream().filter(Objects::nonNull).map(String::trim).collect(Collectors.toSet()));
        }
        if (location != null && !location.trim().isEmpty()) wrapper.eq("location", location.trim());
        if (experience != null && !experience.trim().isEmpty()) wrapper.eq("experience", experience.trim());
        if (degree != null && !degree.trim().isEmpty()) wrapper.eq("degree", degree.trim());

        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like("company_name", kw)
                    .or().like("job_title", kw));
        }

        wrapper.orderByDesc("create_time");
        List<ZhilianJobDataEntity> all = zhilianJobDataMapper.selectList(wrapper);

        List<ZhilianJobDataEntity> filtered = new ArrayList<>();
        for (ZhilianJobDataEntity e : all) {
            if (minK == null && maxK == null) {
                filtered.add(e);
            } else {
                SalaryInfo info = parseSalary(e.getSalary());
                if (info == null || info.medianK == null) continue;
                boolean ok = true;
                if (minK != null) ok = ok && (info.medianK >= minK);
                if (maxK != null) ok = ok && (info.medianK <= maxK);
                if (ok) filtered.add(e);
            }
        }

        int total = filtered.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(total, from + size);

        PagedResult pr = new PagedResult();
        pr.items = filtered.subList(from, to);
        pr.total = total;
        pr.page = page;
        pr.size = size;
        return pr;
    }

    public static class PagedResult {
        public List<ZhilianJobDataEntity> items;
        public long total;
        public int page;
        public int size;
    }

    private static String nullSafe(String s) { return s == null ? "" : s.trim(); }
}