#!/usr/bin/env python3
"""生成阅读页纸纹背景（内置资源 app/src/main/assets/bg/）。

配方来源：project/prototype/sources.html 的「质感」做法，但把 CSS 的彩色径向色斑**去掉**了
—— 用户在手机上看那两坨就是黄渍。改成纸本身该有的层次：
    受光渐变 + 帘纹（竹纸抄纸的细横纹）+ 纤维 + 棉絮云纹 + 颗粒
输出 1264x2780（用户手机真实分辨率，颗粒 1:1，放大糊掉就白做了）。

用法：python3 scripts/gen-paper-texture.py <输出目录>
"""
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H = 1264, 2780


def base(hex_color):
    rgb = np.array([int(hex_color[i:i + 2], 16) for i in (1, 3, 5)], np.float32)
    return np.tile(rgb, (H, W, 1))


def add_light_gradient(img, top, bottom):
    """竖向受光：上亮下暗。"""
    return img + np.linspace(top, bottom, H, dtype=np.float32)[:, None][..., None]


def add_laid_lines(img, rng, amp=2.6, gap=(24, 31)):
    """帘纹：抄纸留下的细横纹，间距抖动，沿横向有强弱。"""
    layer = Image.new('L', (W, H), 128)
    d = ImageDraw.Draw(layer)
    y = 0
    while y < H:
        d.line([0, y, W, y], fill=int(128 - abs(rng.normal(0, amp))), width=1)
        y += int(rng.integers(*gap))
    layer = layer.filter(ImageFilter.GaussianBlur(0.6))
    arr = np.asarray(layer, np.float32) - 128.0
    xs = Image.fromarray(((rng.normal(0, 1, (1, W // 60)) + 3) * 18).astype(np.uint8))
    xs = np.asarray(xs.resize((W, 1), Image.BICUBIC), np.float32)
    xs = (xs - xs.mean()) / (xs.std() + 1e-6) * 0.35 + 1.0
    return img + (arr * xs)[..., None]


def add_fibers(img, rng, count=22000, amp=8.0, length=(6, 32)):
    """纤维：细短线随机朝向。

    **只做暗线**：纸纤维在光下是阴影，只会比底色暗。早先写成 ±双向，
    正向上那批就成了亮白斑点（用户原话「像得了白癜风」）。
    """
    layer = Image.new('L', (W, H), 128)
    d = ImageDraw.Draw(layer)
    for _ in range(count):
        x, y = rng.integers(0, W), rng.integers(0, H)
        ang = rng.uniform(0, np.pi)
        ln = rng.integers(*length)
        v = -abs(float(rng.normal(0, amp)))          # 只减不增
        d.line([x, y, x + np.cos(ang) * ln, y + np.sin(ang) * ln],
               fill=int(128 + v), width=1)
    layer = layer.filter(ImageFilter.GaussianBlur(0.7))
    return img + (np.asarray(layer, np.float32) - 128.0)[..., None]


def add_mottle(img, rng, amp, cell):
    """棉絮云纹：低频低幅，尺度 cell 像素。"""
    l = Image.fromarray(((rng.normal(0, 1, (H // cell, W // cell)) + 3) * 20).astype(np.uint8))
    l = l.resize((W, H), Image.BICUBIC)
    a = np.asarray(l, np.float32)
    a = (a - a.mean()) / (a.std() + 1e-6)
    return img + (a * amp)[..., None]


def add_grain(img, rng, amp, bright_cap=1.2):
    """颗粒。

    轻微不对称：亮端夹在 +bright_cap*amp（默认仅 +1.2σ），暗端留到 -2σ。
    纸上不该有比底色还亮的孤立点（用户看到的就是「白点」）。
    """
    n = rng.normal(0, 1, (H, W))
    return img + (np.clip(n, -2.0, bright_cap) * amp)[..., None]


def save(img, path):
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).save(path, quality=95)
    print('->', path)


def main(out='app/src/main/assets/bg'):
    rng = np.random.default_rng(11)

    # 米纸·纸页（主体版本：用户选定）
    paper = add_grain(add_mottle(add_fibers(add_laid_lines(
        add_light_gradient(base('#F4EDE0'), 6.0, -6.0), rng), rng), rng, 1.5, 30), rng, 3.0)
    save(paper, f'{out}/kazusa-paper-page.jpg')

    # 米纸·纤维（纤维更重的手抄纸）
    fiber = add_grain(add_fibers(add_mottle(
        add_light_gradient(base('#F4EEE4'), 0, 0), rng, 2.2, 30),
        rng, count=26000, amp=7.0), rng, 4.5)
    save(fiber, f'{out}/kazusa-paper-fiber.jpg')

    # 米纸·棉絮（云纹为主，宣纸感）
    cotton = add_grain(add_fibers(add_mottle(
        add_light_gradient(base('#F5EFE6'), 0, 0), rng, 4.5, 18),
        rng, count=12000, amp=4.0, length=(5, 22)), rng, 3.2)
    save(cotton, f'{out}/kazusa-paper-cotton.jpg')

    # 夜间：暖褐（纸感）与深蓝（夜景）
    night_warm = add_grain(add_fibers(add_mottle(base('#16120E'), rng, 2.0, 26),
                                      rng, count=14000, amp=4.5), rng, 3.0)
    save(night_warm, f'{out}/kazusa-night-warm.jpg')
    night_blue = add_grain(add_fibers(add_mottle(base('#0B1420'), rng, 2.2, 26),
                                      rng, count=14000, amp=4.5), rng, 3.2)
    save(night_blue, f'{out}/kazusa-night-blue.jpg')


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'app/src/main/assets/bg')
