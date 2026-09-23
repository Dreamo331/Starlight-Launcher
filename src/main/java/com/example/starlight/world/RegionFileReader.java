package com.example.starlight.world;

/**
 * 【占位桩】Region 文件读取器 —— 待接入启动器真实实现。
 *
 * <p>本类仅为 {@code com.example.starlight.pluginapi.provider.RegionApiProvider}
 * 提供编译期依赖（保证工程可编译），当前所有方法只抛出
 * {@link UnsupportedOperationException}。
 *
 * <p>接入真实实现（解析 Anvil 格式 region 文件：偏移表 + 扇区定位 + zlib 解压，
 * 返回区块 NBT 数据）后，请删除本桩类，并保持下述方法签名不变。
 *
 * @param regionFilePath region 文件路径（*.mca）
 * @param chunkX         区块 X 坐标
 * @param chunkZ         区块 Z 坐标
 * @return 区块原始字节流（NBT 压缩数据）
 */
public final class RegionFileReader {

    private RegionFileReader() {
        // 工具类：禁止实例化
    }

    public static byte[] readChunkData(String regionFilePath, int chunkX, int chunkZ) {
        // TODO: 接入真实的 Anvil region 解析实现（偏移表 + 扇区定位 + 解压）。
        throw new UnsupportedOperationException(
                "RegionFileReader 尚未接入真实实现（占位桩），请实现后删除本桩类");
    }
}
