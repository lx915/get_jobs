package com.getjobs.worker.zhilian;

import com.getjobs.worker.utils.JobUtils;
import lombok.Data;
import lombok.SneakyThrows;

import java.util.List;
import java.util.Objects;

/**
 * @author loks666
 * 项目链接: <a href="https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 */
@Data
public class ZhilianConfig {
    /**
     * 搜索关键词列表
     */
    private List<String> keywords;

    /**
     * 城市编码
     */
    private String cityCode;

    /**
     * 薪资范围
     */
    private String salary;

    /**
     * 学历筛选代码（对应智联 URL 参数 el，多选）。
     * 空列表 = 不限。实测：1=博士 3=硕士 4=本科 5=大专 0=不限。
     */
    private List<String> educationCodes;

    /**
     * 公司人数筛选代码（对应智联 URL 参数 cs，多选）。
     * 空列表 = 不限。实测：1=20人以下 2=20-99人 3=100-299人 8=300-499人
     * 4=500-999人 5=1000-9999人 6=10000人以上。
     */
    private List<String> companySizeCodes;

    /**
     * 调试模式：true 时只搜索/采集岗位，不真正点击「立即投递」。
     */
    private boolean debugger;


    // 注意：已改为在 ZhilianJobService 中通过 ConfigService 构建配置
    // 保留空的 init 以兼容旧调用，但建议不要再使用
    @SneakyThrows
    public static ZhilianConfig init() {
        throw new UnsupportedOperationException("请在 ZhilianJobService 中通过 ConfigService 构建配置");
    }

}
