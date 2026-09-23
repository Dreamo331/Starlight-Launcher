package com.example.starlight.pluginapi;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 中心调度类 {@code API} —— 启动器全部功能的<strong>唯一出入口</strong>。
 *
 * <p>职责边界（与《Starlight 插件生态规范》一致）：
 * <ul>
 *   <li>只做<strong>调度</strong>：按 apiId 找到已注册的 {@link ApiProvider} 并执行；</li>
 *   <li>只做<strong>日志与调试</strong>：打印 API ID、参数、耗时、返回大小；</li>
 *   <li><strong>不包含任何具体业务逻辑</strong>（读 region、解析 NBT 等都在各 Provider 内部）；</li>
 *   <li><strong>不包含任何 UI 逻辑或渲染代码</strong>——只接收请求、查询数据、返回数据字节流。</li>
 * </ul>
 *
 * <p>启动器启动时，由引导代码（如 HTTP 服务启动类）依次调用
 * {@link #registerProvider(ApiProvider)} 注册内置功能类；外部插件一律通过
 * HTTP 端点访问，永远不直接触碰本类。
 */
public final class API {

    /**
     * 硬编码调试开关（需求指定）。
     * <ul>
     *   <li>{@code true}：控制台打印每次调用的 API ID、参数、耗时、返回大小；</li>
     *   <li>{@code false}：关闭调试日志，仅保留注册/告警级别的必要日志。</li>
     * </ul>
     * 发布版本前建议改为 {@code false}。
     */
    private static final boolean DEBUG = true;

    private static final Logger LOGGER = Logger.getLogger(API.class.getName());

    /** 单例实例：整个启动器进程内全局唯一的调度出入口。 */
    private static final API INSTANCE = new API();

    /**
     * 注册表：API ID → 功能类实现。
     * 使用 {@link ConcurrentHashMap} 保证 HTTP 服务多线程并发请求下的线程安全
     * （读多写少，且注册只发生在启动阶段）。
     */
    private final Map<String, ApiProvider> providers = new ConcurrentHashMap<>();

    /** 私有构造：禁止外部实例化，请使用 {@link #getInstance()}。 */
    private API() {
    }

    /** @return 全局唯一实例 */
    public static API getInstance() {
        return INSTANCE;
    }

    /**
     * 注册一个功能类（API Provider）。
     * API ID 重复时<strong>立即抛出异常</strong>：宁可启动期暴露配置冲突，也不让运行时静默覆盖。
     *
     * @param provider 功能类实例，不可为 null
     * @throws IllegalArgumentException provider 为 null
     * @throws IllegalStateException    API ID 已被其他 Provider 注册（冲突）
     */
    public void registerProvider(ApiProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider 不能为 null");
        }
        String id = provider.apiId();
        ApiProvider old = providers.putIfAbsent(id, provider);
        if (old != null) {
            throw new IllegalStateException(
                    "API ID 冲突：" + id + " 已被 " + old.getClass().getName() + " 注册，拒绝重复注册 " + provider.getClass().getName());
        }
        LOGGER.info("已注册 API: " + id + " v" + provider.apiVersion()
                + " (" + provider.getClass().getSimpleName() + ")");
    }

    /**
     * 注销功能类（预留）：插件/功能动态卸载或测试清理时使用。
     *
     * @param apiId 要注销的 API ID
     */
    public void unregisterProvider(String apiId) {
        providers.remove(apiId);
        LOGGER.info("已注销 API: " + apiId);
    }

    /** @return 指定 API 是否已注册 */
    public boolean hasApi(String apiId) {
        return providers.containsKey(apiId);
    }

    /**
     * 当前可用 API 清单（apiId → 版本号）。
     * 返回副本，供握手响应组装"可用 API 端点列表"返回给插件。
     */
    public Map<String, String> availableApis() {
        Map<String, String> snapshot = new HashMap<>();
        providers.forEach((id, p) -> snapshot.put(id, p.apiVersion()));
        return snapshot;
    }

    /**
     * 调度入口：按请求中的 apiId 找到功能类并执行，返回数据字节流。
     *
     * <p>本方法只负责调度、日志与调试，不含任何具体业务逻辑；
     * 异常统一转换为携带 HTTP 状态码的 {@link ApiException}，由 HTTP 服务层
     * 生成统一的错误 JSON 响应。
     *
     * @param request 请求信息（apiId、query 参数、请求体）
     * @return Provider 返回的数据字节流
     * @throws ApiException 404=未知 API；400=参数错误；500=Provider 内部错误
     */
    public byte[] dispatch(ApiRequest request) {
        long startNanos = System.nanoTime();
        String apiId = request.apiId();

        ApiProvider provider = providers.get(apiId);
        if (provider == null) {
            if (DEBUG) {
                LOGGER.warning("[API-DEBUG] 未找到 API: " + apiId + "（已注册 " + providers.size() + " 个）");
            }
            throw new ApiException(404, "未知 API: " + apiId);
        }

        try {
            byte[] result = provider.handle(request);
            if (DEBUG) {
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
                LOGGER.info(String.format(
                        "[API-DEBUG] apiId=%s params=%s 耗时=%dms 返回大小=%d bytes",
                        apiId, request.params(), elapsedMs, result == null ? 0 : result.length));
            }
            return result;
        } catch (ApiException e) {
            // Provider 主动抛出的、已带状态码的异常：原样上抛
            if (DEBUG) {
                LOGGER.warning("[API-DEBUG] apiId=" + apiId + " 调用失败(" + e.statusCode() + "): " + e.getMessage());
            }
            throw e;
        } catch (IllegalArgumentException e) {
            // 参数缺失/非法：统一映射为 400
            if (DEBUG) {
                LOGGER.warning("[API-DEBUG] apiId=" + apiId + " 参数错误: " + e.getMessage());
            }
            throw new ApiException(400, "参数错误: " + e.getMessage(), e);
        } catch (Throwable t) {
            // 兜底：Error（如 NoClassDefFoundError / ExceptionInInitializerError）也必须转成
            // ApiException 返回 —— 否则异常会逃逸到 HttpServer 工作线程使其静默死亡，
            // 插件请求将无限挂起（客户端只能超时）。线程自杀类 Error 除外。
            if (t instanceof ThreadDeath) {
                throw (ThreadDeath) t;
            }
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            if (DEBUG) {
                LOGGER.log(Level.SEVERE, "[API-DEBUG] apiId=" + apiId + " 调用失败(Throwable) 耗时=" + elapsedMs + "ms", t);
            }
            throw new ApiException(500, "API 调用失败: " + apiId + " -> " + t, t);
        }
    }
}
