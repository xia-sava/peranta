"""Peranta のアイコン一式を単一のジオメトリ定義から生成する。

意匠は「橋の上に立つ P」。左から流れ込む橋を P が受け取り、右へ渡す形で、
アプリ名の由来（エスペラント語 peranta = 仲介する）を表す。

生成物:
  desktopApp/icons/peranta.ico                                 exe / MSI / スタートメニュー
  desktopApp/src/main/resources/icons/peranta-<px>.png         トレイ・ウィンドウ
  androidApp/src/main/res/drawable/ic_launcher_background.xml  ランチャー（背景レイヤ）
  androidApp/src/main/res/drawable/ic_launcher_foreground.xml  ランチャー（前景レイヤ）
  androidApp/src/main/res/drawable/ic_launcher_monochrome.xml  テーマアイコン
  shared/src/androidMain/res/drawable/ic_notification.xml      通知のステータスバーアイコン
  tools/icons/peranta-icon.svg                                 意匠の参照用

実行には Pillow が要る。生成物はコミットするので、ビルド時にこのスクリプトは走らない。

    python3.12 tools/icons/generate_icons.py
"""

import struct
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw

REPO = Path(__file__).resolve().parents[2]

GREEN = "#2F9E44"
MINT = "#8CE99A"
WHITE = "#FFFFFF"

# 意匠のジオメトリ。アイコンの正方形を 0..1 に正規化した座標で持つ。
BACKGROUND_RADIUS = 0.22

STEM_LEFT = 0.31
STEM_WIDTH = 0.11
STEM_TOP = 0.22
STEM_BOTTOM = 0.80
BOWL_RADIUS = 0.19
BOWL_STROKE = 0.11

BAR_Y = 0.655
BAR_HEIGHT = 0.075
BAR_LEFT = 0.13
BAR_RIGHT = 0.87
DOT_RADIUS = 0.072

# 単色版は色で分離できないため、橋を縦棒の右で切って隙間を置く。
MONO_GAP = 0.15

MASTER = 1024  # ラスタ化はこの解像度で描いてから各サイズへ縮小する
RASTER_SIZES = (16, 24, 32, 48, 256)
ICO_SIZES = (16, 32, 48, 256)

# Android のアダプティブアイコンは 108dp のうち中央 72dp だけが必ず見える。
ADAPTIVE_VIEWPORT = 108
ADAPTIVE_VISIBLE = 72
NOTIFICATION_VIEWPORT = 24


@dataclass(frozen=True)
class RoundRect:
    x: float
    y: float
    w: float
    h: float
    r: float


@dataclass(frozen=True)
class Circle:
    cx: float
    cy: float
    r: float


@dataclass(frozen=True)
class Ring:
    cx: float
    cy: float
    outer: float
    inner: float


@dataclass(frozen=True)
class Transform:
    """正規化座標を出力座標へ移す拡大縮小と平行移動。"""

    scale: float
    dx: float = 0.0
    dy: float = 0.0

    def x(self, value: float) -> float:
        return value * self.scale + self.dx

    def y(self, value: float) -> float:
        return value * self.scale + self.dy

    def length(self, value: float) -> float:
        return value * self.scale


def bowl() -> Ring:
    return Ring(STEM_LEFT + BOWL_RADIUS, STEM_TOP + BOWL_RADIUS, BOWL_RADIUS, BOWL_RADIUS - BOWL_STROKE)


def stem() -> RoundRect:
    return RoundRect(STEM_LEFT, STEM_TOP, STEM_WIDTH, STEM_BOTTOM - STEM_TOP, STEM_WIDTH / 2)


def bar(left: float, right: float) -> RoundRect:
    return RoundRect(left, BAR_Y - BAR_HEIGHT / 2, right - left, BAR_HEIGHT, BAR_HEIGHT / 2)


def dot(cx: float) -> Circle:
    return Circle(cx, BAR_Y, DOT_RADIUS)


LEFT_DOT_X = BAR_LEFT + DOT_RADIUS * 0.55
RIGHT_DOT_X = BAR_RIGHT - DOT_RADIUS * 0.55


def background_shapes():
    return [(GREEN, RoundRect(0.0, 0.0, 1.0, 1.0, BACKGROUND_RADIUS))]


def color_mark_shapes():
    """カラー版。ミントの橋を敷き、白い P を重ねる。"""
    return [
        (MINT, bar(BAR_LEFT, BAR_RIGHT)),
        (MINT, dot(LEFT_DOT_X)),
        (MINT, dot(RIGHT_DOT_X)),
        (WHITE, bowl()),
        (WHITE, stem()),
    ]


