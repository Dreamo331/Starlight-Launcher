package com.example.starlight.pluginapi.provider;

import com.example.starlight.pluginapi.ApiProvider;
import com.example.starlight.pluginapi.ApiRequest;
import com.example.starlight.world.RegionFileReader;

import java.io.IOException;

/**
 * 功能类（包装）：{@code world/region/chunk} —— 读取 region 文件中的区块原始数据。
 *
 * <p>把启动器原有的 {@link RegionFileReader} 能力封装成插件系统 API，
 * 使外部插件可以通过 HTTP 拉取指定 region 文件中指定坐标的区块数据（二进制字节流）。
 *
 * <p>遵循<strong>"不改动原有逻辑"</strong>原则：本类只是包装器，
 * 内部调用原有 {@code RegionFileReader}，不对其做任何重构。
 *
 * <p>Query 参数：
 * <ul>
 *   <li>{@code region}：region 文件路径（绝对路径，或相对 .minecraft 游戏目录的路径）</li>
 *   <li>{@code x}：区块 X 坐标（区块坐标，不是方块坐标）</li>
 *   <li>{@code z}：区块 Z 坐标</li>
 * </ul>
 *
 * <p>返回：区块原始字节流（NBT 压缩数据），由插件自行解析/展示。
 */
public final class RegionApiProvider implements ApiProvider {

    @Override
    public String apiId() {
        return "world/region/chunk";
    }

    @Override
    public String apiVersion() {
        return "1.0.0";
    }

    @Override
    public byte[] handle(ApiRequest request) throws IOException {
        String regionFile = request.param("region");
        String xStr = request.param("x");
        String zStr = request.param("z");
        if (regionFile == null || xStr == null || zStr == null) {
            throw new IllegalArgumentException("world/region/chunk 缺少必要参数：region / x / z");
        }
        int chunkX = Integer.parseInt(xStr);
        int chunkZ = Integer.parseInt(zStr);

        // 调用启动器原有的 region 读取能力，返回区块原始字节流。
        // TODO: 接入真实实现后，本行无需任何改动 —— 包装层与业务层解耦。
        return RegionFileReader.readChunkData(regionFile, chunkX, chunkZ);
    }
}
