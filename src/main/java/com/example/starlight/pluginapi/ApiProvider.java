package com.example.starlight.pluginapi;

/**
 * 启动器插件系统的 API 提供者接口。
 *
 * <p>设计原则（与《Starlight 插件生态规范》一致）：
 * <ul>
 *   <li>启动器是<strong>纯数据服务端</strong>：不渲染任何 UI，只负责接收请求、查询数据、返回数据字节流。</li>
 *   <li>启动器内部每一项功能（读取 region 文件、解析 NBT、获取生物群系、获取玩家数据……）
 *       都封装为一个独立的"功能类"，实现本接口后注册到中心调度类 {@code API} 中。</li>
 *   <li>Provider 内部可以自由调用启动器原有的功能类（如 {@code RegionFileReader}），
 *       但<strong>不要重构原有类</strong>，只做包装。</li>
 *   <li>Provider 只返回原始数据（JSON 或二进制字节流），绝对不包含任何 UI 逻辑或渲染代码——
 *       UI 展示完全由外部插件负责。</li>
 * </ul>
 */
public interface ApiProvider {

    /**
     * 该 API 的唯一标识，例如 {@code "system/ping"}。
     * 全工程（含所有插件）内必须唯一，注册时以此作为注册表 key。
     */
    String apiId();

    /**
     * 该 API 的语义化版本（SemVer，如 {@code "1.0.0"}）。
     * 插件握手时提交的 {@code required_apis} 会按 SemVer 规则与本值比对：
     * 主版本必须完全一致，启动器侧次版本/修订号必须 &gt;= 插件要求。
     */
    String apiVersion();

    /**
     * 处理一次 API 调用，返回数据字节流（UTF-8 JSON 或原始二进制均可）。
     *
     * @param request 请求信息：apiId、query 参数、请求体字节流（不可变）
     * @return 响应数据字节流
     * @throws Exception Provider 内部异常；HTTP 服务层会统一捕获并转换为错误响应
     */
    byte[] handle(ApiRequest request) throws Exception;
}