def mono_mark_shapes():
    """単色版。橋は左から縦棒まで地続きにし、右は隙間を置いて再開する。"""
    return [
        (WHITE, bar(BAR_LEFT, STEM_LEFT + STEM_WIDTH)),
        (WHITE, dot(LEFT_DOT_X)),
        (WHITE, bar(STEM_LEFT + STEM_WIDTH + MONO_GAP, BAR_RIGHT)),
        (WHITE, dot(RIGHT_DOT_X)),
        (WHITE, bowl()),
        (WHITE, stem()),
    ]


def rgba(color: str) -> tuple[int, int, int, int]:
    value = color.lstrip("#")
    return (int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16), 255)


TRANSPARENT = (0, 0, 0, 0)


def draw_shape(draw: ImageDraw.ImageDraw, shape, transform: Transform, fill) -> None:
    if isinstance(shape, RoundRect):
        draw.rounded_rectangle(
            [
                transform.x(shape.x),
                transform.y(shape.y),
                transform.x(shape.x + shape.w),
                transform.y(shape.y + shape.h),
            ],
            radius=transform.length(shape.r),
            fill=fill,
        )
    elif isinstance(shape, Circle):
        draw_circle(draw, shape.cx, shape.cy, shape.r, transform, fill)
    elif isinstance(shape, Ring):
        draw_circle(draw, shape.cx, shape.cy, shape.outer, transform, fill)
        # 内側は透明で抜く。カラー版では背景レイヤの緑が覗く。
        draw_circle(draw, shape.cx, shape.cy, shape.inner, transform, TRANSPARENT)
    else:
        raise TypeError(f"unsupported shape: {shape!r}")


def draw_circle(draw: ImageDraw.ImageDraw, cx: float, cy: float, r: float, transform: Transform, fill) -> None:
    draw.ellipse(
        [
            transform.x(cx - r),
            transform.y(cy - r),
            transform.x(cx + r),
            transform.y(cy + r),
        ],
        fill=fill,
    )


def render_layer(shapes, size: int) -> Image.Image:
    layer = Image.new("RGBA", (size, size), TRANSPARENT)
    draw = ImageDraw.Draw(layer)
    transform = Transform(scale=size)
    for color, shape in shapes:
        draw_shape(draw, shape, transform, rgba(color))
    return layer


def master_icon() -> Image.Image:
    icon = render_layer(background_shapes(), MASTER)
    icon.alpha_composite(render_layer(color_mark_shapes(), MASTER))
    return icon


def png_bytes(master: Image.Image, size: int) -> bytes:
    from io import BytesIO

    buffer = BytesIO()
    master.resize((size, size), Image.LANCZOS).save(buffer, format="PNG", optimize=True)
    return buffer.getvalue()


def write_ico(path: Path, master: Image.Image, sizes) -> None:
    """PNG 圧縮のエントリを並べた ICO を書く。"""
    blobs = [png_bytes(master, size) for size in sizes]
    offset = 6 + 16 * len(sizes)
    directory = b""
    for size, blob in zip(sizes, blobs):
        # 幅・高さの 256 は 0 で表す。
        directory += struct.pack("<BBBBHHII", size % 256, size % 256, 0, 0, 1, 32, len(blob), offset)
        offset += len(blob)
    path.write_bytes(struct.pack("<HHH", 0, 1, len(sizes)) + directory + b"".join(blobs))


def num(value: float) -> str:
    text = f"{value:.3f}".rstrip("0").rstrip(".")
    return text if text not in ("", "-0") else "0"


def circle_path(cx: float, cy: float, r: float, transform: Transform) -> str:
    x, top, bottom, radius = transform.x(cx), transform.y(cy - r), transform.y(cy + r), transform.length(r)
    return (
        f"M{num(x)},{num(top)}"
        f"A{num(radius)},{num(radius)} 0 1 0 {num(x)},{num(bottom)}"
        f"A{num(radius)},{num(radius)} 0 1 0 {num(x)},{num(top)}Z"
    )


