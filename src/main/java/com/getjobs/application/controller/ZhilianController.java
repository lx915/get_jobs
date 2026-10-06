package com.getjobs.application.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.entity.CookieEntity;
import com.getjobs.application.entity.ZhilianConfigEntity;
import com.getjobs.application.service.ConfigService;
import com.getjobs.application.service.CookieService;
import com.getjobs.application.service.ZhilianService;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.ZhilianJobService;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.getjobs.worker.zhilian.ZhilianConfig;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 智联招聘控制器（整合版）
 * 提供智联招聘平台配置管理、Cookie管理和登录状态控制的REST API接口
 */
@Slf4j
@RestController
@RequestMapping("/api/zhilian")
@CrossOrigin(origins = "*")
public class ZhilianController {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private ZhilianService zhilianService;

    @Autowired
    private PlaywrightManager playwrightManager;

    @Autowired
    private CookieService cookieService;

    @Autowired
    private ZhilianJobService zhilianJobService;

    @Autowired
    private ConfigService configService;

    // 投递进度 SSE 连接池。
    // 此前智联**完全没有**这个通道：executeDelivery 的 progressCallback 在 /start 里
    // 只写了一句 log.info，浏览器一个字都收不到 —— 这就是"点开始投递后没有任何动静"的直接原因。
    private final List<SseEmitter> zhilianProgressEmitters = new CopyOnWriteArrayList<>();

    // ==================== 投递进度 SSE ====================

