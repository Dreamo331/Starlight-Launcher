#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""节日壁纸生成器（可复现）。

本脚本生成 ``src/main/resources/wallpaper/<节日 id>.jpg``：
启动器在节日期间按 ``FestivalCalendar`` 命中的 id 加载同名壁纸（见
``LauncherView.applyFestivalWallpaper``），铺满整个窗口并叠一层可读性遮罩。

为什么用脚本生成而不是手绘：
- 壁纸必须与 ``FestivalCalendar`` 的 11 个 id 一一对应，漏一张就会出现
  「其他节日有背景、这个节日没有」的不一致；
- 每张图都是同一套「深色渐变 + 节日意象剪影 + 柔光粒子」配方，
  换配色就能补齐，避免逐张手工调色。

用法（项目自带 Python 运行时即可，需要 Pillow / numpy）::

    python tools/wallpaper-gen/generate_festival_wallpapers.py

重新生成后直接覆盖 ``src/main/resources/wallpaper/``，无需改 Java 代码。

画面构图约定（与启动器内容区配合）：
- 大量元素放在四角与上下边缘，中央留相对干净的渐变色，避免和卡片抢视线；
- 整体亮度偏低、对比偏柔，前景文字靠 ``LauncherView`` 的可读性遮罩保证；
- 所有粒子/光斑都用径向渐变 sprite，而不是硬边圆点（硬边圆点在深色背景上像脏点）。
"""

from __future__ import annotations

import math
import os
import zlib

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

# 输出尺寸：与启动器窗口比例接近，拉伸铺满时形变小
OUT_W, OUT_H = 1600, 900
# 先按 2 倍尺寸绘制再缩小，边缘更干净（PIL 的圆/线没有抗锯齿）
SUPER = 2

OUT_DIR = os.path.normpath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)),
                 "..", "..", "src", "main", "resources", "wallpaper")
)


# --------------------------------------------------------------------------
# 颜色与基础图层
# --------------------------------------------------------------------------

def _hex_rgb(color: str) -> tuple[float, float, float]:
    color = color.lstrip("#")
    return tuple(int(color[i:i + 2], 16) / 255.0 for i in (0, 2, 4))  # type: ignore[return-value]


def _rgb255(color: str) -> tuple[int, int, int]:
    r, g, b = _hex_rgb(color)
    return int(r * 255), int(g * 255), int(b * 255)


def vertical_gradient(stops: list[tuple[float, str]], w: int, h: int) -> np.ndarray:
    """按 (位置, 颜色) 生成竖直渐变；位置 0=顶部、1=底部。"""
    ys = np.linspace(0.0, 1.0, h)
    img = np.zeros((h, w, 3), dtype=np.float32)
    stops = sorted(stops, key=lambda s: s[0])
    for i in range(len(stops) - 1):
        p0, c0 = stops[i]
        p1, c1 = stops[i + 1]
        c0v = np.array(_hex_rgb(c0), dtype=np.float32)
        c1v = np.array(_hex_rgb(c1), dtype=np.float32)
        mask = (ys >= p0) & (ys <= p1)
        if not np.any(mask):
            continue
        t = ((ys[mask] - p0) / max(p1 - p0, 1e-6))[:, None]
        img[mask] = (c0v[None, :] * (1 - t) + c1v[None, :] * t)[:, None, :]
    img[: int(stops[0][0] * h)] = np.array(_hex_rgb(stops[0][1]), dtype=np.float32)
    img[int(stops[-1][0] * h):] = np.array(_hex_rgb(stops[-1][1]), dtype=np.float32)
    return img


def add_radial(img: np.ndarray, cx: float, cy: float, radius: float,
               color: str, strength: float, aspect: float = 1.0) -> None:
    """叠加一团柔光（加色混合）。cx/cy/radius 都按图像宽高归一化。"""
    h, w = img.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    dx = (xx - cx * w) / (radius * w)
    dy = (yy - cy * h) / (radius * h * aspect)
    d = np.sqrt(dx * dx + dy * dy)
    falloff = np.clip(1.0 - d, 0.0, 1.0) ** 2.4
    c = np.array(_hex_rgb(color), dtype=np.float32)
    img += falloff[..., None] * c[None, None, :] * strength


def vignette(img: np.ndarray, strength: float = 0.5) -> None:
    """四角压暗，让居中内容区保持通透。"""
    h, w = img.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    nx = (xx / w - 0.5) * 2
    ny = (yy / h - 0.5) * 2
    d = np.sqrt(nx * nx * 0.85 + ny * ny)
    factor = np.clip(1.0 - strength * np.clip(d - 0.40, 0, 1) ** 2, 0.30, 1.0)
    img *= factor[..., None]


def add_grain(img: np.ndarray, rng: np.random.Generator, amount: float = 0.010) -> None:
    """细颗粒：压掉 JPEG 渐变上的色带。"""
    noise = rng.normal(0.0, amount, img.shape[:2]).astype(np.float32)
    img += noise[..., None]


# --------------------------------------------------------------------------
# 绘制工具
# --------------------------------------------------------------------------

def _canvas(w: int, h: int) -> Image.Image:
    return Image.new("RGBA", (w, h), (0, 0, 0, 0))


def _blur(layer: Image.Image, radius: int) -> Image.Image:
    return layer.filter(ImageFilter.GaussianBlur(radius))


def _sprite(radius: int, color: str, peak_alpha: int, softness: float = 2.2) -> Image.Image:
    """径向渐变光斑 sprite（中心亮、边缘透明），用于柔光 / 光斑 / 灯笼光晕。"""
    d = max(2, radius * 2)
    yy, xx = np.mgrid[0:d, 0:d].astype(np.float32)
    dist = np.sqrt((xx - radius) ** 2 + (yy - radius) ** 2) / radius
    alpha = (np.clip(1.0 - dist, 0.0, 1.0) ** softness * peak_alpha).astype(np.uint8)
    arr = np.zeros((d, d, 4), dtype=np.uint8)
    r, g, b = _rgb255(color)
    arr[..., 0], arr[..., 1], arr[..., 2] = r, g, b
    arr[..., 3] = alpha
    return Image.fromarray(arr, "RGBA")


def _paste(layer: Image.Image, sprite: Image.Image, x: float, y: float) -> None:
    layer.alpha_composite(sprite, (int(x - sprite.width / 2), int(y - sprite.height / 2)))


def draw_bokeh(layer: Image.Image, rng: np.random.Generator, w: int, h: int,
               count: int, color: str, rmin: float = 0.004, rmax: float = 0.022,
               alpha: int = 42, area: tuple[float, float, float, float] = (0, 0, 1, 1)) -> None:
    """散景光斑：大而极淡的径向光斑（浅景深效果），不是实心点。"""
    x0, y0, x1, y1 = area
    for _ in range(count):
        r = int(rng.uniform(rmin, rmax) * w)
        if r < 3:
            continue
        sp = _sprite(r, color, int(alpha * rng.uniform(0.45, 1.0)), 2.6)
        _paste(layer, sp, rng.uniform(x0, x1) * w, rng.uniform(y0, y1) * h)


def draw_stars(layer: Image.Image, rng: np.random.Generator, w: int, h: int,
               count: int, color: str = "#ffffff", alpha: int = 170) -> None:
    """星点：绝大多数是 1–2px 的细点，少量带柔光。"""
    d = ImageDraw.Draw(layer)
    c = _rgb255(color)
    for _ in range(count):
        cx = rng.uniform(0, 1) * w
        cy = rng.uniform(0, 0.82) * h
        rad = rng.uniform(0.0005, 0.0014) * w
        a = int(alpha * rng.uniform(0.25, 1.0))
        d.ellipse([cx - rad, cy - rad, cx + rad, cy + rad], fill=(*c, a))
    # 少量亮星带一点光晕
    for _ in range(max(2, count // 90)):
        r = int(rng.uniform(0.005, 0.010) * w)
        _paste(layer, _sprite(r, color, 40, 2.0),
               rng.uniform(0, 1) * w, rng.uniform(0, 0.6) * h)


def draw_cloud_bank(layer: Image.Image, rng: np.random.Generator, w: int, h: int,
                    color: str, banks: int = 3, alpha: int = 34,
                    y0: float = 0.28, y1: float = 0.88) -> None:
    """云团：一团由多枚椭圆叠成、整体重模糊的软云，而不是一条长椭圆。"""
    for _ in range(banks):
        cx = rng.uniform(0.05, 0.95) * w
        cy = rng.uniform(y0, y1) * h
        width = rng.uniform(0.14, 0.34) * w
        height = width * rng.uniform(0.10, 0.20)
        cloud = _canvas(w, h)
        cd = ImageDraw.Draw(cloud)
        c = _rgb255(color)
        puffs = int(rng.integers(5, 9))
        for _ in range(puffs):
            ox = rng.uniform(-width * 0.45, width * 0.45)
            oy = rng.uniform(-height * 0.25, height * 0.25)
            rw = width * rng.uniform(0.22, 0.42)
            rh = height * rng.uniform(0.55, 1.0)
            cd.ellipse([cx + ox - rw, cy + oy - rh, cx + ox + rw, cy + oy + rh],
                       fill=(*c, int(alpha * rng.uniform(0.6, 1.0))))
        layer.alpha_composite(_blur(cloud, int(w * 0.014)))


def draw_hills(layer: Image.Image, rng: np.random.Generator, w: int, h: int,
               color: str, base: float = 0.72, amp: float = 0.08,
               alpha: int = 210, layers: int = 2) -> None:
    """层叠山脊剪影（越靠下越深），用于清明 / 重阳等。"""
    c = _rgb255(color)
    d = ImageDraw.Draw(layer)
    for li in range(layers):
        yb = base + li * 0.075
        a = int(alpha * (1.0 - li * 0.16))
        phase = rng.uniform(0, math.tau)
        freq = rng.uniform(1.2, 2.2)
        amp_l = amp * (1.0 - li * 0.2)
        pts = [(0, h)]
        for x in range(0, w + 1, max(2, w // 140)):
            t = x / w
            y = yb + math.sin(phase + t * freq * math.tau) * amp_l \
                + math.sin(phase * 1.7 + t * freq * 2.3 * math.tau) * amp_l * 0.25
            pts.append((x, y * h))
        pts.append((w, h))
        d.polygon(pts, fill=(*c, a))


def draw_pines(layer: Image.Image, rng: np.random.Generator, w: int, h: int,
               color: str, count: int = 7, alpha: int = 235) -> None:
    """圣诞松树剪影：底部一排分层的针叶塔（层与层之间留台阶，避免糊成一座山）。"""
    d = ImageDraw.Draw(layer)
    c = _rgb255(color)
    for i in range(count):
        cx = (i + 0.5) / count * w + rng.uniform(-0.02, 0.02) * w
        th = rng.uniform(0.16, 0.26) * h
        tw = th * rng.uniform(0.34, 0.44)
        base_y = h + 2
        # 三层针叶塔：底宽顶窄，每往上一层收窄并抬高（apex 的 y 必须小于 base 的 y）
        for tier in range(3):
            bottom = base_y - th * (tier * 0.20)
            apex = bottom - th * 0.48
            half = tw * (0.56 - tier * 0.13)
            d.polygon([(cx, apex), (cx - half, bottom), (cx + half, bottom)],
                      fill=(*c, alpha))
        # 树干
        d.rectangle([cx - th * 0.035, base_y - th * 0.10, cx + th * 0.035, base_y],
                    fill=(*c, alpha))


def draw_snow(layer: Image.Image, rng: np.random.Generator, w: int, h: int,
              count: int = 260, color: str = "#ffffff") -> None:
    d = ImageDraw.Draw(layer)
    c = _rgb255(color)
    for _ in range(count):
        cx = rng.uniform(0, 1) * w
        cy = rng.uniform(0, 1) * h
        rad = rng.uniform(0.0003, 0.0014) * w
        a = int(rng.uniform(30, 150))
        d.ellipse([cx - rad, cy - rad, cx + rad, cy + rad], fill=(*c, a))


def draw_moon(layer: Image.Image, rng: np.random.Generator, w: int, h: int,
              cx: float, cy: float, r: float, color: str = "#fcd34d",
              craters: bool = True) -> None:
    """圆月 / 太阳：先铺两层极淡的大光晕，再画一个边缘清晰的圆盘（不叠多层实心圆）。"""
    x, y, rr = cx * w, cy * h, r * w
    _paste(layer, _sprite(int(rr * 4.2), color, 44, 2.6), x, y)
    _paste(layer, _sprite(int(rr * 1.9), color, 60, 2.0), x, y)
    d = ImageDraw.Draw(layer)
    c = _rgb255(color)
    d.ellipse([x - rr, y - rr, x + rr, y + rr], fill=(*c, 238))
    if not craters:
        return
    # 月面淡淡的环形山（降低纯色的塑料感）
    for _ in range(5):
        ox = rng.uniform(-0.55, 0.55) * rr
        oy = rng.uniform(-0.55, 0.55) * rr
        cr = rr * rng.uniform(0.06, 0.16)
        d.ellipse([x + ox - cr, y + oy - cr, x + ox + cr, y + oy + cr],
                  fill=(*c, 225), outline=(210, 180, 90, 70))


def draw_crescent(layer: Image.Image, rng: np.random.Generator, w: int, h: int,
                  cx: float, cy: float, r: float, color: str = "#e9d5ff") -> None:
    """新月：月盘画在独立图层上用「挖洞」遮罩得到弯月。"""
    x, y, rr = cx * w, cy * h, r * w
    _paste(layer, _sprite(int(rr * 4.0), color, 34, 2.6), x, y)
    disc = _canvas(int(rr * 4), int(rr * 4))
    d = ImageDraw.Draw(disc)
    c = _rgb255(color)
    ox, oy = disc.width / 2, disc.height / 2
    d.ellipse([ox - rr, oy - rr, ox + rr, oy + rr], fill=(*c, 240))
    # 用透明椭圆「挖掉」右侧，得到弯月（ImageDraw 直接写像素，alpha=0 即擦除）
    k = rr * 0.52
    d.ellipse([ox - rr + k, oy - rr - k * 0.42, ox + rr + k, oy + rr - k * 0.42],
              fill=(0, 0, 0, 0))
    layer.alpha_composite(disc, (int(x - ox), int(y - oy)))


def draw_fireworks(layer: Image.Image, rng: np.random.Generator, w: int, h: int,
                   bursts: int, palette: list[str]) -> None:
    """烟花：爆点向外辐射的短线 + 外围散点，整体轻微模糊。"""
    # 烟花要能一眼看出来：线更长、更亮，只做极轻的模糊
    fw = _canvas(w, h)
    d = ImageDraw.Draw(fw)
    for _ in range(bursts):
        cx = rng.uniform(0.10, 0.90) * w
        cy = rng.uniform(0.08, 0.40) * h
        c = _rgb255(palette[int(rng.integers(0, len(palette)))])
        rad = rng.uniform(0.09, 0.17) * w
        rays = int(rng.integers(30, 52))
        for i in range(rays):
            ang = i / rays * math.tau + rng.uniform(-0.02, 0.02)
            rr = rad * rng.uniform(0.55, 1.0)
            d.line([cx, cy, cx + math.cos(ang) * rr, cy + math.sin(ang) * rr],
                   fill=(*c, int(rng.uniform(120, 210))), width=max(2, int(w * 0.0014)))
        for _ in range(int(rays * 1.6)):
            ang = rng.uniform(0, math.tau)
            rr = rad * rng.uniform(0.9, 1.3)
            s = rng.uniform(0.0010, 0.0030) * w
            px, py = cx + math.cos(ang) * rr, cy + math.sin(ang) * rr
            d.ellipse([px - s, py - s, px + s, py + s], fill=(*c, int(rng.uniform(90, 200))))
        # 爆心的一点亮光
        _paste(fw, _sprite(int(w * 0.02), palette[0], 80, 2.0), cx, cy)
    layer.alpha_composite(_blur(fw, int(w * 0.0009)))


def draw_lantern(layer: Image.Image, rng: np.random.Generator, w: int, h: int,
                 cx: float, cy: float, rw: float, palette: list[str],
                 hanging: bool = True) -> None:
    """一盏带暖光晕的灯笼（提绳 / 顶盖 / 灯身 / 底盖 / 流苏）。

    ``hanging=True`` 时从窗口顶沿垂下（春节 / 元宵），否则当作漂浮的孔明灯。
    灯身画在独立图层上并保持不透明：在 RGBA 图层上直接叠半透明内层会变成「空心圈」，
    这里改成「实心灯身 + 实心浅色内圈 + 实心金色骨架」。
    """
    color = palette[int(rng.integers(0, len(palette)))]
    rh = rw * rng.uniform(1.10, 1.30)
    base = _rgb255(color)
    # 内圈浅色：同色系提亮 38%（提太亮会像镜子，不像灯笼纸）
    light = tuple(int(v + (255 - v) * 0.38) for v in base)
    gold = _rgb255("#fbbf24")

    # 暖光晕（压在灯身下面，只从灯身外缘透出来）
    _paste(layer, _sprite(int(rw * 4.0), "#fbbf24", 78, 2.0), cx, cy)
    _paste(layer, _sprite(int(rw * 1.7), "#fde68a", 90, 1.8), cx, cy)

    lan = _canvas(w, h)
    d = ImageDraw.Draw(lan)
    # 提绳 / 灯身 / 骨架都画在不透明图层上
    if hanging:
        d.line([cx, 0, cx, cy - rh * 1.05], fill=(*base, 255), width=max(1, int(w * 0.0007)))
    # 顶盖与底盖
    d.rectangle([cx - rw * 0.46, cy - rh * 1.06, cx + rw * 0.46, cy - rh * 0.90],
                fill=(*gold, 255))
    d.rectangle([cx - rw * 0.30, cy + rh * 0.90, cx + rw * 0.30, cy + rh * 1.06],
                fill=(*gold, 255))
    # 灯身（实心）+ 浅色内芯
    d.ellipse([cx - rw, cy - rh, cx + rw, cy + rh], fill=(*base, 255))
    d.ellipse([cx - rw * 0.62, cy - rh * 0.70, cx + rw * 0.62, cy + rh * 0.70],
              fill=(*light, 255))
    # 纵向骨架（两道细线，按灯身收窄）
    for k in (-0.38, 0.38):
        d.line([cx + k * rw, cy - rh * 0.97, cx + k * rw, cy + rh * 0.97],
               fill=(*gold, 255), width=max(2, int(w * 0.0010)))
    # 流苏
    d.line([cx, cy + rh * 1.06, cx, cy + rh * 1.60], fill=(*gold, 255),
           width=max(1, int(w * 0.0008)))
    layer.alpha_composite(lan)


def draw_waves(layer: Image.Image, w: int, h: int, color: str, base: float = 0.80,
               alpha: int = 90) -> None:
    """水面波纹（横向细弧线）。"""
    d = ImageDraw.Draw(layer)
    c = _rgb255(color)
    for row in range(8):
        y = (base + row * 0.024) * h
        for x in range(-60, w + 60, int(w * 0.055)):
            d.arc([x, y - h * 0.012, x + w * 0.06, y + h * 0.012], 200, 340,
                  fill=(*c, alpha), width=max(1, int(w * 0.0007)))


def draw_leaves(layer: Image.Image, rng: np.random.Generator, w: int, h: int,
                count: int, color: str, alpha: int = 120,
                area: tuple[float, float, float, float] = (0, 0, 1, 1)) -> None:
    """细长旋转椭圆叶片（竹叶 / 柳叶 / 落叶）。

    尺寸刻意做小（0.6%–1.8% 画幅宽）：叶片一大就会像漂浮的绿色胶囊，
    小而密才像风里的叶子。
    """
    x0, y0, x1, y1 = area
    c = _rgb255(color)
    for _ in range(count):
        cx = rng.uniform(x0, x1) * w
        cy = rng.uniform(y0, y1) * h
        ln = rng.uniform(0.006, 0.018) * w
        wd = ln * rng.uniform(0.16, 0.26)
        pad = int(ln * 0.4) + 2
        leaf = Image.new("RGBA", (int(ln * 2) + pad * 2, int(ln * 2) + pad * 2),
                         (0, 0, 0, 0))
        ld = ImageDraw.Draw(leaf)
        cxo = leaf.width / 2
        cyo = leaf.height / 2
        ld.ellipse([cxo - wd, cyo - ln, cxo + wd, cyo + ln],
                   fill=(*c, int(alpha * rng.uniform(0.6, 1.0))))
        leaf = leaf.rotate(math.degrees(rng.uniform(0, math.tau)),
                           resample=Image.BICUBIC, expand=True)
        leaf = _blur(leaf, 1)
        layer.alpha_composite(leaf, (int(cx - leaf.width / 2), int(cy - leaf.height / 2)))


def draw_bats(layer: Image.Image, rng: np.random.Generator, w: int, h: int,
              count: int = 6, color: str = "#160b20") -> None:
    """万圣蝙蝠剪影：身体 + 带两个尖角与下缘缺口的翅膀（远看是蝙蝠，不是纸飞机）。"""
    d = ImageDraw.Draw(layer)
    c = _rgb255(color)
    for _ in range(count):
        cx = rng.uniform(0.06, 0.94) * w
        cy = rng.uniform(0.10, 0.44) * h
        s = rng.uniform(0.014, 0.024) * w
        left = [
            (cx, cy - s * 0.20),
            (cx - s * 0.30, cy - s * 0.32),
            (cx - s * 0.78, cy - s * 0.52),
            (cx - s * 1.45, cy - s * 0.50),
            (cx - s * 1.05, cy - s * 0.08),
            (cx - s * 1.35, cy + s * 0.08),
            (cx - s * 0.85, cy + s * 0.02),
            (cx - s * 0.60, cy + s * 0.28),
            (cx - s * 0.22, cy + s * 0.08),
            (cx, cy + s * 0.24),
        ]
        d.polygon(left, fill=(*c, 232))
        right = [(2 * cx - x, y) for (x, y) in left[1:]]
        d.polygon([(cx, cy - s * 0.20)] + right, fill=(*c, 232))
        d.ellipse([cx - s * 0.12, cy - s * 0.30, cx + s * 0.12, cy + s * 0.28],
                  fill=(*c, 232))


def draw_pumpkins(layer: Image.Image, rng: np.random.Generator, w: int, h: int,
                  color: str, count: int = 4) -> None:
    """万圣南瓜剪影：贴底的小南瓜，带短茎与淡淡的瓣线。"""
    d = ImageDraw.Draw(layer)
    c = _rgb255(color)
    for i in range(count):
        cx = (i + 0.5) / count * w + rng.uniform(-0.03, 0.03) * w
        rw = rng.uniform(0.024, 0.038) * w
        rh = rw * rng.uniform(0.85, 1.00)
        cy = h - rh * rng.uniform(1.12, 1.30)
        d.ellipse([cx - rw, cy - rh, cx + rw, cy + rh], fill=(*c, 232))
        # 橙色描边模拟月光勾边，让剪影不只是个黑圆
        d.arc([cx - rw, cy - rh, cx + rw, cy + rh], 200, 340,
              fill=(251, 146, 60, 110), width=max(2, int(w * 0.0014)))
        d.rectangle([cx - rw * 0.07, cy - rh * 1.30, cx + rw * 0.07, cy - rh * 0.98],
                    fill=(*c, 232))
        for k in (-0.5, 0.0, 0.5):
            d.arc([cx - rw + k * rw, cy - rh, cx + rw + k * rw, cy + rh], 250, 290,
                  fill=(251, 146, 60, 85), width=max(2, int(w * 0.0012)))


def draw_birds(layer: Image.Image, rng: np.random.Generator, w: int, h: int,
               count: int = 6, color: str = "#1c1206") -> None:
    """远山归鸟：几组小 V 形。"""
    d = ImageDraw.Draw(layer)
    c = _rgb255(color)
    for _ in range(count):
        bx, by = rng.uniform(0.15, 0.85) * w, rng.uniform(0.12, 0.40) * h
        s = rng.uniform(0.003, 0.007) * w
        d.line([bx - s, by - s * 0.5, bx, by], fill=(*c, 150), width=2)
        d.line([bx, by, bx + s, by - s * 0.5], fill=(*c, 150), width=2)


def draw_milky_way(layer: Image.Image, rng: np.random.Generator, w: int, h: int) -> None:
    """银河带：一条由大量极淡光斑堆出来的斜带，再整体模糊。"""
    band = _canvas(w, h)
    for i in range(160):
        t = i / 160
        cx = (0.04 + t * 0.94) * w + rng.normal(0, 0.012) * w
        cy = (0.78 - math.sin(t * math.pi) * 0.44) * h + rng.normal(0, 0.03) * h
        r = int(rng.uniform(0.012, 0.045) * w)
        _paste(band, _sprite(r, "#e9d5ff", int(rng.uniform(8, 18)), 2.8), cx, cy)
    layer.alpha_composite(_blur(band, int(w * 0.010)))
    # 带内更密的小星
    d = ImageDraw.Draw(layer)
    c = _rgb255("#ffffff")
    for _ in range(420):
        t = rng.uniform(0, 1)
        cx = (0.04 + t * 0.94) * w + rng.normal(0, 0.02) * w
        cy = (0.78 - math.sin(t * math.pi) * 0.44) * h + rng.normal(0, 0.035) * h
        rad = rng.uniform(0.0004, 0.0011) * w
        d.ellipse([cx - rad, cy - rad, cx + rad, cy + rad],
                  fill=(*c, int(rng.uniform(50, 170))))


# --------------------------------------------------------------------------
# 每个节日的配方
# --------------------------------------------------------------------------

# 与 FestivalCalendar 的 id 严格一一对应；配色取自 Festival 的按钮 / 灯笼色系，
# 但整体压暗，保证前景文字可读。
RECIPES: dict[str, dict] = {
    "chunjie": {
        "sky": [(0.0, "#240607"), (0.55, "#521012"), (1.0, "#7f1d1a")],
        "glow": [("#f59e0b", 0.18, 0.20, 0.34, 0.50), ("#dc2626", 0.82, 0.62, 0.34, 0.42)],
        "motif": "lanterns", "accent": "#fbbf24",
    },
    "yuanxiao": {
        "sky": [(0.0, "#221006"), (0.55, "#6b2410"), (1.0, "#92400e")],
        "glow": [("#fbbf24", 0.50, 0.32, 0.40, 0.55), ("#f97316", 0.85, 0.70, 0.30, 0.40)],
        "motif": "floating_lanterns", "accent": "#fcd34d",
    },
    "qingming": {
        "sky": [(0.0, "#081a14"), (0.55, "#14532d"), (1.0, "#166534")],
        "glow": [("#a7f3d0", 0.40, 0.24, 0.32, 0.30), ("#4ade80", 0.75, 0.70, 0.30, 0.24)],
        "motif": "mist_hills", "accent": "#bbf7d0",
    },
    "duanwu": {
        "sky": [(0.0, "#041c1c"), (0.55, "#0f4c47"), (1.0, "#115e59")],
        "glow": [("#34d399", 0.35, 0.26, 0.36, 0.34), ("#0d9488", 0.78, 0.72, 0.34, 0.30)],
        "motif": "bamboo_water", "accent": "#6ee7b7",
    },
    "qixi": {
        "sky": [(0.0, "#070b1f"), (0.55, "#251247"), (1.0, "#3b1d70")],
        "glow": [("#f472b6", 0.28, 0.30, 0.34, 0.30), ("#a78bfa", 0.76, 0.58, 0.36, 0.32)],
        "motif": "milky_way", "accent": "#fbcfe8",
    },
    "zhongqiu": {
        "sky": [(0.0, "#071120"), (0.55, "#102845"), (1.0, "#1b3553")],
        "glow": [("#fcd34d", 0.72, 0.26, 0.40, 0.22), ("#60a5fa", 0.20, 0.60, 0.34, 0.24)],
        "motif": "moon_clouds", "accent": "#fde68a",
    },
    "chongyang": {
        "sky": [(0.0, "#1f0d04"), (0.55, "#6b2510"), (1.0, "#92400e")],
        "glow": [("#fbbf24", 0.30, 0.30, 0.40, 0.50), ("#f59e0b", 0.80, 0.62, 0.32, 0.34)],
        "motif": "sun_hills", "accent": "#fcd34d",
    },
    "guoqing": {
        "sky": [(0.0, "#220405"), (0.55, "#6b1414"), (1.0, "#8a1c1c")],
        "glow": [("#fbbf24", 0.48, 0.28, 0.38, 0.40), ("#dc2626", 0.85, 0.66, 0.32, 0.34)],
        "motif": "fireworks_gold", "accent": "#fde68a",
    },
    "wansheng": {
        "sky": [(0.0, "#0f0719"), (0.55, "#331759"), (1.0, "#43217c")],
        "glow": [("#fb923c", 0.82, 0.20, 0.34, 0.42), ("#7c3aed", 0.24, 0.66, 0.36, 0.30)],
        "motif": "halloween", "accent": "#fdba74",
    },
    "shengdan": {
        "sky": [(0.0, "#031214"), (0.55, "#0c332b"), (1.0, "#12462c")],
        "glow": [("#ef4444", 0.20, 0.26, 0.32, 0.26), ("#fca5a5", 0.80, 0.24, 0.34, 0.24)],
        "motif": "christmas", "accent": "#fecaca",
    },
    "yuandan": {
        "sky": [(0.0, "#060f22"), (0.55, "#1a2468"), (1.0, "#3b1d70")],
        "glow": [("#67e8f9", 0.46, 0.32, 0.36, 0.34), ("#93c5fd", 0.80, 0.70, 0.34, 0.28)],
        "motif": "newyear", "accent": "#a5f3fc",
    },
}


def build(name: str, spec: dict) -> Image.Image:
    w, h = OUT_W * SUPER, OUT_H * SUPER
    # 用 CRC32 做种子（Python 的 hash() 带随机盐，同一张图两次生成会不一样）
    rng = np.random.default_rng(zlib.crc32(name.encode("utf-8")))
    img = vertical_gradient(spec["sky"], w, h)

    for color, cx, cy, radius, strength in spec["glow"]:
        add_radial(img, cx, cy, radius, color, strength)

    layer = _canvas(w, h)
    accent = spec["accent"]
    motif = spec["motif"]

    if motif == "lanterns":
        draw_cloud_bank(layer, rng, w, h, "#fca5a5", 3, 30, 0.55, 0.92)
        draw_bokeh(layer, rng, w, h, 36, accent, 0.004, 0.018, 46,
                   (0.0, 0.35, 1.0, 1.0))
        # 灯笼只挂在左右上角，避开中间的标题栏
        for fx, fy, fr in ((0.052, 0.115, 0.019), (0.135, 0.060, 0.013),
                           (0.865, 0.065, 0.013), (0.948, 0.125, 0.019)):
            draw_lantern(layer, rng, w, h, fx * w, fy * h, fr * w,
                         ["#dc2626", "#b91c1c", "#f59e0b"])
    elif motif == "floating_lanterns":
        draw_waves(layer, w, h, "#fdba74", 0.80, 60)
        draw_cloud_bank(layer, rng, w, h, "#fdba74", 2, 26, 0.55, 0.80)
        draw_bokeh(layer, rng, w, h, 40, accent, 0.004, 0.020, 48,
                   (0.0, 0.3, 1.0, 1.0))
        for fx, fy, fr in ((0.10, 0.30, 0.012), (0.18, 0.52, 0.009),
                           (0.86, 0.34, 0.013), (0.78, 0.58, 0.009),
                           (0.30, 0.68, 0.008), (0.68, 0.72, 0.008)):
            draw_lantern(layer, rng, w, h, fx * w, fy * h, fr * w,
                         ["#fbbf24", "#f59e0b", "#fdba74"], hanging=False)
    elif motif == "mist_hills":
        draw_hills(layer, rng, w, h, "#02170f", 0.58, 0.075, 235, 3)
        draw_cloud_bank(layer, rng, w, h, "#d1fae5", 4, 34, 0.42, 0.85)
        draw_leaves(layer, rng, w, h, 60, "#86efac", 95, (0, 0, 0.40, 1))
        draw_leaves(layer, rng, w, h, 50, "#86efac", 80, (0.60, 0, 1, 1))
        draw_bokeh(layer, rng, w, h, 20, "#d1fae5", 0.003, 0.012, 34,
                   (0, 0.4, 1, 1))
    elif motif == "bamboo_water":
        draw_waves(layer, w, h, "#5eead4", 0.78, 80)
        draw_leaves(layer, rng, w, h, 70, "#0b3b36", 165, (0, 0, 0.30, 0.55))
        draw_leaves(layer, rng, w, h, 60, "#0b3b36", 165, (0.70, 0, 1, 0.55))
        draw_leaves(layer, rng, w, h, 26, "#5eead4", 70, (0, 0.1, 0.32, 0.7))
        draw_bokeh(layer, rng, w, h, 22, "#99f6e4", 0.003, 0.011, 38, (0, 0.3, 1, 1))
    elif motif == "milky_way":
        draw_milky_way(layer, rng, w, h)
        draw_stars(layer, rng, w, h, 480, "#ffffff", 165)
        draw_bokeh(layer, rng, w, h, 26, "#fbcfe8", 0.003, 0.014, 40)
        # 两颗亮星（牛郎 / 织女）
        for sx, sy in ((0.26, 0.22), (0.76, 0.16)):
            _paste(layer, _sprite(int(w * 0.016), "#fbcfe8", 80, 2.0), sx * w, sy * h)
            d = ImageDraw.Draw(layer)
            arm = w * 0.012
            d.line([sx * w - arm, sy * h, sx * w + arm, sy * h],
                   fill=(255, 255, 255, 120), width=2)
            d.line([sx * w, sy * h - arm, sx * w, sy * h + arm],
                   fill=(255, 255, 255, 120), width=2)
    elif motif == "moon_clouds":
        draw_stars(layer, rng, w, h, 300)
        draw_cloud_bank(layer, rng, w, h, "#93c5fd", 4, 34, 0.34, 0.72)
        draw_moon(layer, rng, w, h, 0.73, 0.27, 0.070, "#fcd34d")
        draw_cloud_bank(layer, rng, w, h, "#cbd5e1", 2, 24, 0.62, 0.86)
        draw_bokeh(layer, rng, w, h, 24, "#fde68a", 0.003, 0.010, 40, (0, 0.5, 1, 1))
    elif motif == "sun_hills":
        draw_moon(layer, rng, w, h, 0.30, 0.27, 0.052, "#fbbf24", craters=False)
        draw_hills(layer, rng, w, h, "#341004", 0.66, 0.07, 215, 3)
        draw_birds(layer, rng, w, h, 6)
        draw_bokeh(layer, rng, w, h, 24, "#fcd34d", 0.003, 0.011, 38, (0, 0.45, 1, 1))
    elif motif == "fireworks_gold":
        draw_fireworks(layer, rng, w, h, 4, ["#fbbf24", "#fde68a", "#fca5a5"])
        draw_cloud_bank(layer, rng, w, h, "#fca5a5", 3, 26, 0.55, 0.9)
        draw_bokeh(layer, rng, w, h, 44, accent, 0.003, 0.014, 52, (0, 0.25, 1, 1))
    elif motif == "halloween":
        draw_stars(layer, rng, w, h, 200, "#e9d5ff", 120)
        draw_bokeh(layer, rng, w, h, 30, "#fb923c", 0.004, 0.016, 42, (0, 0.15, 1, 0.9))
        draw_cloud_bank(layer, rng, w, h, "#c4b5fd", 3, 26, 0.55, 0.9)
        draw_bats(layer, rng, w, h, 6)
        draw_crescent(layer, rng, w, h, 0.82, 0.17, 0.042)
        draw_pumpkins(layer, rng, w, h, "#170c24", 5)
    elif motif == "christmas":
        draw_stars(layer, rng, w, h, 280, "#ffffff", 140)
        draw_cloud_bank(layer, rng, w, h, "#d1fae5", 3, 24, 0.30, 0.7)
        draw_bokeh(layer, rng, w, h, 40, "#fecaca", 0.003, 0.016, 44, (0, 0.2, 1, 0.9))
        draw_bokeh(layer, rng, w, h, 16, "#fde68a", 0.003, 0.010, 40, (0, 0.3, 1, 0.85))
        draw_pines(layer, rng, w, h, "#041f14", 9)
        draw_snow(layer, rng, w, h, 300)
    elif motif == "newyear":
        draw_stars(layer, rng, w, h, 420, "#ffffff", 145)
        draw_fireworks(layer, rng, w, h, 3, ["#67e8f9", "#a5b4fc", "#fbcfe8"])
        draw_cloud_bank(layer, rng, w, h, "#93c5fd", 3, 24, 0.55, 0.9)
        draw_bokeh(layer, rng, w, h, 34, "#a5f3fc", 0.003, 0.013, 42, (0, 0.3, 1, 1))

    img = composite_pil(img, layer)
    vignette(img, 0.52)

    out = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8), "RGB")
    out = out.resize((OUT_W, OUT_H), Image.LANCZOS)
    final = np.asarray(out, dtype=np.float32) / 255.0
    add_grain(final, rng, 0.009)
    return Image.fromarray((np.clip(final, 0, 1) * 255).astype(np.uint8), "RGB")


def composite_pil(base: np.ndarray, layer: Image.Image) -> np.ndarray:
    """把 PIL RGBA 图层 alpha 合成到 numpy 底图上。"""
    h, w = base.shape[:2]
    if layer.size != (w, h):
        layer = layer.resize((w, h), Image.LANCZOS)
    base_img = Image.fromarray((np.clip(base, 0, 1) * 255).astype(np.uint8), "RGB").convert("RGBA")
    base_img.alpha_composite(layer)
    return np.asarray(base_img.convert("RGB"), dtype=np.float32) / 255.0


def main() -> None:
    os.makedirs(OUT_DIR, exist_ok=True)
    for name, spec in RECIPES.items():
        img = build(name, spec)
        path = os.path.join(OUT_DIR, name + ".jpg")
        img.save(path, "JPEG", quality=90, optimize=True, progressive=True)
        print(f"  {name:<12} {img.size[0]}x{img.size[1]}  {os.path.getsize(path) // 1024} KB")
    print(f"done: {len(RECIPES)} wallpapers -> {OUT_DIR}")


if __name__ == "__main__":
    main()