def path_data(shape, transform: Transform) -> str:
    if isinstance(shape, RoundRect):
        x0, y0 = transform.x(shape.x), transform.y(shape.y)
        x1, y1 = transform.x(shape.x + shape.w), transform.y(shape.y + shape.h)
        r = transform.length(shape.r)
        if r == 0:
            return f"M{num(x0)},{num(y0)}H{num(x1)}V{num(y1)}H{num(x0)}Z"
        return (
            f"M{num(x0 + r)},{num(y0)}"
            f"H{num(x1 - r)}A{num(r)},{num(r)} 0 0 1 {num(x1)},{num(y0 + r)}"
            f"V{num(y1 - r)}A{num(r)},{num(r)} 0 0 1 {num(x1 - r)},{num(y1)}"
            f"H{num(x0 + r)}A{num(r)},{num(r)} 0 0 1 {num(x0)},{num(y1 - r)}"
            f"V{num(y0 + r)}A{num(r)},{num(r)} 0 0 1 {num(x0 + r)},{num(y0)}Z"
        )
    if isinstance(shape, Circle):
        return circle_path(shape.cx, shape.cy, shape.r, transform)
    if isinstance(shape, Ring):
        return circle_path(shape.cx, shape.cy, shape.outer, transform) + circle_path(
            shape.cx, shape.cy, shape.inner, transform
        )
    raise TypeError(f"unsupported shape: {shape!r}")


def is_ring(shape) -> bool:
    return isinstance(shape, Ring)


def vector_drawable(shapes, transform: Transform, viewport: int, tinted: bool) -> str:
    """Android の vector drawable を組み立てる。pathData は SVG のパス構文と同じ。"""
    attributes = [
        f'android:width="{viewport}dp"',
        f'android:height="{viewport}dp"',
        f'android:viewportWidth="{viewport}"',
        f'android:viewportHeight="{viewport}"',
    ]
    if tinted:
        attributes.append('android:tint="#FFFFFFFF"')

    header = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
    ]
    header += [f"    {attribute}" for attribute in attributes[:-1]]
    header.append(f"    {attributes[-1]}>")

    body = []
    for color, shape in shapes:
        body.append("    <path")
        body.append(f'        android:fillColor="{color}"')
        if is_ring(shape):
            body.append('        android:fillType="evenOdd"')
        body.append(f'        android:pathData="{path_data(shape, transform)}" />')

    return "\n".join(header + body + ["</vector>", ""])


def svg_document(shapes, transform: Transform, size: int) -> str:
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
        f'viewBox="0 0 {size} {size}">',
    ]
    for color, shape in shapes:
        rule = ' fill-rule="evenodd"' if is_ring(shape) else ""
        lines.append(f'    <path fill="{color}"{rule} d="{path_data(shape, transform)}" />')
    lines.append("</svg>")
    lines.append("")
    return "\n".join(lines)


def adaptive_transform() -> Transform:
    """アイコンの正方形を 108dp の中央 72dp へ収める。"""
    offset = (ADAPTIVE_VIEWPORT - ADAPTIVE_VISIBLE) / 2
    return Transform(scale=ADAPTIVE_VISIBLE, dx=offset, dy=offset)


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")
    print(f"  {path.relative_to(REPO)}")


def main() -> None:
    master = master_icon()
    print("生成:")

    write_ico(REPO / "desktopApp/icons/peranta.ico", master, ICO_SIZES)
    print("  desktopApp/icons/peranta.ico")

    resources = REPO / "desktopApp/src/main/resources/icons"
    resources.mkdir(parents=True, exist_ok=True)
    for size in RASTER_SIZES:
        target = resources / f"peranta-{size}.png"
        target.write_bytes(png_bytes(master, size))
        print(f"  {target.relative_to(REPO)}")

    adaptive = adaptive_transform()
    write(
        REPO / "androidApp/src/main/res/drawable/ic_launcher_background.xml",
        vector_drawable(
            [(GREEN, RoundRect(0.0, 0.0, 1.0, 1.0, 0.0))],
            Transform(scale=ADAPTIVE_VIEWPORT),
            ADAPTIVE_VIEWPORT,
            tinted=False,
        ),
    )
    write(
        REPO / "androidApp/src/main/res/drawable/ic_launcher_foreground.xml",
        vector_drawable(color_mark_shapes(), adaptive, ADAPTIVE_VIEWPORT, tinted=False),
    )
    write(
        REPO / "androidApp/src/main/res/drawable/ic_launcher_monochrome.xml",
        vector_drawable(mono_mark_shapes(), adaptive, ADAPTIVE_VIEWPORT, tinted=False),
    )
    write(
        REPO / "shared/src/androidMain/res/drawable/ic_notification.xml",
        vector_drawable(
            mono_mark_shapes(),
            Transform(scale=NOTIFICATION_VIEWPORT),
            NOTIFICATION_VIEWPORT,
            tinted=True,
        ),
    )
    write(
        Path(__file__).with_name("peranta-icon.svg"),
        svg_document(background_shapes() + color_mark_shapes(), Transform(scale=256), 256),
    )


if __name__ == "__main__":
    main()
