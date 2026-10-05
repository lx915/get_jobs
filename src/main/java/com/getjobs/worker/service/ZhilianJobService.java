package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.zhilian.ZhiLian;
import com.getjobs.worker.zhilian.ZhilianConfig;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 智联招聘任务服务
 * 管理智联招聘平台的投递任务执行和状态
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZhilianJobService implements JobPlatformService {
    private static final String PLATFORM = "zhilian";

    private final PlaywrightManager playwrightManager;
    private final ObjectProvider<ZhiLian> zhilianProvider;
    private final ConfigService configService;

    // 任务运行状态
    private volatile boolean isRunning = false;
    // 停止标志
    private volatile boolean shouldStop = false;

    @Override
    public void executeDelivery(Consumer<JobProgressMessage> progressCallback) {
        if (isRunning) {
            progressCallback.accept(JobProgressMessage.warning(PLATFORM, "任务已在运行中"));
            return;
        }

        // ① 先在**调用方线程**上抢前台名额。
        //    这一步必须放在 runOnPlaywright **外面**：runOnPlaywright 会先把任务排进
        //    专用线程的队列，而 Boss 是把整轮 execute() 包在 callOnPlaywright 里的，
        //    整轮（可能几十分钟）都占着那条线程 —— 抢名额若放在里面，
        //    要等 Boss 跑完才轮到判断，界面上就是"点了开始投递什么都没发生"，
        //    用户永远看不到这条拒绝消息。
        String holder = playwrightManager.acquireForeground(PLATFORM);
        if (holder != null) {
            String msg = "另一个平台（" + holder + "）正在投递中，请等它结束后再启动智联。"
                    + "两个平台同时驱动同一个浏览器会互相打断，导致岗位被整体跳过。";
            log.warn("[zhilian] {}", msg);
            progressCallback.accept(JobProgressMessage.error(PLATFORM, msg));
            return;
        }

        // ② 真正的浏览器操作必须收敛到 Playwright 专用线程上串行执行。
        //    智联此前是**唯一**绕开那条线程的平台：/start 里 CompletableFuture.runAsync
        //    之后直接在 ForkJoinPool 线程上驱动 Playwright。两个平台同时跑时，
        //    两条线程会同时驱动同一条 Playwright Connection —— 实测 Boss 侧连丢 4 个关键词
        //    （每个 200+ 岗位全废），报 PlaywrightException: Object doesn't exist: frame@/handle@。
        try {
            playwrightManager.runOnPlaywright(() -> doExecuteDelivery(progressCallback));
        } finally {
            playwrightManager.releaseForeground(PLATFORM);
        }
    }

    private void doExecuteDelivery(Consumer<JobProgressMessage> progressCallback) {
        try {
            // 浏览器/页面可能已经被关掉：
            //  - 手动关窗口、Chrome 崩溃 -> browserClosed 置位，需要整体重启
            //  - 只把智联那个标签页关了 -> 其他字段仍然非空，但 zhilianPage 已失效
            // 两种情况都会让 page.navigate 抛 TargetClosedError。
            // Boss 侧早就有这一步（BossJobService 里的 ensureReady），智联侧一直漏着，
            // 表现就是"点了开始投递毫无动静，日志里却写着投递完成、共投递0个岗位"。
            progressCallback.accept(JobProgressMessage.info(PLATFORM, "检查浏览器状态..."));
            playwrightManager.ensureReady();

            // 获取智联招聘页面实例（页面被单独关掉时会自动重建并回到首页）
            Page page = playwrightManager.ensureZhilianPage();
            if (page == null) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "智联招聘页面不可用，请重启应用后重试"));
                return;
            }

            // 检查是否已登录
            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "请先登录智联招聘"));
                return;
            }

            // 通过校验后再标记运行
            isRunning = true;
            shouldStop = false;

            // 暂停后台登录监控，避免与投递流程并发访问同一Page
            playwrightManager.pauseZhilianMonitoring();

            // 加载配置（统一从 zhilian_config 专表读取）
            ZhilianConfig config = configService.getZhilianConfig();
            progressCallback.accept(JobProgressMessage.info(PLATFORM, "配置加载成功"));

            // 调试模式必须显式说出来：否则用户看到的仍然是一串"正在投递：XXX"，
            // 最后"共投递0个" —— 完全看不出这是干跑（和 Boss 侧同一类坑）。
            boolean dryRun = config.isDebugger();
            if (dryRun) {
                progressCallback.accept(JobProgressMessage.warning(PLATFORM,
                        "当前是【调试模式】：只搜索采集、不会真正投递。"
                                + "想真实投递，请在「平台配置」里把「调试模式」关掉并保存。"));
            }

            progressCallback.accept(JobProgressMessage.info(PLATFORM, "开始投递任务..."));

            // 创建ZhiLian实例并执行投递
            ZhiLian.ProgressCallback zhilianCallback = (level, message, current, total) -> {
                if ("error".equals(level)) {
                    progressCallback.accept(JobProgressMessage.error(PLATFORM, message));
                } else if ("warning".equals(level)) {
                    progressCallback.accept(JobProgressMessage.warning(PLATFORM, message));
                } else if (current != null && total != null) {
                    progressCallback.accept(JobProgressMessage.progress(PLATFORM, message, current, total));
                } else {
                    progressCallback.accept(JobProgressMessage.info(PLATFORM, message));
                }
            };

            ZhiLian zhilian = zhilianProvider.getObject();
            zhilian.setPage(page);
            zhilian.setConfig(config);
            zhilian.setProgressCallback(zhilianCallback);
            zhilian.setShouldStopCallback(this::shouldStop);
            zhilian.prepare();

            int deliveredCount = zhilian.execute();

            // "0 个" + "出过错" 不能说成"完成"：那正是用户看到"点了没动静"的原因之一
            if (dryRun) {
                progressCallback.accept(JobProgressMessage.warning(PLATFORM,
                        String.format("调试模式已结束：搜索/采集了岗位但一个都没投（共投递%d个）。"
                                + "关闭「调试模式」后重跑才会真实投递", deliveredCount)));
            } else if (zhilian.hasError() && deliveredCount == 0) {
                progressCallback.accept(JobProgressMessage.warning(PLATFORM,
                    String.format("投递任务结束，共投递%d个职位。过程中有异常，请查看上方日志", deliveredCount)));
            } else {
                progressCallback.accept(JobProgressMessage.success(PLATFORM,
                    String.format("投递任务完成，共投递%d个职位", deliveredCount)));
            }
        } catch (Exception e) {
            log.error("智联招聘投递任务执行失败", e);
            progressCallback.accept(JobProgressMessage.error(PLATFORM, "投递失败: " + e.getMessage()));
        } finally {
            isRunning = false;
            shouldStop = false;
            // 恢复后台登录监控
            try {
                playwrightManager.resumeZhilianMonitoring();
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void stopDelivery() {
        if (isRunning) {
            log.info("收到停止智联招聘投递任务的请求");
            shouldStop = true;
        }
    }

    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("platform", PLATFORM);
        status.put("isRunning", isRunning);
        status.put("isLoggedIn", playwrightManager.isLoggedIn(PLATFORM));
        return status;
    }

    @Override
    public String getPlatformName() {
        return PLATFORM;
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * 检查是否应该停止
     */
    public boolean shouldStop() {
        return shouldStop;
    }

    
}