    /** SSE - 智联投递任务进度推送 */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamZhilianProgress() {
        SseEmitter emitter = new SseEmitter(0L); // 永不超时
        zhilianProgressEmitters.add(emitter);

        emitter.onCompletion(() -> {
            log.info("智联进度SSE连接已完成");
            zhilianProgressEmitters.remove(emitter);
        });
        emitter.onTimeout(() -> {
            log.info("智联进度SSE连接超时");
            zhilianProgressEmitters.remove(emitter);
        });
        emitter.onError(e -> {
            log.error("智联进度SSE连接错误", e);
            zhilianProgressEmitters.remove(emitter);
        });

        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("message", "已连接到智联投递进度推送")));
        } catch (IOException e) {
            log.error("发送智联SSE连接消息失败", e);
        }
        return emitter;
    }

    private void sendZhilianProgress(JobProgressMessage message) {
        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : zhilianProgressEmitters) {
            try {
                emitter.send(SseEmitter.event().name("progress").data(objectMapper.writeValueAsString(message)));
            } catch (Exception e) {
                if (e instanceof AsyncRequestNotUsableException ||
                        e instanceof ClientAbortException ||
                        (e.getCause() instanceof ClientAbortException) ||
                        (e instanceof IOException && String.valueOf(e.getMessage()).contains("中止了一个已建立的连接"))) {
                    log.debug("智联进度 SSE 客户端已断开，移除连接: {}", e.getMessage());
                    try { emitter.complete(); } catch (Exception ignored) {}
                } else {
                    log.error("发送智联进度消息失败", e);
                }
                deadEmitters.add(emitter);
            }
        }
        zhilianProgressEmitters.removeAll(deadEmitters);
    }

    /** 心跳 - 智联进度 SSE */
    @Scheduled(fixedRate = 30000)
    public void heartbeatZhilianProgress() {
        if (zhilianProgressEmitters.isEmpty()) return;
        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : zhilianProgressEmitters) {
            try {
                emitter.send(SseEmitter.event().name("ping").data("keep-alive"));
            } catch (Exception e) {
                if (e instanceof AsyncRequestNotUsableException ||
                        e instanceof ClientAbortException ||
                        (e.getCause() instanceof ClientAbortException) ||
                        (e instanceof IOException && String.valueOf(e.getMessage()).contains("中止了一个已建立的连接"))) {
                    log.debug("智联进度 SSE 客户端已断开（心跳），移除连接: {}", e.getMessage());
                    try { emitter.complete(); } catch (Exception ignored) {}
                } else {
                    log.error("发送智联进度心跳失败", e);
                }
                deadEmitters.add(emitter);
            }
        }
        zhilianProgressEmitters.removeAll(deadEmitters);
    }

    // ==================== 配置管理相关接口 ====================

    /**
     * 获取 智联 配置与可选项
     */
    @GetMapping("/config")
    public Map<String, Object> getAllZhilianConfig() {
        Map<String, Object> result = new HashMap<>();

        ZhilianConfigEntity config = zhilianService.getFirstConfig();
        if (config == null) {
            config = new ZhilianConfigEntity();
        }

        Map<String, List<Map<String, String>>> options = new HashMap<>();
        options.put("city", toOptionList("city"));
        // 薪资 / 学历 / 公司人数：对应智联搜索页的 sl / el / cs 参数，
        // 取值代码见 ZhilianService.seedFilterOptions（薪资的 code 是"最低,最高"区间对）
        options.put("salary", toOptionList("salary"));
        options.put("education", toOptionList("education"));
        options.put("companySize", toOptionList("companySize"));

        result.put("config", config);
        result.put("options", options);
        return result;
    }

    /** 把 zhilian_option 里的某一类选项转成前端用的 {name, code} 列表 */
    private List<Map<String, String>> toOptionList(String type) {
        return zhilianService.getOptionsByType(type).stream().map(e -> {
            Map<String, String> m = new HashMap<>();
            m.put("name", e.getName());
            m.put("code", e.getCode());
            return m;
        }).collect(Collectors.toList());
    }

    /**
     * 更新 智联 配置（选择性更新第一条记录）
     */
    @PutMapping("/config")
    public ZhilianConfigEntity updateConfig(@RequestBody ZhilianConfigEntity config) {
        return zhilianService.updateConfig(config);
    }

    /**
     * 返回城市选项列表
     */
    @GetMapping("/config/options/city")
    public List<Map<String, String>> getCityOptions() {
        return zhilianService.getOptionsByType("city").stream().map(e -> {
            Map<String, String> m = new HashMap<>();
            m.put("name", e.getName());
            m.put("code", e.getCode());
            return m;
        }).collect(Collectors.toList());
    }

    // ==================== 只读诊断 ====================

    /**
     * 智联搜索页诊断（只读，不点任何按钮）。
     * <p>
     * 用途：智联改版频繁，一旦"搜不到岗位"或"采集不到数据"，需要能直接看到
     * 当前搜索页的真实 DOM，而不是靠猜。这里复用与投递完全相同的一份 URL 构建逻辑，
     * 把落地 URL、卡片数、关键选择器的命中数以及第一张卡片的原始 HTML 一并返回。
     * <p>
     * 与 {@code /api/playwright/diagnose} 的区别：那个是 Boss 专用（数的是 Boss 的卡片），
     * 这个数的是智联的 {@code div.job-card}。
     */
    @GetMapping("/inspect")
    public Map<String, Object> inspect(
            @RequestParam(value = "url", required = false) String url,
            @RequestParam(value = "keyword", required = false) String keyword) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            ZhilianConfig cfg = configService.getZhilianConfig();
            String kw = (keyword != null && !keyword.isBlank()) ? keyword
                    : (cfg.getKeywords() != null && !cfg.getKeywords().isEmpty() ? cfg.getKeywords().get(0) : "Java");
            String target = (url != null && !url.isBlank())
                    ? url : zhilianService.buildZhilianSearchUrl(cfg, kw, 1);

            Map<String, Object> cfgSummary = new LinkedHashMap<>();
            cfgSummary.put("keyword", kw);
            cfgSummary.put("cityCode", cfg.getCityCode());
            cfgSummary.put("salary", cfg.getSalary());
            cfgSummary.put("educationCodes", cfg.getEducationCodes());
            cfgSummary.put("companySizeCodes", cfg.getCompanySizeCodes());
            cfgSummary.put("debugger", cfg.isDebugger());
            out.put("config", cfgSummary);
            out.put("target", target);

            // 只读诊断期间先停掉后台登录监控，避免两边同时摸同一张 Page 撞出
            // "Object doesn't exist: response@..." 
            playwrightManager.pauseZhilianMonitoring();
            try {
                playwrightManager.callOnPlaywright(() -> {
                    Page page = playwrightManager.ensureZhilianPage();
                    try {
                        page.navigate(target, new Page.NavigateOptions()
                                .setTimeout(60_000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                    } catch (Exception e) {
                        out.put("navigateWarning", String.valueOf(e.getMessage()));
                    }
                    PlaywrightUtil.sleep(5);
                    out.put("currentUrl", page.url());
                    out.put("pageTitle", page.title());

                    String probeJs = """
                            () => {
                              const cards = document.querySelectorAll('div.job-card');
                              const first = cards.length ? cards[0] : null;
                              const c = (s) => document.querySelectorAll(s).length;
                              const hrefs = [];
                              document.querySelectorAll('a[href*="jobdetail"]').forEach((a, i) => {
                                if (i < 3) hrefs.push(a.getAttribute('href'));
                              });
                              // ==== 「只打招呼、不投简历」能力探测 ====
                              // 智联前端状态机 getDeliverBarState 的判定条件是：
                              //   showPreChat = 已登录 && positionDeliveryType ∈
                              //     {TWO_IM_AND_DELIVERY, THREE_CALL_AND_IM_AND_DELIVERY, TWO_IM_AND_SIGN_UP}
                              //     && 岗位未失效 && 非第三方(ats) && 非外部投递 && 尚未投递过
                              // showPreChat 为 true 时，详情页会多出一个「先聊聊」按钮（只发打招呼语、
                              // 不发简历）；为 false 时只有「立即投递」（= 简历 + 打招呼语）。
                              // 这里把页面内嵌状态里这几个字段的取值分布统计出来，回答"到底有没有岗位支持先聊聊"。
                              const html = document.documentElement.innerHTML;
                              const strStats = (key) => {
                                const out = {};
                                const parts = html.split(key + '":"');
                                for (let i = 1; i < parts.length; i++) {
                                  const v = parts[i].slice(0, parts[i].indexOf('"'));
                                  const k = v === '' ? '(空字符串)' : v;
                                  out[k] = (out[k] || 0) + 1;
                                }
                                return out;
                              };
                              const numStats = (key) => {
                                const out = {};
                                const parts = html.split(key + '":');
                                for (let i = 1; i < parts.length; i++) {
                                  const v = parts[i].slice(0, 8).split(/[^0-9-]/)[0];
                                  out[v] = (out[v] || 0) + 1;
                                }
                                return out;
                              };
                              const liao = [];
                              document.querySelectorAll('a,button,span,div').forEach((e) => {
                                if (e.children.length !== 0) return;
                                const t = (e.textContent || '').trim();
                                if (t && t.length <= 8 && t.indexOf('聊聊') >= 0) {
                                  liao.push({ tag: e.tagName, cls: String(e.className || '').slice(0, 60), text: t });
                                }
                              });
                              return {
                                cardCountAll: cards.length,
                                deliveryTypeStats: strStats('positionDeliveryType'),
                                imChatStatusStats: numStats('imChatStatusForChatBeforeDelivery'),
                                jobStatusStats: numStats('jobStatus'),
                                prechatTextHits: liao.slice(0, 12),
                                counts: {
                                  a_jobcard_name: c('a.job-card__name'),
                                  any_jobcard_name: c('.job-card__name'),
                                  name_link: c('.job-card__name--link'),
                                  company_name: c('.job-card__company-name'),
                                  salary: c('.job-card__salary'),
                                  seo_meta: c('.job-card__seo-meta'),
                                  location_span: c('div.job-card__location span'),
                                  jobdetail_links: c('a[href*="jobdetail"]'),
                                  apply_btn: c('button.job-detail-summary__apply')
                                },
                                firstHrefs: hrefs,
                                firstCardText: first ? first.innerText.replace(/\\s+/g, ' ').slice(0, 260) : '',
                                cardHtml: first ? first.outerHTML.slice(0, 2800) : ''
                              };
                            }""";
                    out.put("probe", page.evaluate(probeJs));

                    // 第二段：点开第一张卡片，把右侧详情面板的结构 dump 出来。
                    // 登录态下卡片是"紧凑版"，标题只是一个 span，**没有任何 jobdetail 链接**，
                    // 所以 jobId 只能从右侧详情面板取。这里只点卡片（不点投递），是只读的。
                    String detailJs = """
                            () => {
                              const card = document.querySelector('div.job-card');
                              if (card) card.click();
                              const pick = (sels) => {
                                for (const s of sels) { const e = document.querySelector(s); if (e) return e; }
                                return null;
                              };
                              return new Promise((resolve) => setTimeout(() => {
                                const panel = pick(['div.job-detail-panel','div.job-detail-modules',
                                                     'div.job-detail-summary','div.job-detail-card']);
                                const links = [];
                                document.querySelectorAll('a[href*="jobdetail"]').forEach((a) => links.push(a.getAttribute('href')));
                                const btn = document.querySelector('button.job-detail-summary__apply');
                                // 面板里的按钮清单 + 可见文字：用来回答"除了立即投递（＝投简历＋打招呼）
                                // 之外，还有没有'只打招呼/在线沟通'这类入口"。
                                const btns = [];
                                document.querySelectorAll(
                                  'div.job-detail-panel button, div.job-detail-panel a, div.job-detail-panel [role="button"]'
                                ).forEach((e) => btns.push({
                                  tag: e.tagName,
                                  cls: String(e.className || '').slice(0, 70),
                                  text: (e.textContent || '').replace(/\\s+/g, ' ').trim().slice(0, 24)
                                }));
                                // 全页扫描"沟通/聊一聊/打招呼"类入口：用来回答
                                // "能不能只打招呼、不投简历"。
                                const greetHits = [];
                                document.querySelectorAll('a,button,[role="button"],span').forEach((e) => {
                                  if (e.children.length !== 0) return;
                                  const t = (e.textContent || '').replace(/\\s+/g, ' ').trim();
                                  if (t && t.length <= 12 && /沟通|聊聊|打招呼|在线聊|咨询HR/.test(t)) {
                                    greetHits.push({ tag: e.tagName, cls: String(e.className || '').slice(0, 60), text: t });
                                  }
                                });
                                const allBtns = [];
                                document.querySelectorAll('button').forEach((e) => allBtns.push({
                                  cls: String(e.className || '').slice(0, 60),
                                  text: (e.textContent || '').replace(/\\s+/g, ' ').trim().slice(0, 20)
                                }));
                                resolve({
                                  panelClassName: panel ? panel.className : null,
                                  panelHtml: panel ? panel.outerHTML.slice(0, 3800) : '',
                                  panelText: panel ? panel.innerText.replace(/\\s+/g, ' ').slice(0, 1200) : '',
                                  panelButtons: btns.slice(0, 40),
                                  pageGreetHits: greetHits.slice(0, 20),
                                  pageButtons: allBtns.slice(0, 30),
                                  // 投递动作区原始 HTML：showPreChat 为 true 时，这里会多出「先聊聊」
                                  // 按钮；为 false 时只剩「立即投递」（v-if 落空会留下 <!----> 注释占位）。
                                  actionsHtml: (() => {
                                    const a = document.querySelector('div.job-detail-summary__actions');
                                    return a ? a.outerHTML.replace(/\\s+/g, ' ').slice(0, 1200) : '';
                                  })(),
                                  // 「聊聊」类文字（含带子节点的按钮），比上面按'沟通'扫更宽
                                  liaoHits: (() => {
                                    const out = [];
                                    document.querySelectorAll('a,button,span,div').forEach((e) => {
                                      const t = (e.textContent || '').replace(/\\s+/g, ' ').trim();
                                      if (t && t.length <= 8 && t.indexOf('聊聊') >= 0) {
                                        out.push({ tag: e.tagName, cls: String(e.className || '').slice(0, 60), text: t });
                                      }
                                    });
                                    return out.slice(0, 10);
                                  })(),
                                  jobdetailLinks: links.slice(0, 5),
                                  applyCount: document.querySelectorAll('button.job-detail-summary__apply').length,
                                  applyText: btn ? btn.textContent.trim() : '',
                                  titleTexts: Array.from(document.querySelectorAll('.job-detail-summary__title-text, .job-detail-card__title, h1'))
                                                 .slice(0, 3).map((e) => e.textContent.trim())
                                });
                              }, 3000));
                            }""";
                    try {
                        out.put("detail", page.evaluate(detailJs));
                    } catch (Exception e) {
                        out.put("detailError", String.valueOf(e.getMessage()));
                    }
                    return null;
                });
            } finally {
                playwrightManager.resumeZhilianMonitoring();
            }
            out.put("success", true);
        } catch (Exception e) {
            log.error("智联诊断失败", e);
            out.put("success", false);
            out.put("error", String.valueOf(e.getMessage()));
        }
        return out;
    }

    // ==================== 登录和认证相关接口 ====================

    /**
     * 检查登录状态
     * @return 登录状态信息
     */
    @GetMapping("/login-status")
    public ResponseEntity<Map<String, Object>> checkLoginStatus() {
        Map<String, Object> response = new HashMap<>();

        try {
            boolean isLoggedIn = playwrightManager.isLoggedIn("zhilian");
            response.put("success", true);
            response.put("isLoggedIn", isLoggedIn);
            response.put("message", isLoggedIn ? "已登录" : "未登录");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("检查登录状态失败", e);
            response.put("success", false);
            response.put("message", "检查登录状态失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 触发智联招聘登录：在未登录时点击二维码入口，等待扫码
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> triggerZhilianLogin() {
        Map<String, Object> response = new HashMap<>();
        try {
            playwrightManager.triggerZhilianLogin();
            response.put("success", true);
            response.put("message", "已尝试打开智联二维码登录入口，请在浏览器扫码登录");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("触发智联登录失败", e);
            response.put("success", false);
            response.put("message", "触发登录失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 退出登录：清空数据库Cookie并清理运行中的上下文Cookie
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logoutZhilian() {
        Map<String, Object> response = new HashMap<>();
        try {
            // 更新登录状态为未登录并触发SSE通知
            playwrightManager.setLoginStatus("zhilian", false);

            // 清空数据库中 智联招聘 平台的所有 Cookie 值
            cookieService.clearCookieByPlatform("zhilian", "manual logout");

            // 清理运行中的上下文Cookie
            try {
                playwrightManager.clearZhilianCookies();
            } catch (Exception e) {
                log.warn("清理智联招聘上下文Cookie时发生异常，但不影响退出流程: {}", e.getMessage());
            }

            response.put("success", true);
            response.put("message", "智联招聘已退出登录，数据库Cookie和上下文Cookie均已清理");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("退出登录失败", e);
            response.put("success", false);
            response.put("message", "退出登录失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ==================== Cookie管理相关接口 ====================

    /**
     * 调试接口：读取数据库中的 智联招聘 Cookie 记录
     */
    @GetMapping("/cookie")
    public ResponseEntity<Map<String, Object>> getZhilianCookieRecord() {
        Map<String, Object> response = new HashMap<>();
        try {
            CookieEntity cookie = cookieService.getCookieByPlatform("zhilian");
            Map<String, Object> data = new HashMap<>();
            if (cookie != null) {
                data.put("platform", cookie.getPlatform());
                data.put("cookie_value", cookie.getCookieValue());
                data.put("remark", cookie.getRemark());
                data.put("created_at", cookie.getCreatedAt());
                data.put("updated_at", cookie.getUpdatedAt());
            } else {
                data.put("platform", "zhilian");
                data.put("cookie_value", null);
                data.put("message", "未找到智联招聘Cookie记录");
            }
            response.put("success", true);
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "读取Cookie记录失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 调试接口：主动保存当前上下文中的 智联招聘 Cookie 到数据库
     */
    @PostMapping("/save-cookie")
    public ResponseEntity<Map<String, Object>> saveZhilianCookie() {
        Map<String, Object> response = new HashMap<>();
        try {
            playwrightManager.saveZhilianCookiesToDb("manual save");
            response.put("success", true);
            response.put("message", "已主动保存智联招聘Cookie到数据库");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "保存智联招聘Cookie失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ==================== 数据分析与列表 ====================

    /** 投递统计（Dashboard） */
    @GetMapping("/stats")
    public ZhilianService.StatsResponse stats(
            @RequestParam(value = "statuses", required = false) String statuses,
            @RequestParam(value = "location", required = false) String location,
            @RequestParam(value = "experience", required = false) String experience,
            @RequestParam(value = "degree", required = false) String degree,
            @RequestParam(value = "minK", required = false) Double minK,
            @RequestParam(value = "maxK", required = false) Double maxK,
            @RequestParam(value = "keyword", required = false) String keyword
    ) {
        java.util.List<String> statusList = null;
        if (statuses != null && !statuses.trim().isEmpty()) {
            statusList = java.util.Arrays.stream(statuses.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(java.util.stream.Collectors.toList());
        }
        return zhilianService.getZhilianStats(statusList, location, experience, degree, minK, maxK, keyword);
    }

    /** 岗位列表（分页 + 筛选） */
    @GetMapping("/list")
    public ZhilianService.PagedResult list(
            @RequestParam(value = "statuses", required = false) String statuses,
            @RequestParam(value = "location", required = false) String location,
            @RequestParam(value = "experience", required = false) String experience,
            @RequestParam(value = "degree", required = false) String degree,
            @RequestParam(value = "minK", required = false) Double minK,
            @RequestParam(value = "maxK", required = false) Double maxK,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
            @RequestParam(value = "size", required = false, defaultValue = "20") Integer size
    ) {
        java.util.List<String> statusList = null;
        if (statuses != null && !statuses.trim().isEmpty()) {
            statusList = java.util.Arrays.stream(statuses.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(java.util.stream.Collectors.toList());
        }
        return zhilianService.listZhilianJobs(statusList, location, experience, degree, minK, maxK, keyword, page, size);
    }

    /**
     * 地点选项（整表去重 + 岗位数），供前端地点筛选下拉直接选择。
     * 智联的地点粒度是「城市·区」，下拉里给完整值可避免用户手打猜格式。
     */
    @GetMapping("/locations")
    public List<Map<String, Object>> locations() {
        return zhilianService.getZhilianLocations();
    }

    // ==================== 任务管理相关接口 ====================

    /**
     * 启动智联招聘自动投递任务
     * @return 响应结果
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startZhilianJob() {
        Map<String, Object> response = new HashMap<>();

        try {
            // 未登录则不允许启动
            if (!playwrightManager.isLoggedIn("zhilian")) {
                response.put("success", false);
                response.put("message", "请先登录智联招聘");
                response.put("status", "not_logged_in");
                return ResponseEntity.badRequest().body(response);
            }

            // 检查是否已有任务在运行
            if (zhilianJobService.isRunning()) {
                response.put("success", false);
                response.put("message", "智联招聘任务已在运行中。如果长时间没有进展，先点\"停止投递\"再重新开始");
                response.put("status", "running");
                return ResponseEntity.badRequest().body(response);
            }

            // 异步启动新任务：进度既写日志，也通过 /api/zhilian/stream 推给前端
            CompletableFuture.runAsync(() -> {
                zhilianJobService.executeDelivery(progressMessage -> {
                    sendZhilianProgress(progressMessage);
                    log.info("[{}] {}", progressMessage.getPlatform(), progressMessage.getMessage());
                });
            });

            response.put("success", true);
            response.put("message", "智联招聘任务启动成功");
            response.put("status", "started");

            log.info("通过API启动智联招聘任务成功");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("启动智联招聘任务失败", e);
            response.put("success", false);
            response.put("message", "启动智联招聘任务失败: " + e.getMessage());
            response.put("error", e.getClass().getSimpleName());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 停止智联招聘任务
     * @return 响应结果
     */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stopZhilianJob() {
        Map<String, Object> response = new HashMap<>();

        try {
            if (!zhilianJobService.isRunning()) {
                // 任务可能已经自己跑完了。前端收到这个结果后会把按钮复位，不要当成错误弹框。
                response.put("success", false);
                response.put("status", "idle");
                response.put("message", "当前没有正在运行的智联招聘任务（可能已经结束）");
                return ResponseEntity.badRequest().body(response);
            }

            zhilianJobService.stopDelivery();

            response.put("success", true);
            response.put("status", "stopping");
            response.put("message", "智联招聘任务停止请求已发送");

            log.info("通过API停止智联招聘任务");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("停止智联招聘任务失败", e);
            response.put("success", false);
            response.put("message", "停止智联招聘任务失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 获取当前运行状态
     * @return 当前状态信息
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getCurrentStatus() {
        Map<String, Object> response = new HashMap<>();

        try {
            Map<String, Object> status = zhilianJobService.getStatus();
            response.put("success", true);
            response.putAll(status);
            response.put("timestamp", System.currentTimeMillis());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("获取当前状态失败", e);
            response.put("success", false);
            response.put("message", "获取状态失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ==================== 健康检查接口 ====================

    /**
     * 健康检查接口
     * @return 服务状态
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("service", "ZhilianController");
        response.put("status", "healthy");
        response.put("timestamp", System.currentTimeMillis());

        return ResponseEntity.ok(response);
    }

    // ==================== 辅助方法 ====================

    // 旧枚举构建方法已移除，改为从数据库读取
}
