package com.getjobs.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("zhilian_config")
public class ZhilianConfigEntity {
    @TableId(type = IdType.AUTO)
    /** 主键ID */
    private Long id;

    /** 搜索关键词（逗号或括号列表，例如 "[Java,后端]" 或 "Java,后端"） */
    private String keywords;

    /** 城市（中文名或代码，单值） */
    private String cityCode;

    /** 薪资范围（中文名或代码，单值） */
    private String salary;

    /**
     * 学历筛选（多选，逗号分隔的代码或中文名，例如 "4,3" 或 "本科,硕士"）。
     * <p>
     * 对应智联搜索页的 {@code el} 参数。留空 = 不限。
     * 代码取值已实测：1=博士 3=硕士 4=本科 5=大专 0=不限。
     */
    private String education;

    /**
     * 公司人数筛选（多选，逗号分隔的代码或中文名，例如 "2,3"）。
     * <p>
     * 对应智联搜索页的 {@code cs} 参数。留空 = 不限。
     * 代码取值已实测：1=20人以下 2=20-99人 3=100-299人 8=300-499人 4=500-999人
     * 5=1000-9999人 6=10000人以上。
     */
    private String companySize;

    /**
     * 调试模式：1=只搜索采集、不真正点击投递。
     * <p>
     * 与 Boss 模块的 debugger 字段同义。智联的学历/公司人数筛选刚上线，
     * 没有这个开关就只能靠真投递来验证筛选条件是否生效，代价太大。
     */
    private Integer debugger;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
