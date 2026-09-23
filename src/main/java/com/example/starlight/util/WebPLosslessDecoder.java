package com.example.starlight.util;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * 轻量纯 Java <b>WebP 无损（VP8L）</b>解码器。
 *
 * <p>为什么需要它：Modrinth 的封面接口返回的 {@code icon_url} 如今普遍是 VP8L 无损 WebP
 * （形如 {@code https://cdn.modrinth.com/data/<id>/<hash>_96.webp}），而 JDK 的 {@code ImageIO}
 * 与 JavaFX 17 都<b>不支持 WebP</b>，直接解码必然失败（封面变灰块）。
 * 实测这些缩略图全部是 {@code VP8L} 块（不是需要完整 VP8 帧解码器的有损 {@code VP8 }），
 * 所以可以用一份几百行的自包含实现搞定，不需要引入任何第三方依赖或本地库。
 *
 * <p>实现范围：VP8L 无损静态图（含预测变换、交叉颜色变换、减法绿色、颜色索引/调色板、
 * 元 Huffman、颜色缓存、LZ77 回引用）。<b>不支持</b>有损 {@code VP8 }、动画 {@code ANIM}、
 * 以及 {@code ALPH} 独立 alpha 通道（这些属于有损/动画 WebP，本解码器会返回 {@code null}）。
 *
 * <p>格式参考：WebP Lossless Bitstream Specification。
 */
public final class WebPLosslessDecoder {

    private WebPLosslessDecoder() {
    }

    /** 判断字节流是否是 WebP（RIFF....WEBP） */
    public static boolean isWebP(byte[] data) {
        return data != null && data.length >= 16
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
    }

    /** 该字节流是否是本解码器支持的无损 VP8L（有损/动画返回 false） */
    public static boolean isSupported(byte[] data) {
        if (!isWebP(data)) return false;
        return findChunk(data, "VP8L") != null;
    }

    /** 排查用：设置系统属性 {@code -Dstarlight.webp.debug=true} 可打印失败原因 */
    private static boolean debugEnabled() {
        return Boolean.getBoolean("starlight.webp.debug");
    }

    private static void debug(String msg) {
        if (debugEnabled()) System.err.println("[WebP] " + msg);
    }

    /**
     * 解码 WebP 无损图。
     *
     * @return ARGB 位图；输入不是受支持的 VP8L（有损 / 动画 / 数据损坏）时返回 {@code null}
     */
    public static BufferedImage decode(byte[] data) {
        try {
            int[] chunk = findChunk(data, "VP8L");
            if (chunk == null) return null;
            BitReader br = new BitReader(data, chunk[0], chunk[1]);

            int signature = br.readBits(8);
            if (signature != 0x2F) return null;              // VP8L 签名
            int width = br.readBits(14) + 1;
            int height = br.readBits(14) + 1;
            boolean alphaUsed = br.readBits(1) != 0;
            int version = br.readBits(3);
            if (version != 0) return null;
            debug("尺寸 " + width + "x" + height + " alpha=" + alphaUsed);

            int[] argb = new Decoder(br).decodeImage(width, height, true);
            if (argb == null) {
                debug("decodeImage 返回 null");
                return null;
            }
            if (!alphaUsed) {
                for (int i = 0; i < argb.length; i++) argb[i] |= 0xFF000000;
            }

            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            img.setRGB(0, 0, width, height, argb, 0, width);
            return img;
        } catch (Throwable t) {
            // 解码失败一律返回 null，由调用方回退到占位图，不让异常影响界面
            if (debugEnabled()) t.printStackTrace(System.err);
            return null;
        }
    }

    /** 在 RIFF 容器里定位指定 FOURCC 的块，返回 {payloadOffset, payloadLength}；找不到返回 null */
    private static int[] findChunk(byte[] data, String fourCC) {
        int pos = 12;
        while (pos + 8 <= data.length) {
            String id = new String(data, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = readLE32(data, pos + 4);
            if (size < 0 || pos + 8 + size > data.length) return null;
            if (id.equals(fourCC)) return new int[]{pos + 8, size};
            if (id.equals("VP8X")) {
                // 扩展容器：跳过 10 字节头继续找里面的 VP8L
                pos += 8 + size + (size & 1);
                continue;
            }
            if (id.equals("VP8 ")) return null;              // 有损，不支持
            pos += 8 + size + (size & 1);                    // 块按偶数对齐
        }
        return null;
    }

    private static int readLE32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    // ==================== 位读取器（LSB first） ====================

    private static final class BitReader {
        private final byte[] data;
        private int pos;
        private int end;
        private long bitBuf;
        private int bitCount;
        private boolean eos;

        BitReader(byte[] data, int offset, int length) {
            this.data = data;
            this.pos = offset;
            this.end = Math.min(data.length, offset + length);
        }

        int readBits(int n) {
            if (n == 0) return 0;
            while (bitCount < n) {
                if (pos >= end) { eos = true; return 0; }
                bitBuf |= (long) (data[pos++] & 0xFF) << bitCount;
                bitCount += 8;
            }
            int v = (int) (bitBuf & ((1L << n) - 1));
            bitBuf >>>= n;
            bitCount -= n;
            return v;
        }

        boolean isEos() {
            return eos || (pos >= end && bitCount == 0);
        }
    }

    // ==================== Huffman ====================

    /** 规范 Huffman 解码树：counts/symbols 按（码长，符号）排序，用 zlib 式逐位下降解码 */
    private static final class HuffmanTree {
        private final int[] counts = new int[16];
        private final int[] symbols;
        private final int singleSymbol;      // >= 0 表示整棵树只有这一个符号（不消耗任何位）

        private HuffmanTree(int[] counts, int[] symbols, int singleSymbol) {
            System.arraycopy(counts, 0, this.counts, 0, 16);
            this.symbols = symbols;
            this.singleSymbol = singleSymbol;
        }

        static HuffmanTree build(int[] codeLengths, int alphabetSize) {
            int[] counts = new int[16];
            for (int i = 0; i < alphabetSize; i++) {
                int len = codeLengths[i];
                if (len < 0 || len > 15) return null;
                counts[len]++;
            }
            counts[0] = 0;
            int total = 0, only = -1;
            for (int len = 1; len <= 15; len++) total += counts[len];
            if (total == 0) return null;
            if (total == 1) {
                for (int i = 0; i < alphabetSize; i++) {
                    if (codeLengths[i] != 0) { only = i; break; }
                }
            }
            int[] offsets = new int[16];
            int acc = 0;
            for (int len = 1; len <= 15; len++) {
                offsets[len] = acc;
                acc += counts[len];
            }
            int[] symbols = new int[total];
            int[] next = offsets.clone();
            for (int i = 0; i < alphabetSize; i++) {
                int len = codeLengths[i];
                if (len > 0) symbols[next[len]++] = i;
            }
            return new HuffmanTree(counts, symbols, only);
        }

        int readSymbol(BitReader br) {
            if (singleSymbol >= 0) return singleSymbol;
            int code = 0, first = 0, index = 0;
            for (int len = 1; len <= 15; len++) {
                code |= br.readBits(1);
                int count = counts[len];
                if (code - first < count) return symbols[index + (code - first)];
                index += count;
                first = (first + count) << 1;
                code <<= 1;
            }
            return -1;                                        // 非法码字
        }
    }

    /** VP8L 里 code length 编码的读取顺序 */
    private static final int[] CODE_LENGTH_ORDER = {
            17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
    };
    private static final int[] CODE_LENGTH_EXTRA_BITS = {2, 3, 7};
    private static final int[] CODE_LENGTH_REPEAT_OFFSET = {3, 3, 11};

    private static final int NUM_LITERAL_CODES = 256;
    private static final int NUM_LENGTH_CODES = 24;
    private static final int NUM_DISTANCE_CODES = 40;

    /** 距离码 → 平面距离 的查找表（spec 的 kCodeToPlane） */
    private static final int[] CODE_TO_PLANE = {
            0x18, 0x07, 0x17, 0x19, 0x28, 0x06, 0x27, 0x29, 0x16, 0x1a,
            0x26, 0x2a, 0x38, 0x05, 0x37, 0x39, 0x15, 0x1b, 0x36, 0x3a,
            0x25, 0x2b, 0x48, 0x04, 0x47, 0x49, 0x14, 0x1c, 0x35, 0x3b,
            0x46, 0x4a, 0x24, 0x2c, 0x58, 0x45, 0x4b, 0x34, 0x3c, 0x03,
            0x57, 0x59, 0x13, 0x1d, 0x56, 0x5a, 0x23, 0x2d, 0x44, 0x4c,
            0x55, 0x5b, 0x33, 0x3d, 0x68, 0x02, 0x67, 0x69, 0x12, 0x1e,
            0x66, 0x6a, 0x22, 0x2e, 0x54, 0x5c, 0x43, 0x4d, 0x65, 0x6b,
            0x32, 0x3e, 0x78, 0x01, 0x77, 0x79, 0x53, 0x5d, 0x11, 0x1f,
            0x64, 0x6c, 0x42, 0x4e, 0x76, 0x7a, 0x21, 0x2f, 0x75, 0x7b,
            0x31, 0x3f, 0x63, 0x6d, 0x52, 0x5e, 0x00, 0x74, 0x7c, 0x41,
            0x4f, 0x10, 0x20, 0x62, 0x6e, 0x30, 0x73, 0x7d, 0x51, 0x5f,
            0x40, 0x72, 0x7e, 0x61, 0x6f, 0x50, 0x71, 0x7f, 0x60, 0x70
    };
    private static final int CODE_TO_PLANE_CODES = 120;

    /** 一个变换 */
    private static final class Transform {
        final int type;                 // 0=预测 1=交叉颜色 2=减法绿色 3=颜色索引
        final int bits;
        final int[] data;

        Transform(int type, int bits, int[] data) {
            this.type = type;
            this.bits = bits;
            this.data = data;
        }
    }

    // ==================== 解码器主体 ====================

    private static final class Decoder {
        private final BitReader br;

        Decoder(BitReader br) {
            this.br = br;
        }

        /**
         * 解码一个「图像流」。
         *
         * @param level0 最外层图像：需要读变换，并在最后应用逆变换
         * @return ARGB 像素数组（宽 × 高）
         */
        int[] decodeImage(int xsize, int ysize, boolean level0) throws IOException {
            int[] curW = {xsize};
            int[] curH = {ysize};
            java.util.List<Transform> transforms = new java.util.ArrayList<>();

            if (level0) {
                while (br.readBits(1) != 0) {
                    if (!readTransform(curW, curH, transforms)) return null;
                }
            }

            // 颜色缓存
            int colorCacheBits = 0;
            if (br.readBits(1) != 0) {
                colorCacheBits = br.readBits(4);
                if (colorCacheBits < 1 || colorCacheBits > 11) return null;
            }

            int width = curW[0], height = curH[0];
            HuffmanGroup[] groups = readHuffmanCodes(width, height, colorCacheBits, level0);
            if (groups == null) { debug("readHuffmanCodes 失败 " + width + "x" + height); return null; }
            debug("Huffman 组数=" + groups.length + " 缓存位=" + colorCacheBits + " 图 " + width + "x" + height);

            int[] huffmanImage = null;
            int huffmanBits = 0;
            int huffmanXSize = 0;
            if (level0 && groups.length > 1) {
                // 元 Huffman 图在 readHuffmanCodes 里解析，这里由成员带回
                huffmanImage = metaHuffmanImage;
                huffmanBits = metaHuffmanBits;
                huffmanXSize = metaHuffmanXSize;
            }

            int[] pixels = decodeImageData(groups, width, height, colorCacheBits,
                    huffmanImage, huffmanBits, huffmanXSize);
            if (pixels == null) { debug("decodeImageData 失败 " + width + "x" + height); return null; }

            if (level0) {
                if (debugEnabled()) {
                    StringBuilder sb = new StringBuilder("变换链(定义顺序): ");
                    for (Transform t0 : transforms) sb.append("type=").append(t0.type)
                            .append("/bits=").append(t0.bits)
                            .append("/dataLen=").append(t0.data == null ? 0 : t0.data.length).append(" ");
                    debug(sb.toString());
                }
                // 逆变换按「定义顺序的倒序」应用
                for (int i = transforms.size() - 1; i >= 0; i--) {
                    Transform t = transforms.get(i);
                    switch (t.type) {
                        case 2 -> pixels = inverseSubtractGreen(pixels);
                        case 0 -> pixels = inversePredictor(pixels, xsize, ysize, t);
                        case 1 -> pixels = inverseCrossColor(pixels, xsize, ysize, t);
                        case 3 -> pixels = inverseColorIndexing(pixels, xsize, ysize, t);
                        default -> {
                            return null;
                        }
                    }
                }
            }
            return pixels;
        }

        // ---------- 变换读取 ----------

        private int[] metaHuffmanImage;
        private int metaHuffmanBits;
        private int metaHuffmanXSize;

        private boolean readTransform(int[] curW, int[] curH,
                                      java.util.List<Transform> transforms) throws IOException {
            int type = br.readBits(2);
            switch (type) {
                case 0:      // 预测变换
                case 1: {    // 交叉颜色变换
                    int bits = br.readBits(3) + 2;
                    int subW = subsample(curW[0], bits);
                    int subH = subsample(curH[0], bits);
                    int[] data = decodeImage(subW, subH, false);
                    if (data == null) return false;
                    transforms.add(new Transform(type, bits, data));
                    return true;
                }
                case 2:      // 减法绿色：无附加数据
                    transforms.add(new Transform(2, 0, null));
                    return true;
                default: {   // 3 = 颜色索引
                    int numColors = br.readBits(8) + 1;
                    int bits = (numColors > 16) ? 0
                            : (numColors > 4) ? 1
                            : (numColors > 2) ? 2 : 3;
                    curW[0] = subsample(curW[0], bits);
                    curH[0] = subsample(curH[0], bits);
                    int[] palette = decodeImage(numColors, 1, false);
                    if (palette == null) return false;
                    transforms.add(new Transform(3, bits, palette));
                    return true;
                }
            }
        }

        private static int subsample(int size, int bits) {
            return (size + (1 << bits) - 1) >> bits;
        }

        // ---------- Huffman 组 ----------

        private static final class HuffmanGroup {
            HuffmanTree green, red, blue, alpha, dist;
        }

        private HuffmanGroup[] readHuffmanCodes(int xsize, int ysize, int colorCacheBits,
                                                boolean allowMeta) throws IOException {
            int numGroups = 1;
            if (allowMeta && br.readBits(1) != 0) {
                metaHuffmanBits = br.readBits(3) + 2;
                metaHuffmanXSize = subsample(xsize, metaHuffmanBits);
                int metaYSize = subsample(ysize, metaHuffmanBits);
                metaHuffmanImage = decodeImage(metaHuffmanXSize, metaYSize, false);
                if (metaHuffmanImage == null) return null;
                // 组号存放在 red + green 两个通道里
                for (int i = 0; i < metaHuffmanImage.length; i++) {
                    int group = (metaHuffmanImage[i] >>> 8) & 0xFFFF;
                    metaHuffmanImage[i] = group;
                    if (group + 1 > numGroups) numGroups = group + 1;
                }
            }

            int cacheSize = colorCacheBits > 0 ? (1 << colorCacheBits) : 0;
            int greenAlphabet = NUM_LITERAL_CODES + NUM_LENGTH_CODES + cacheSize;
            HuffmanGroup[] groups = new HuffmanGroup[numGroups];
            int[] lengths = new int[greenAlphabet];
            for (int g = 0; g < numGroups; g++) {
                HuffmanGroup hg = new HuffmanGroup();
                hg.green = readHuffmanCode(greenAlphabet, lengths);
                if (hg.green == null) return null;
                hg.red = readHuffmanCode(NUM_LITERAL_CODES, new int[NUM_LITERAL_CODES]);
                hg.blue = readHuffmanCode(NUM_LITERAL_CODES, new int[NUM_LITERAL_CODES]);
                hg.alpha = readHuffmanCode(NUM_LITERAL_CODES, new int[NUM_LITERAL_CODES]);
                hg.dist = readHuffmanCode(NUM_DISTANCE_CODES, new int[NUM_DISTANCE_CODES]);
                if (hg.red == null || hg.blue == null || hg.alpha == null || hg.dist == null) return null;
                groups[g] = hg;
            }
            return groups;
        }

        /** 读一个 Huffman 码（简单码 / 规范码两条路径） */
        private HuffmanTree readHuffmanCode(int alphabetSize, int[] codeLengths) throws IOException {
            java.util.Arrays.fill(codeLengths, 0);
            if (br.readBits(1) != 0) {
                // 简单码：1~2 个符号，码长直接给出
                int numSymbols = br.readBits(1) + 1;
                int firstIs8Bits = br.readBits(1);
                int s0 = br.readBits(firstIs8Bits == 0 ? 1 : 8);
                if (s0 >= alphabetSize) return null;
                codeLengths[s0] = 1;
                if (numSymbols == 2) {
                    int s1 = br.readBits(8);
                    if (s1 >= alphabetSize) return null;
                    codeLengths[s1] = 1;
                }
                debug("  简单码 alphabet=" + alphabetSize + " 符号数=" + numSymbols + " s0=" + s0);
                return HuffmanTree.build(codeLengths, alphabetSize);
            }

            // 规范码：先读「码长码」的码长，再用它读真正的码长
            int[] codeLengthCodeLengths = new int[19];
            int numCodes = br.readBits(4) + 4;
            for (int i = 0; i < numCodes; i++) {
                codeLengthCodeLengths[CODE_LENGTH_ORDER[i]] = br.readBits(3);
            }
            HuffmanTree clTree = HuffmanTree.build(codeLengthCodeLengths, 19);
            if (clTree == null) return null;

            // 只读前 max_symbol 个符号的码长，其余保持 0（省位的空间优化）。
            // 漏掉这一段会让其后所有位错位，整张图直接解不出来。
            int maxSymbol;
            if (br.readBits(1) != 0) {
                int lengthNbits = 2 + 2 * br.readBits(3);
                maxSymbol = 2 + br.readBits(lengthNbits);
                if (maxSymbol > alphabetSize) return null;
            } else {
                maxSymbol = alphabetSize;
            }

            int symbol = 0;
            int prevCodeLen = 8;                        // 默认码长
            while (symbol < alphabetSize && maxSymbol-- > 0) {
                int codeLen = clTree.readSymbol(br);
                if (codeLen < 0) return null;
                if (codeLen < 16) {
                    codeLengths[symbol++] = codeLen;
                    if (codeLen != 0) prevCodeLen = codeLen;
                } else {
                    int slot = codeLen - 16;
                    if (slot > 2) return null;
                    int repeat = br.readBits(CODE_LENGTH_EXTRA_BITS[slot])
                            + CODE_LENGTH_REPEAT_OFFSET[slot];
                    if (symbol + repeat > alphabetSize) return null;
                    int value = (codeLen == 16) ? prevCodeLen : 0;
                    while (repeat-- > 0) codeLengths[symbol++] = value;
                }
            }
            {
                int nonZero = 0, maxLen = 0, total = 0;
                for (int i = 0; i < alphabetSize; i++) {
                    if (codeLengths[i] > 0) { nonZero++; total += codeLengths[i]; }
                    if (codeLengths[i] > maxLen) maxLen = codeLengths[i];
                }
                debug("  规范码 alphabet=" + alphabetSize + " 非零符号=" + nonZero
                        + " 最长码=" + maxLen + " 码长和=" + total
                        + (total == (1 << maxLen) ? " (完整树)" : " (不完整!)"));
            }
            return HuffmanTree.build(codeLengths, alphabetSize);
        }

        // ---------- LZ77 图像数据 ----------

        private int[] decodeImageData(HuffmanGroup[] groups, int width, int height, int colorCacheBits,
                                      int[] huffmanImage, int huffmanBits, int huffmanXSize) {
            int total = width * height;
            if (total <= 0) return null;
            int[] out = new int[total];
            // 颜色缓存是「每个图像流一份、所有 Huffman 组共用」的（libwebp 语义），
            // 不是每组各一份
            int[] colorCache = colorCacheBits > 0 ? new int[1 << colorCacheBits] : null;
            int pos = 0;
            int lenCodeLimit = NUM_LITERAL_CODES + NUM_LENGTH_CODES;
            int cacheLimit = lenCodeLimit + (colorCache != null ? colorCache.length : 0);

            while (pos < total) {
                if (br.isEos()) return null;
                HuffmanGroup hg = groups[0];
                if (groups.length > 1 && huffmanImage != null) {
                    int y = pos / width, x = pos % width;
                    int idx = (y >>> huffmanBits) * huffmanXSize + (x >>> huffmanBits);
                    if (idx >= huffmanImage.length) return null;
                    int g = huffmanImage[idx];
                    if (g >= groups.length) return null;
                    hg = groups[g];
                }

                int code = hg.green.readSymbol(br);
                if (code < 0) return null;

                if (code < NUM_LITERAL_CODES) {
                    // 字面量像素
                    int green = code;
                    int red = hg.red.readSymbol(br);
                    int blue = hg.blue.readSymbol(br);
                    int alpha = hg.alpha.readSymbol(br);
                    if (red < 0 || blue < 0 || alpha < 0) return null;
                    int pixel = (alpha << 24) | (red << 16) | (green << 8) | blue;
                    out[pos++] = pixel;
                    insertCache(colorCache, colorCacheBits, pixel);
                } else if (code < lenCodeLimit) {
                    // LZ77 回引用
                    int length = getCopyLength(code - NUM_LITERAL_CODES);
                    int distSym = hg.dist.readSymbol(br);
                    if (distSym < 0 || length < 0) return null;
                    int distCode = getCopyDistance(distSym);
                    if (distCode < 0) return null;
                    int dist = planeCodeToDistance(width, distCode);
                    if (dist <= 0 || dist > pos) return null;
                    int end = Math.min(pos + length, total);
                    int src = pos - dist;
                    while (pos < end) {
                        int pixel = out[src++];
                        out[pos++] = pixel;
                        insertCache(colorCache, colorCacheBits, pixel);
                    }
                } else if (code < cacheLimit) {
                    // 颜色缓存命中
                    int key = code - lenCodeLimit;
                    out[pos++] = colorCache[key];
                } else {
                    return null;
                }
            }
            return out;
        }

        private void insertCache(int[] colorCache, int colorCacheBits, int pixel) {
            if (colorCache == null) return;
            int idx = (int) ((0x1e35a7bdL * (pixel & 0xFFFFFFFFL)) >>> (32 - colorCacheBits));
            colorCache[idx] = pixel;
        }

        /** 前缀编码：距离码 / 长度码共用同一套 */
        private int getCopyDistance(int symbol) {
            if (symbol < 4) return symbol + 1;
            int extraBits = (symbol - 2) >> 1;
            int offset = (2 + (symbol & 1)) << extraBits;
            return offset + br.readBits(extraBits) + 1;
        }

        private int getCopyLength(int symbol) {
            return getCopyDistance(symbol);
        }

        private static int planeCodeToDistance(int xsize, int planeCode) {
            if (planeCode > CODE_TO_PLANE_CODES) {
                return planeCode - CODE_TO_PLANE_CODES;
            }
            int distCode = CODE_TO_PLANE[planeCode - 1];
            int yOffset = distCode >> 4;
            int xOffset = 8 - (distCode & 0x0F);
            int dist = yOffset * xsize + xOffset;
            return Math.max(dist, 1);
        }

        // ---------- 逆变换 ----------

        private static int[] inverseSubtractGreen(int[] px) {
            for (int i = 0; i < px.length; i++) {
                int p = px[i];
                int green = (p >>> 8) & 0xFF;
                int red = ((p >>> 16) & 0xFF) + green;
                int blue = (p & 0xFF) + green;
                px[i] = (p & 0xFF000000) | ((red & 0xFF) << 16) | (green << 8) | (blue & 0xFF);
            }
            return px;
        }

        private static int colorTransformDelta(int t, int c) {
            return ((byte) t) * ((byte) c) >> 5;
        }

        private static int[] inverseCrossColor(int[] px, int w, int h, Transform t) {
            int tileW = 1 << t.bits;
            int tilesPerRow = subsample(w, t.bits);
            for (int y = 0; y < h; y++) {
                int ty = y >> t.bits;
                for (int x = 0; x < w; x++) {
                    int m = t.data[ty * tilesPerRow + (x >> t.bits)];
                    int greenToRed = (m >>> 16) & 0xFF;
                    int greenToBlue = (m >>> 8) & 0xFF;
                    int redToBlue = m & 0xFF;

                    int i = y * w + x;
                    int p = px[i];
                    int green = (p >>> 8) & 0xFF;
                    int red = (p >>> 16) & 0xFF;
                    int blue = p & 0xFF;

                    red = (red + colorTransformDelta(greenToRed, green)) & 0xFF;
                    blue = (blue + colorTransformDelta(greenToBlue, green)) & 0xFF;
                    blue = (blue + colorTransformDelta(redToBlue, red)) & 0xFF;

                    px[i] = (p & 0xFF000000) | (red << 16) | (green << 8) | blue;
                }
            }
            return px;
        }

        private static int[] inverseColorIndexing(int[] px, int w, int h, Transform t) {
            int bitsPerPixel = 8 >> t.bits;
            int[] palette = t.data;
            int[] out = new int[w * h];
            if (bitsPerPixel < 8) {
                int pixelsPerByte = 1 << t.bits;
                int countMask = pixelsPerByte - 1;
                int bitMask = (1 << bitsPerPixel) - 1;
                int src = 0;
                for (int y = 0; y < h; y++) {
                    int packed = 0;
                    for (int x = 0; x < w; x++) {
                        if ((x & countMask) == 0) {
                            if (src >= px.length) return out;
                            packed = (px[src++] >>> 8) & 0xFF;   // 索引位打包在 green 通道
                        }
                        int idx = packed & bitMask;
                        out[y * w + x] = idx < palette.length ? palette[idx] : 0;
                        packed >>>= bitsPerPixel;
                    }
                }
            } else {
                // 每像素 8 位索引
                for (int i = 0; i < w * h && i < px.length; i++) {
                    int idx = (px[i] >>> 8) & 0xFF;
                    out[i] = idx < palette.length ? palette[idx] : 0;
                }
            }
            return out;
        }

        private static int[] inversePredictor(int[] px, int w, int h, Transform t) {
            int tileW = 1 << t.bits;
            int tilesPerRow = subsample(w, t.bits);
            int[] out = new int[w * h];
            int[] modeHist = debugEnabled() ? new int[16] : null;
            for (int y = 0; y < h; y++) {
                int base = y * w;
                for (int x = 0; x < w; x++) {
                    int mode;
                    if (y == 0) {
                        // 首行不使用预测模式：首像素用 mode 0，其余用 L（mode 1）
                        mode = (x == 0) ? 0 : 1;
                    } else if (x == 0) {
                        // 每行首像素固定用 T（mode 2）
                        mode = 2;
                    } else {
                        mode = (t.data[(y >> t.bits) * tilesPerRow + (x >> t.bits)] >>> 8) & 0xFF;
                    }
                    if (modeHist != null) modeHist[Math.min(mode, 15)]++;
                    int i = base + x;
                    int left = x > 0 ? out[i - 1] : 0;
                    int top = y > 0 ? out[i - w] : 0;
                    int topLeft = (x > 0 && y > 0) ? out[i - w - 1] : 0;
                    int topRight = (y > 0) ? ((x + 1 < w) ? out[i - w + 1] : out[i - w]) : 0;
                    int pred = predict(mode, left, top, topLeft, topRight);
                    out[i] = addPixels(px[i], pred);
                }
            }
            if (modeHist != null) {
                StringBuilder sb = new StringBuilder("预测模式直方图 bits=" + t.bits + ": ");
                for (int m = 0; m < 16; m++) if (modeHist[m] > 0) sb.append(m).append("=").append(modeHist[m]).append(" ");
                debug(sb.toString());
            }
            return out;
        }

        // ---------- 预测子（与 spec / libwebp 一致） ----------

        private static int addPixels(int a, int b) {
            int alpha = (((a >>> 24) + (b >>> 24)) & 0xFF) << 24;
            int red = ((((a >>> 16) & 0xFF) + ((b >>> 16) & 0xFF)) & 0xFF) << 16;
            int green = ((((a >>> 8) & 0xFF) + ((b >>> 8) & 0xFF)) & 0xFF) << 8;
            int blue = (((a & 0xFF) + (b & 0xFF)) & 0xFF);
            return alpha | red | green | blue;
        }

        private static int average2(int a, int b) {
            return (((a ^ b) & 0xFEFEFEFE) >>> 1) + (a & b);
        }

        private static int clip255(int a) {
            if (a < 0) return 0;
            if (a > 255) return 255;
            return a;
        }

        private static int addSubtractFull(int a, int b, int c) {
            return clip255(a + b - c);
        }

        private static int addSubtractHalf(int a, int b, int c) {
            return clip255(a + (b - c) / 2);
        }

        private static int clampedAddSubtractFull(int c0, int c1, int c2) {
            int a = addSubtractFull(c0 >>> 24, c1 >>> 24, c2 >>> 24);
            int r = addSubtractFull((c0 >>> 16) & 0xFF, (c1 >>> 16) & 0xFF, (c2 >>> 16) & 0xFF);
            int g = addSubtractFull((c0 >>> 8) & 0xFF, (c1 >>> 8) & 0xFF, (c2 >>> 8) & 0xFF);
            int b = addSubtractFull(c0 & 0xFF, c1 & 0xFF, c2 & 0xFF);
            return (a << 24) | (r << 16) | (g << 8) | b;
        }

        private static int clampedAddSubtractHalf(int c0, int c1, int c2) {
            int ave = average2(c0, c1);
            int a = addSubtractHalf(ave >>> 24, c2 >>> 24, c0 >>> 24);
            int r = addSubtractHalf((ave >>> 16) & 0xFF, (c2 >>> 16) & 0xFF, (c0 >>> 16) & 0xFF);
            int g = addSubtractHalf((ave >>> 8) & 0xFF, (c2 >>> 8) & 0xFF, (c0 >>> 8) & 0xFF);
            int b = addSubtractHalf(ave & 0xFF, c2 & 0xFF, c0 & 0xFF);
            return (a << 24) | (r << 16) | (g << 8) | b;
        }

        private static int select(int a, int b, int c) {
            int pa = sub3(a >>> 24, b >>> 24, c >>> 24)
                    + sub3((a >>> 16) & 0xFF, (b >>> 16) & 0xFF, (c >>> 16) & 0xFF)
                    + sub3((a >>> 8) & 0xFF, (b >>> 8) & 0xFF, (c >>> 8) & 0xFF)
                    + sub3(a & 0xFF, b & 0xFF, c & 0xFF);
            return pa <= 0 ? a : b;
        }

        private static int sub3(int a, int b, int c) {
            return Math.abs(b - c) - Math.abs(a - c);
        }

        private static int predict(int mode, int left, int top, int topLeft, int topRight) {
            return switch (mode) {
                case 0 -> 0xFF000000;                               // 黑
                case 1 -> left;
                case 2 -> top;
                case 3 -> topRight;
                case 4 -> topLeft;
                case 5 -> average2(average2(left, topRight), top);
                case 6 -> average2(left, topLeft);
                case 7 -> average2(left, top);
                case 8 -> average2(topLeft, top);
                case 9 -> average2(top, topRight);
                case 10 -> average2(average2(left, topLeft), average2(top, topRight));
                case 11 -> select(top, left, topLeft);
                case 12 -> clampedAddSubtractFull(left, top, topLeft);
                case 13 -> clampedAddSubtractHalf(left, top, topLeft);
                default -> 0xFF000000;
            };
        }
    }
}
