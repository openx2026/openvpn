#!/usr/bin/env python3
"""Generate Android adaptive launcher icons from app/logo.png."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from PIL import Image

# Adaptive icon layer sizes (px) per density — 108dp baseline
ADAPTIVE_SIZES = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}

LEGACY_SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

BACKGROUND_COLOR = (0x1A, 0x73, 0xE8, 255)

# Adaptive icon safe zone is ~66dp of 108dp; logo bolt touches edges — use 62%.
DEFAULT_FOREGROUND_SCALE = 0.62
DEFAULT_LEGACY_SCALE = 0.88


def resize_and_center(logo: Image.Image, canvas_size: int, scale: float) -> Image.Image:
    target = int(round(canvas_size * scale))
    resized = logo.resize((target, target), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    offset = (canvas_size - target) // 2
    canvas.paste(resized, (offset, offset), resized)
    return canvas


def solid_background(size: int, color: tuple[int, ...]) -> Image.Image:
    return Image.new("RGBA", (size, size), color)


def composite_legacy(logo: Image.Image, size: int, scale: float) -> Image.Image:
    bg = solid_background(size, BACKGROUND_COLOR)
    fg = resize_and_center(logo, size, scale)
    bg.alpha_composite(fg)
    return bg


def write_adaptive_xml(path: Path) -> None:
    path.write_text(
        """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
""",
        encoding="utf-8",
    )


def cleanup_stale(res_dir: Path) -> None:
    for rel in (
        "drawable/ic_launcher_foreground.xml",
        "drawable/ic_launcher_background.xml",
        "drawable/ic_launcher_foreground_inset.xml",
    ):
        path = res_dir / rel
        if path.exists():
            path.unlink()
            print(f"  removed {rel}")

    for folder in ADAPTIVE_SIZES:
        mipmap_dir = res_dir / folder
        if not mipmap_dir.is_dir():
            continue
        for webp in mipmap_dir.glob("ic_launcher*.webp"):
            webp.unlink()
            print(f"  removed {folder}/{webp.name}")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Generate Android launcher icons from app/logo.png"
    )
    parser.add_argument(
        "--source",
        type=Path,
        help="Source logo PNG (default: app/logo.png)",
    )
    parser.add_argument(
        "--res-dir",
        type=Path,
        help="Android res/ directory (default: app/src/main/res)",
    )
    parser.add_argument(
        "--foreground-scale",
        type=float,
        default=DEFAULT_FOREGROUND_SCALE,
        help=f"Foreground scale within adaptive canvas (default: {DEFAULT_FOREGROUND_SCALE})",
    )
    parser.add_argument(
        "--legacy-scale",
        type=float,
        default=DEFAULT_LEGACY_SCALE,
        help=f"Legacy icon scale (default: {DEFAULT_LEGACY_SCALE})",
    )
    args = parser.parse_args()

    root = Path(__file__).resolve().parent.parent
    source = args.source or root / "app" / "logo.png"
    res_dir = args.res_dir or root / "app" / "src" / "main" / "res"

    if not source.is_file():
        print(f"error: source not found: {source}", file=sys.stderr)
        return 1

    logo = Image.open(source).convert("RGBA")
    print(f"source: {source} ({logo.size[0]}x{logo.size[1]})")
    print(
        f"foreground scale: {args.foreground_scale:.0%}, "
        f"legacy scale: {args.legacy_scale:.0%}"
    )

    for folder, adaptive_size in ADAPTIVE_SIZES.items():
        out_dir = res_dir / folder
        out_dir.mkdir(parents=True, exist_ok=True)

        fg = resize_and_center(logo, adaptive_size, args.foreground_scale)
        fg.save(out_dir / "ic_launcher_foreground.png", optimize=True)
        print(f"  ic_launcher_foreground.png -> {folder} ({adaptive_size}px)")

        bg = solid_background(adaptive_size, BACKGROUND_COLOR)
        bg.save(out_dir / "ic_launcher_background.png", optimize=True)
        print(f"  ic_launcher_background.png -> {folder} ({adaptive_size}px)")

    for folder, legacy_size in LEGACY_SIZES.items():
        out_dir = res_dir / folder
        icon = composite_legacy(logo, legacy_size, args.legacy_scale)
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            icon.save(out_dir / name, optimize=True)
        print(f"  ic_launcher.png + round -> {folder} ({legacy_size}px)")

    anydpi = res_dir / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    write_adaptive_xml(anydpi / "ic_launcher.xml")
    write_adaptive_xml(anydpi / "ic_launcher_round.xml")
    print("  updated mipmap-anydpi-v26/ic_launcher*.xml")

    print("cleanup:")
    cleanup_stale(res_dir)
    print("done.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
