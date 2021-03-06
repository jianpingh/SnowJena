package cn.yueshutong.core.rateLimiter;

import cn.yueshutong.commoon.entity.LimiterRule;
import cn.yueshutong.core.config.RateLimiterConfig;
import cn.yueshutong.monitor.entity.MonitorBean;
import cn.yueshutong.monitor.client.MonitorService;
import cn.yueshutong.monitor.client.MonitorServiceImpl;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 令牌桶算法、漏桶算法
 * 单点、无锁、纳秒级流控
 * 分布式、无锁、秒级流控
 */
public class RateLimiterDefault implements RateLimiter {
    private AtomicLong bucket = new AtomicLong(0); //令牌桶初始容量：0
    private LimiterRule rule;
    private LimiterRule.Rule crule;
    private ScheduledFuture<?> scheduledFuture;
    private RateLimiterConfig config;

    @Override
    public MonitorService getMonitorService() {
        return monitorService;
    }

    private MonitorService monitorService =  new MonitorServiceImpl();

    public RateLimiterDefault(LimiterRule rule, RateLimiterConfig config) {
        this.config = config;
        init(rule);
    }

    @Override
    public void init(LimiterRule rule) {
        this.rule = rule;
        this.crule = rule.new Rule();
        if (scheduledFuture != null) {
            distory();
        }
        if (rule.getQps() != 0) {
            putBucket();
        }
    }

    /**
     * Monirot
     */
    @Override
    public boolean tryAcquire() {
        MonitorBean monitor = new MonitorBean();
        monitor.setLocalDateTime(LocalDateTime.now());
        monitor.setPre(1);
        monitor.setApp(rule.getApp());
        monitor.setId(rule.getId());
        monitor.setName(rule.getName());
        boolean b = tryAcquireFact(); //fact
        if (b){
            monitor.setAfter(1);
        }
        config.getScheduled().execute(() -> { //异步执行
            monitorService.save(monitor);
        });
        return b;
    }

    /**
     * AcquireModel
     */
    private boolean tryAcquireFact() {
        if (rule.getQps() == 0) {
            return false;
        }
        switch (rule.getAcquireModel()) {
            case FAILFAST:
                return tryAcquireFailed();
            case BLOCKING:
                return tryAcquireSucceed();
        }
        return false;
    }

    /**
     * 黑/白名单
     */
    @Override
    public boolean tryAcquire(String o) {
        boolean allow;
        switch (rule.getRuleAuthority()) {
            case AUTHORITY_BLACK:
                allow = Stream.of(rule.getLimitApp()).noneMatch(s -> s.equals(o));
                break;
            case AUTHORITY_WHITE:
                allow = Arrays.asList(rule.getLimitApp()).contains(o);
                break;
            default:
                allow = true;
        }
        return allow && tryAcquire();
    }

    @Override
    public String getId() {
        return rule.getId();
    }

    @Override
    public LimiterRule getLimiterRule() {
        return this.rule;
    }

    /**
     * CAS获取令牌,阻塞直到成功
     */
    public boolean tryAcquireSucceed() {
        long l = bucket.longValue();
        while (!(l > 0 && bucket.compareAndSet(l, l - 1))) {
            sleep();
            l = bucket.longValue();
        }
        return true;
    }

    /**
     * CAS获取令牌,没有令牌立即失败
     */
    public boolean tryAcquireFailed() {
        long l = bucket.longValue();
        while (l > 0) {
            if (bucket.compareAndSet(l, l - 1)) {
                return true;
            }
            l = bucket.longValue();
        }
        return false;
    }

    /**
     * 周期性放令牌，控制访问速率
     */
    private void putBucket() {
        this.scheduledFuture = config.getScheduled().scheduleAtFixedRate(() -> {
            if (crule.getSize() > bucket.longValue()) {
                bucket.incrementAndGet();
            }
        }, rule.getInitialDelay(), crule.getPeriod(), TimeUnit.NANOSECONDS);
    }

    public void distory() {
        this.scheduledFuture.cancel(true);
    }

    private void sleep() {
        if (crule.getPeriod() < 1 * 1000 * 1000) { //大于1ms强制休眠
            return;
        }
        try {
            Thread.sleep(crule.getPeriod() / 1000 / 1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

}
