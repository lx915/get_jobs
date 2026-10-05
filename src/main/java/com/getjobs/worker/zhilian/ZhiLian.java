package com.getjobs.worker.zhilian;

import com.getjobs.application.entity.ZhilianJobDataEntity;
import com.getjobs.application.service.BlacklistService;
import com.getjobs.application.service.ZhilianService;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * @author loks666
 * 项目链接: <a href="https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 * <p>
 * 智联招聘自动投递 - Playwright版本
 * <p>
 * <b>2026-10 重写说明（适配智联新版搜索页）</b>
 * <p>
 * 智联把搜索页整体改版，旧实现赖以工作的 URL 结构与 DOM 全部失效，
 * 症状就是"通过本软件启动一个岗位都搜不到"（连关键词都对不上）。
 * 旧实现（上游 main 分支至今仍是这一版）有四处已经死掉：
 * <ol>
 *   <li>URL 用 {@code https://www.zhaopin.com/sou/jl{city}/p{n}?sl=} —— 现在 302 到
 *       {@code /jobs?jl=&sl=}，<b>关键词在跳转中丢失</b>，页面直接回"暂未找到符合你要求的职位"。</li>
 *   <li>关键词靠"在输入框里打字"提交 —— 新页面不接受这种方式，必须放进 URL 的 {@code kw}。</li>
 *   <li>"不限城市"被映射成 {@code jl=0} —— 新页面不认这个值，实测 {@code ?kw=Java&jl=0}
 *       返回 0 个岗位，而<b>完全不传 jl</b> 才是全国。这条最隐蔽：地址看着完全正常。</li>
 *   <li>选择器全废：{@code div.joblist-box__item / div.positionlist / a.jobinfo__name /
 *       p.jobinfo__salary / div.companyinfo__name / a.soupager__btn /
 *       button.collect-and-apply__btn} 在新页面上命中数<b>全部为 0</b>。</li>
 * </ol>
 * 新版 URL 形如：
 * {@code https://www.zhaopin.com/jobs?kw=Java&jl=538&el=4,3&cs=2,3&p=1}
 * （{@code kw} 关键词 / {@code jl} 城市 / {@code sl} 薪资 / {@code el} 学历 /
 * {@code cs} 公司人数 / {@code p} 页码，多选用逗号分隔，均已实测）。
 * <p>
 * 新版交互是左右分栏：左边 {@code div.job-card} 列表，点卡片把详情加载到右边
 * {@code div.job-detail-panel}，真正的投递按钮是右边的
 * {@code button.job-detail-summary__apply}（文字"立即投递"），
 * 成功后会弹 {@code div.deliver-greeting-modal}（标题"已向对方发送简历和打招呼语"）。
 * <p>
 * ⚠️ 还有一个坑：<b>登录态下卡片是"紧凑版"</b>，标题只是一个
 * {@code span.vue-clamp__text}，<b>既没有 a 标签也没有 jobdetail 链接</b>，
 * 因此卡片上拿不到 jobId。jobId 只能"点开卡片后从右侧详情面板的
 * {@code a[href*=jobdetail]} 里读" —— 所以本实现是"逐张卡片点开→读详情→投递"。
 */
@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class ZhiLian {

    @Setter
    private Page page;

    @Setter
    private ZhilianConfig config;

    @Setter
    private ProgressCallback progressCallback;

    @Setter
    private Supplier<Boolean> shouldStopCallback;

    private final List<Job> resultList = new ArrayList<>();
    private boolean isLimit = false;

    /**
     * 本次任务是否出现过异常/被跳过的关键词。
     * <p>
     * 以前所有异常都只写 log，execute() 最后仍然报"投递完成，共投递0个岗位"，
     * 界面上看就是"点了开始投递，什么都没发生，任务却显示已完成"。
     */
    private volatile boolean hasError = false;

    /** 岗位卡片（新版列表项） */
    private static final String CARD_SELECTOR = "div.job-card";
    /** 右侧详情面板 */
    private static final String DETAIL_PANEL = "div.job-detail-panel";
    /** 详情面板里的职位名 */
    private static final String DETAIL_TITLE = "span.job-detail-summary__title-text";
    /** 详情面板里的薪资 */
    private static final String DETAIL_SALARY = "span.job-detail-summary__salary";
    /** 详情面板里的投递按钮 */
    private static final String DETAIL_APPLY_BTN = "button.job-detail-summary__apply";
    /** 详情面板里的职位链接（jobId 的唯一来源） */
    private static final String DETAIL_JOB_LINK = "a[href*='jobdetail']";
    /** 投递成功弹窗 */
    private static final String GREETING_MODAL = "div.deliver-greeting-modal";

    /** 卡片标题的候选选择器：登录态是 span.vue-clamp__text，匿名态是 a.job-card__name */
    private static final String[] CARD_TITLE_SELECTORS = {
            ".job-card__title-main .vue-clamp__text",
            ".job-card__title-main",
            "a.job-card__name",
            ".job-card__name",
    };

    private static final int MAX_PAGES = 50;
    private static final int CARD_WAIT_MS = 15_000;
    private static final int DETAIL_WAIT_MS = 12_000;

    private final ZhilianService zhilianService;

    /** 黑名单服务：命中即「放弃投递」（不是搜索层过滤 —— 智联搜索本来也没有排除参数） */
    private final BlacklistService blacklistService;

    /**
     * 智联黑名单关键词，{@link #prepare()} 时从库里加载一次。
     * <p>
     * 三类对应 {@code boss_blacklist} 表里的 type（表名是历史包袱，表本身是通用的
     * {@code (type, value)} 结构）：{@code zhilian_company} / {@code zhilian_job} /
     * {@code zhilian_recruiter}。匹配是「子串包含 + 忽略大小写」，见 {@link #matchedTerm}。
     */
    private Set<String> blacklistCompanies = Set.of();
    private Set<String> blacklistJobs = Set.of();
    private Set<String> blacklistRecruiters = Set.of();

    /**
     * 进度回调接口
     * @param level  消息级别：info / warning / error
     * @param message 消息内容
     * @param current 当前进度，可为 null
     * @param total   总数，可为 null
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void accept(String level, String message, Integer current, Integer total);
    }

    /** 本次任务是否出现过异常 */
    public boolean hasError() {
        return hasError;
    }

    /** 准备工作：重置本次任务的状态 */
    public void prepare() {
        log.info("智联招聘准备工作开始...");
        resultList.clear();
        isLimit = false;
        hasError = false;
        // 黑名单：每轮任务开始时从库里读一次。和 Boss 侧（Boss.prepare()）完全对称。
        // 读失败不能拖垮整轮任务，所以逐个兜异常，失败就当空表。
        blacklistCompanies = safeBlacklist("zhilian_company");
        blacklistJobs = safeBlacklist("zhilian_job");
        blacklistRecruiters = safeBlacklist("zhilian_recruiter");
        log.info("黑名单加载完成: 公司({}) 岗位({}) 招聘者({})",
                blacklistCompanies.size(), blacklistJobs.size(), blacklistRecruiters.size());
        log.info("智联招聘准备工作完成");
    }

    private Set<String> safeBlacklist(String type) {
        try {
            Set<String> values = blacklistService.getValues(type);
            return values == null ? Set.of() : values;
        } catch (Exception e) {
            log.warn("加载黑名单失败 type={}: {}", type, e.getMessage());
            return Set.of();
        }
    }

    /**
     * 黑名单命中判定：<b>子串包含 + 忽略大小写 + 去首尾空格</b>。
     * <p>
     * 忽略大小写是为了绕开 Boss 侧的坑：那边用的是 Java {@code String.contains}，
     * 大小写敏感 —— 填 {@code pdd} 命中不了 {@code PDD科技}，用户很难发现。
     * 子串语义则是故意的：填「华为」必须能拦住「华为技术有限公司」。
     *
     * @return 命中的那个关键词（便于日志说清"被哪个词拦的"）；未命中返回 null
     */
    private String matchedTerm(Set<String> terms, String text) {
        if (terms == null || terms.isEmpty() || text == null || text.isBlank()) return null;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (term == null || term.isBlank()) continue;
            if (lower.contains(term.trim().toLowerCase(Locale.ROOT))) {
                return term.trim();
            }
        }
        return null;
    }

    /**
     * 执行投递任务
     * @return 投递数量
     */
    public int execute() {
        log.info("智联招聘投递任务开始...");
        long startTime = System.currentTimeMillis();

        try {
            List<String> keywords = config.getKeywords();
            if (keywords == null || keywords.isEmpty()) {
                sendWarning("没有配置任何搜索关键词，请在「平台配置」里填写关键词后重试");
                return 0;
            }

            for (String keyword : keywords) {
                if (shouldStop() || isLimit) {
                    sendProgress("用户取消投递或已达上限", null, null);
                    break;
                }
                deliverByKeyword(keyword);

                if (config.isDebugger()) {
                    // 调试模式只看第一个关键词的第一页，避免白跑十几分钟
                    sendProgress("调试模式：只看第一个关键词的第一页，结束", null, null);
                    break;
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            String message = String.format("智联招聘投递完成，共投递%d个岗位，用时%s",
                    resultList.size(), formatDuration(duration));
            log.info(message);
            sendProgress(message, null, null);

            if (!resultList.isEmpty()) {
                log.info("新投递公司如下:");
                resultList.forEach(job -> log.info(job.toString()));
            } else {
                log.info("未投递新的岗位...");
            }

        } catch (Exception e) {
            log.error("智联招聘投递过程出现异常", e);
            sendError("投递出现异常: " + e.getMessage());
        }

        return resultList.size();
    }

    /** 按关键词逐页搜索并投递 */
    private void deliverByKeyword(String keyword) {
        if (isLimit) return;

        try {
            log.info("开始投递关键词: {}", keyword);
            sendProgress("正在搜索关键词: " + keyword, null, null);

            for (int pageNum = 1; pageNum <= MAX_PAGES; pageNum++) {
                if (shouldStop() || isLimit) {
                    sendProgress("用户取消投递或已达上限", null, null);
                    return;
                }

                String url = buildSearchUrl(keyword, pageNum);
                log.info("【{}】打开第 {} 页: {}", keyword, pageNum, url);
                sendProgress("搜索地址: " + url, null, null);

                if (!navigateWithRetry(url, keyword)) {
                    sendError("关键词【" + keyword + "】第" + pageNum + "页打开失败，跳过该关键词");
                    return;
                }

                int count = waitForCards(pageNum);
                if (count == 0) {
                    if (pageNum == 1) {
                        // 这条提示就是原来最缺的东西：搜不到岗位时用户完全不知道是"关键词没命中"
                        // 还是"筛选太严"，只能看到一个"共投递0个岗位"。
                        sendWarning(String.format(
                                "关键词【%s】一个岗位都没搜到。请检查：①关键词是否过窄（建议先用“Java”“后端”这类大词）；"
                                        + "②城市 / 薪资 / 学历 / 公司人数 是否筛得过严；③该关键词下是否本来就没岗位。"
                                        + "当前搜索地址：%s", keyword, url));
                        hasError = true;
                    } else {
                        sendProgress(String.format("【%s】第%d页没有岗位，翻页结束", keyword, pageNum), pageNum, MAX_PAGES);
                    }
                    return;
                }

                log.info("【{}】第{}页检测到 {} 个岗位", keyword, pageNum, count);
                sendProgress(String.format("【%s】第%d页共%d个岗位", keyword, pageNum, count), pageNum, MAX_PAGES);

                if (!deliverCurrentPage(keyword, pageNum, count)) {
                    return;
                }

                if (config.isDebugger()) return;

                PlaywrightUtil.sleep(2);
            }

            log.info("关键词【{}】投递完成", keyword);
        } catch (Exception e) {
            log.error("投递关键词【{}】时出现异常", keyword, e);
            sendError("关键词【" + keyword + "】执行异常: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage());
        }
    }

    /**
     * 构建新版搜索 URL（实现在 ZhilianService，诊断接口复用同一份）。
     */
    private String buildSearchUrl(String keyword, int pageNum) {
        return zhilianService.buildZhilianSearchUrl(config, keyword, pageNum);
    }

    /**
     * 导航到搜索页。
     * <p>
     * ⚠️ 必须容错：智联的投递跑在 ForkJoinPool 线程上，而后台的「智联登录状态监控」
     * 每隔几秒会在 Playwright 专用线程上摸同一张 Page。两边撞上时 navigate 会抛
     * {@code PlaywrightException - Object doesn't exist: response@...} —— 页面其实已经到位了，
     * 但这个异常会把**整个关键词**整段跳过。这里靠"异常后再看 URL 是否已经在站内"判定成功，
     * 并多给一次重试（仓库里 setupZhilianPlatform 早就是这个口径）。
     */
    private boolean navigateWithRetry(String url, String keyword) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                page.navigate(url, new Page.NavigateOptions()
                        .setTimeout(60_000)
                        .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));
                PlaywrightUtil.sleep(2);
                return true;
            } catch (Exception navEx) {
                String currentUrl = null;
                try { currentUrl = page.url(); } catch (Exception ignored) {}
                if (currentUrl != null && currentUrl.contains("zhaopin.com")) {
                    log.warn("【{}】导航抛异常但页面已在智联站内，按已到位处理: {}", keyword, navEx.getMessage());
                    PlaywrightUtil.sleep(2);
                    return true;
                }
                if (attempt < 2) {
                    log.warn("【{}】导航失败（第 {} 次），2 秒后重试: {}", keyword, attempt, navEx.getMessage());
                    PlaywrightUtil.sleep(2);
                } else {
                    log.error("【{}】导航连续失败: {}", keyword, navEx.getMessage());
                }
            }
        }
        return false;
    }

    /** 等岗位卡片出现，返回当前卡片数（0 表示该关键词/筛选组合没有结果） */
    private int waitForCards(int pageNum) {
        try {
            page.waitForSelector(CARD_SELECTOR,
                    new Page.WaitForSelectorOptions().setTimeout(CARD_WAIT_MS));
        } catch (Exception e) {
            log.warn("第{}页等待岗位卡片超时（{}ms），按无岗位处理", pageNum, CARD_WAIT_MS);
        }
        return currentCardCount();
    }

    private int currentCardCount() {
        try {
            return page.locator(CARD_SELECTOR).count();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 处理当前页：逐张卡片点开→读右侧详情（拿 jobId 等权威字段）→（非调试模式）点投递。
     *
     * @return 是否继续投递下一页
     */
    private boolean deliverCurrentPage(String keyword, int pageNum, int total) {
        Set<String> processed = new HashSet<>();
        int newCollected = 0;
        int delivered = 0;

        try {
            for (int i = 0; i < total; i++) {
                if (shouldStop() || isLimit) {
                    sendProgress("用户取消投递或已达上限", null, null);
                    return false;
                }

                Locator cards = page.locator(CARD_SELECTOR);
                if (i >= cards.count()) break;    // 列表被刷短了
                Locator card = cards.nth(i);

                String cardTitle = firstNonEmpty(multiText(card, CARD_TITLE_SELECTORS)).trim();

                try {
                    card.scrollIntoViewIfNeeded();
                } catch (Exception ignored) {}
                PlaywrightUtil.sleep(1);

                try {
                    clickCard(card);
                } catch (Exception e) {
                    sendWarning(String.format("第%d个岗位点击失败，跳过: %s", i + 1, e.getMessage()));
                    continue;
                }
                PlaywrightUtil.sleep(1);

                // 等右侧详情面板切到这个岗位（面板是复用的，必须确认标题已经换过来，
                // 否则会拿上一个岗位的信息去投下一个岗位 —— 那是最危险的一类 bug）
                if (!waitDetailForTitle(cardTitle, DETAIL_WAIT_MS)) {
                    if (detailShowsApplied()) {
                        log.info("岗位【{}】详情显示已投递过，跳过", cardTitle);
                        continue;
                    }
                    sendWarning(String.format("第%d个岗位【%s】点开后右侧详情没有出现「立即投递」按钮，跳过",
                            i + 1, cardTitle.isEmpty() ? "(标题未取到)" : cardTitle));
                    continue;
                }

                String jobId = readDetailJobId();
                String title = firstNonEmpty(text(page.locator(DETAIL_TITLE).first()), cardTitle);
                String company = readDetailCompany();
                String salary = text(page.locator(DETAIL_SALARY).first());
                String[] tags = readDetailTags();
                // 详情面板顶部的 tag 顺序并不固定：常见是 [城市, 经验, 学历]，
                // 但也有 [城市, 学历]、[城市, 实习, 经验, 学历] 等变体。
                // 早期按位置硬取会把"实习"当成经验、"本科"当成经验的下一格，
                // 所以改成按词形分类。
                String[] classified = classifyTags(tags);
                String location = classified[0];
                String experience = classified[1];
                String degree = classified[2];

                String dedupKey = (jobId != null && !jobId.isEmpty())
                        ? jobId : (title + "|" + company);
                if (!processed.add(dedupKey)) {
                    log.info("本页已处理过岗位【{}】，跳过", title);
                    continue;
                }

                // ==================== 黑名单判定（「放弃投递」，不是搜索层过滤） ====================
                // 智联搜索参数只有正向筛选（kw/jl/sl/el/cs…，**没有任何排除项**），
                // 所以"按公司名/岗位名关键词不投"只能在这里做：命中就干脆不点「立即投递」。
                // 这一段必须早于 sendProgress("正在投递…") 与 clickApplyAndConfirm()，
                // 否则日志会先喊"正在投递"再跳过，用户以为投了其实没投。
                String publisherTitle = readDetailPublisherTitle();
                String hitCompanyTerm = matchedTerm(blacklistCompanies, company);
                String hitJobTerm = matchedTerm(blacklistJobs, title);
                String hitRecruiterTerm = matchedTerm(blacklistRecruiters, publisherTitle);
                boolean blacklistHit = hitCompanyTerm != null || hitJobTerm != null || hitRecruiterTerm != null;
                String hitCategory = hitCompanyTerm != null ? "公司"
                        : (hitJobTerm != null ? "岗位" : "招聘者");
                String hitTerm = hitCompanyTerm != null ? hitCompanyTerm
                        : (hitJobTerm != null ? hitJobTerm : hitRecruiterTerm);

                // 采集落库（只对新岗位）
                boolean isNew;
                try {
                    isNew = (jobId != null && !jobId.isEmpty())
                            ? !zhilianService.existsByJobId(jobId)
                            : !zhilianService.existsByTitleAndCompany(title, company);
                } catch (Exception e) {
                    isNew = false;
                }
                if (isNew) {
                    try {
                        ZhilianJobDataEntity entity = new ZhilianJobDataEntity();
                        entity.setJobId(jobId == null ? "" : jobId);
                        entity.setJobTitle(title);
                        entity.setJobLink(jobId == null || jobId.isEmpty() ? null
                                : "https://www.zhaopin.com/jobdetail/" + jobId + ".htm");
                        entity.setSalary(salary);
                        entity.setLocation(location);
                        entity.setExperience(experience);
                        entity.setDegree(degree);
                        entity.setCompanyName(company);
                        entity.setDeliveryStatus(blacklistHit ? "已过滤" : "未投递");
                        zhilianService.insertJob(entity);
                        newCollected++;
                        log.info("已保存岗位数据：jobId={}，title={}，company={}", jobId, title, company);
                    } catch (Exception e) {
                        log.warn("保存岗位数据失败: {}", e.getMessage());
                    }
                } else {
                    log.info("岗位已采集过，跳过入库：jobId={}，title={}", jobId, title);
                }

                if (blacklistHit) {
                    log.info("已放弃投递：命中{}黑名单 | 公司：{} | 岗位：{} | 关键词：{} | 发布者：{}",
                            hitCategory, company, title, hitTerm, publisherTitle);
                    sendWarning(String.format("已放弃投递（命中%s黑名单「%s」）：%s%s",
                            hitCategory, hitTerm,
                            title.isEmpty() ? "(岗位名未取到)" : title,
                            company.isEmpty() ? "" : " @ " + company));
                    // 老记录（已存在、状态还是「未投递」）也刷成「已过滤」，
                    // 否则列表里会长期留着一堆"永远不会被投递、却写着未投递"的行。
                    // 新入库的上面已经写成「已过滤」了，这里是幂等的。
                    try {
                        if (jobId != null && !jobId.isEmpty()) {
                            zhilianService.markFilteredByJobId(jobId);
                        } else {
                            zhilianService.markFilteredByTitleAndCompany(title, company);
                        }
                    } catch (Exception ignored) {
                    }
                    continue;
                }

                if (config.isDebugger()) {
                    continue;
                }

                sendProgress(String.format("正在投递（第%d/%d个）：%s%s", i + 1, total,
                                title.isEmpty() ? "(岗位名未取到)" : title,
                                company.isEmpty() ? "" : " @ " + company),
                        pageNum, MAX_PAGES);

                if (clickApplyAndConfirm()) {
                    delivered++;
                    markDelivered(jobId, title, company);
                    Job job = new Job();
                    job.setJobName(title);
                    job.setCompanyName(company);
                    job.setSalary(salary);
                    job.setJobInfo(experience + "·" + degree);
                    job.setJobArea(location);
                    resultList.add(job);
                    log.info("已投递：jobId={}，title={}，company={}", jobId, title, company);
                }
                PlaywrightUtil.sleep(2);
            }

            if (config.isDebugger()) {
                sendWarning(String.format(
                        "【调试模式】第%d页共%d个岗位：新采集%d个，一个都没投。"
                                + "（每个岗位都已点开右侧详情、确认「立即投递」按钮存在，说明投递链路可用）",
                        pageNum, total, newCollected));
            } else {
                sendProgress(String.format("第%d页处理完成：新增采集%d个，成功投递%d个",
                        pageNum, newCollected, delivered), pageNum, MAX_PAGES);
            }
            return true;

        } catch (Exception e) {
            log.error("投递当前页面失败", e);
            sendError("投递当前页面失败: " + e.getMessage());
            try {
                saveCurrentPageHtml();
                log.info("已保存当前页面到 src/main/java/com/getjobs/worker/zhilian/page.html 以便排查");
            } catch (Exception saveEx) {
                log.warn("保存当前页面HTML失败: {}", saveEx.getMessage());
            }
            return false;
        }
    }

    /**
     * 点击岗位卡片，把详情加载到右侧面板。
     * <p>
     * 卡片标题在匿名态是个 {@code target="_blank"} 链接，点击中心点有可能命中它而弹出新标签页。
     * 这里沿用仓库里原有的做法：点击前注册 onPage 监听，把由当前页打开的新窗口直接关掉。
     */
    private void clickCard(Locator card) {
        java.util.function.Consumer<Page> closer = newPopup -> {
            try {
                if (newPopup.opener() == page) {
                    try { newPopup.waitForLoadState(); } catch (Exception ignored) {}
                    try { PlaywrightUtil.sleep(200); } catch (Exception ignored) {}
                    try { newPopup.close(); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        };
        page.context().onPage(closer);
        try {
            card.click();
        } finally {
            try { page.context().offPage(closer); } catch (Exception ignored) {}
        }
    }

    /**
     * 等右侧详情面板切到指定岗位（标题一致）且「立即投递」按钮出现。
     */
    private boolean waitDetailForTitle(String expectedTitle, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (shouldStop()) return false;
            try {
                boolean hasApply = page.locator(DETAIL_PANEL + " " + DETAIL_APPLY_BTN).count() > 0;
                if (hasApply) {
                    String nowTitle = text(page.locator(DETAIL_TITLE).first()).trim();
                    if (expectedTitle == null || expectedTitle.isEmpty()
                            || nowTitle.isEmpty() || nowTitle.equals(expectedTitle)) {
                        return true;
                    }
                }
                if (detailShowsApplied()) return false;
            } catch (Exception ignored) {}
            PlaywrightUtil.sleep(1);
        }
        return false;
    }

    /** 详情面板是否显示"已投递"（说明这个岗位之前投过） */
    private boolean detailShowsApplied() {
        try {
            Locator b = page.locator(DETAIL_PANEL + " " + DETAIL_APPLY_BTN);
            if (b.count() == 0) return false;
            String t = b.first().textContent();
            String cls = b.first().getAttribute("class");
            if (t != null && (t.contains("已投递") || t.contains("继续沟通"))) return true;
            return cls != null && cls.contains("disabled");
        } catch (Exception e) {
            return false;
        }
    }

    /** jobId 只能从详情面板的 jobdetail 链接里取（登录态卡片上没有链接） */
    private String readDetailJobId() {
        String[] sels = {DETAIL_PANEL + " " + DETAIL_JOB_LINK, DETAIL_JOB_LINK};
        for (String sel : sels) {
            try {
                Locator l = page.locator(sel);
                if (l.count() > 0) {
                    String id = extractJobIdFromLink(l.first().getAttribute("href"));
                    if (id != null && !id.isEmpty()) return id;
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    private String readDetailCompany() {
        String[] sels = {
                DETAIL_PANEL + " .job-detail-summary__company-name",
                DETAIL_PANEL + " a.job-company-info__name",
                DETAIL_PANEL + " .job-detail-summary__company span",
                ".job-detail-summary__company-name",
        };
        return multiText(page, sels).trim();
    }

    /**
     * 详情面板里「职位发布者」的职位/姓名，用于招聘者黑名单（对应 Boss 的 recruiter 类型）。
     * <p>
     * 新版详情页把发布者放在单独的 {@code job-detail-publisher-card} 里，
     * 选择器取自实际 DOM（匿名页 SSR 里抓到的结构，例如
     * {@code 人才经纪人-经营性招聘服务 · 上海神瀚人力资源有限公司}）。
     * <p>
     * ⚠️ 登录态下这块**不一定渲染**（实测列表页右侧面板只到「职位描述」为止）。
     * 取不到就返回空串，而空串永远不会命中黑名单 —— 所以最坏情况只是
     * "招聘者黑名单不生效"，不会误伤任何岗位。
     */
    private String readDetailPublisherTitle() {
        String[] sels = {
                DETAIL_PANEL + " .job-detail-publisher__job-title",
                DETAIL_PANEL + " .job-detail-publisher__name",
                ".job-detail-publisher__job-title",
                ".job-detail-publisher__name",
        };
        return multiText(page, sels).trim();
    }

    /** 详情面板顶部的 tag 列表，通常依次是 [城市, 经验, 学历] */
    private String[] readDetailTags() {
        List<String> out = new ArrayList<>();
        try {
            Locator tags = page.locator(DETAIL_PANEL + " li.job-detail-summary__tag");
            int n = tags.count();
            for (int i = 0; i < n; i++) {
                String t = tags.nth(i).textContent();
                if (t != null && !t.trim().isEmpty()) out.add(t.trim());
            }
        } catch (Exception ignored) {}
        return out.toArray(new String[0]);
    }

    /**
     * 把详情面板的 tag 列表归类成 [城市, 经验, 学历]。
     * <p>
     * tag 的顺序不固定（[城市,经验,学历] / [城市,学历] / [城市,实习,经验,学历] 都出现过），
     * 按位置硬取会把"实习"当成经验、"本科"当成经验，投递分析里的分布就全错了。
     */
    private String[] classifyTags(String[] tags) {
        String loc = "", exp = "", deg = "";
        if (tags != null) {
            for (String t : tags) {
                if (t == null || t.isEmpty()) continue;
                if (isDegreeWord(t)) {
                    if (deg.isEmpty()) deg = t;
                } else if (isExperienceWord(t)) {
                    if (exp.isEmpty()) exp = t;
                } else if (loc.isEmpty()) {
                    loc = t;
                }
            }
        }
        return new String[]{loc, exp, deg};
    }

    private boolean isDegreeWord(String t) {
        return t.contains("大专") || t.contains("本科") || t.contains("硕士") || t.contains("博士")
                || t.contains("中专") || t.contains("学历不限")
                || "高中".equals(t) || "初中及以下".equals(t) || "MBA".equalsIgnoreCase(t);
    }

    private boolean isExperienceWord(String t) {
        if (t.contains("经验不限") || t.contains("应届") || t.contains("实习") || t.contains("在校")) {
            return true;
        }
        return t.matches("^[0-9]+.*年.*$") || t.matches("^[0-9]+年以上$");
    }

    /** 点「立即投递」并等成功弹窗；返回是否确认投递成功 */    private boolean clickApplyAndConfirm() {
        try {
            Locator apply = page.locator(DETAIL_PANEL + " " + DETAIL_APPLY_BTN).first();
            try {
                apply.scrollIntoViewIfNeeded();
            } catch (Exception ignored) {}
            apply.click();

            boolean ok = waitForGreetingModal(10_000);
            closeGreetingModal();
            if (!ok) {
                if (checkIsLimit()) return false;
                sendWarning("点击「立即投递」后没有出现成功提示，可能被限流或该岗位需要先沟通，已跳过");
                return false;
            }
            return true;
        } catch (Exception e) {
            sendWarning("点击「立即投递」失败: " + e.getMessage());
            return false;
        }
    }

    /** 等投递成功弹窗真正显示出来 */
    private boolean waitForGreetingModal(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (shouldStop()) return false;
            try {
                Locator modal = page.locator(GREETING_MODAL);
                if (modal.count() > 0 && modal.first().isVisible()) {
                    String title = text(modal.first().locator("h3.deliver-greeting-modal__title"));
                    return title.isEmpty() || title.contains("已向对方发送");
                }
            } catch (Exception ignored) {}
            PlaywrightUtil.sleep(1);
        }
        return false;
    }

    /**
     * 关闭投递成功弹窗，选「留在此页」以便继续投递下一个。
     */
    private void closeGreetingModal() {
        try {
            Locator stay = page.locator("button.deliver-greeting-modal__btn--secondary");
            if (stay.count() > 0 && stay.first().isVisible()) {
                stay.first().click();
                PlaywrightUtil.sleep(1);
                return;
            }
        } catch (Exception ignored) {}
        try {
            Locator close = page.locator("button.deliver-greeting-modal__close");
            if (close.count() > 0 && close.first().isVisible()) {
                close.first().click();
                PlaywrightUtil.sleep(1);
            }
        } catch (Exception ignored) {}
    }

    /** 检查是否达到今日投递上限 */
    private boolean checkIsLimit() {
        try {
            Locator hit = page.locator("text=达到上限");
            if (hit.count() > 0) {
                log.info("今日投递已达上限！");
                isLimit = true;
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void markDelivered(String jobId, String title, String company) {
        try {
            if (jobId != null && !jobId.isEmpty()) {
                zhilianService.markDeliveredByJobId(jobId);
            } else if (!title.isEmpty() && !company.isEmpty()) {
                zhilianService.markDeliveredByTitleAndCompany(title, company);
            }
        } catch (Exception ex) {
            log.warn("更新投递状态失败: {}", ex.getMessage());
        }
    }

    private String extractJobIdFromLink(String link) {
        if (link == null) return null;
        try {
            int i = link.indexOf("jobdetail/");
            int j = link.lastIndexOf(".htm");
            if (i >= 0 && j > i) {
                return link.substring(i + "jobdetail/".length(), j);
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ==================== 小工具 ====================

    /** 在 parent 下按候选选择器依次取文本，返回第一个非空的 */
    private String multiText(Locator parent, String[] selectors) {
        for (String sel : selectors) {
            String t = text(parent.locator(sel));
            if (!t.isEmpty()) return t;
        }
        return "";
    }

    /** 页面级版本（page 不是 Locator，需要一个重载） */
    private String multiText(Page p, String[] selectors) {
        for (String sel : selectors) {
            String t = text(p.locator(sel));
            if (!t.isEmpty()) return t;
        }
        return "";
    }

    /** 取 locator 的文本（count 为 0 返回空串） */
    private String text(Locator locator) {
        try {
            if (locator.count() == 0) return "";
            String t = locator.first().textContent();
            return t == null ? "" : t.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    private String at(String[] arr, int idx) {
        if (arr == null || idx < 0 || idx >= arr.length) return "";
        return arr[idx] == null ? "" : arr[idx];
    }

    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        if (hours > 0) {
            return String.format("%d小时%d分钟", hours, minutes % 60);
        } else if (minutes > 0) {
            return String.format("%d分钟%d秒", minutes, seconds % 60);
        } else {
            return String.format("%d秒", seconds);
        }
    }

    /** 把当前页面内容保存到项目内 page.html，覆盖原文件（排障用） */
    private void saveCurrentPageHtml() {
        try {
            String html = page.content();
            java.nio.file.Path path = java.nio.file.Paths.get("src/main/java/com/getjobs/worker/zhilian/page.html");
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.write(path, html.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("写入 page.html 失败", e);
        }
    }

    private void sendProgress(String message, Integer current, Integer total) {
        if (progressCallback != null) {
            progressCallback.accept("info", message, current, total);
        }
    }

    private void sendWarning(String message) {
        log.warn(message);
        if (progressCallback != null) {
            progressCallback.accept("warning", message, null, null);
        }
    }

    private void sendError(String message) {
        hasError = true;
        log.error(message);
        if (progressCallback != null) {
            progressCallback.accept("error", message, null, null);
        }
    }

    private boolean shouldStop() {
        return shouldStopCallback != null && shouldStopCallback.get();
    }
}
