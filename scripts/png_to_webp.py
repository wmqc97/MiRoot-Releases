#!/usr/bin/env python3
"""
Convert PNG images to WebP format using Pillow.

Usage:
  # Batch convert all PNGs in a directory
  python scripts/png_to_webp.py input_dir output_dir

  # With options
  python scripts/png_to_webp.py input_dir output_dir --quality 90 --lossless

  # Convert a single file
  python scripts/png_to_webp.py input.png output.webp
"""

import argparse
import os
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    print("Error: Pillow is required. Install with: pip install Pillow", file=sys.stderr)
    sys.exit(1)

SUPPORTED_EXTENSIONS = {".png", ".jpg", ".jpeg"}


def convert_image(input_path: Path, output_path: Path, quality: int = 100, lossless: bool = True) -> None:
    """Convert a single image to WebP format."""
    img = Image.open(input_path)
    # Preserve alpha channel if present
    if img.mode in ("RGBA", "LA", "P"):
        img = img.convert("RGBA")
    elif img.mode != "RGB":
        img = img.convert("RGB")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    img.save(
        output_path,
        format="WEBP",
        lossless=lossless,
        quality=quality,
        method=6,  # slowest method = best compression
    )
    src_size = input_path.stat().st_size
    dst_size = output_path.stat().st_size
    ratio = (1 - dst_size / src_size) * 100
    print(f"  {input_path.name}: {src_size // 1024}KB → {dst_size // 1024}KB ({ratio:.0f}% saved)")


def main() -> None:
    parser = argparse.ArgumentParser(description="Convert PNG images to WebP")
    parser.add_argument("input", help="Input file or directory")
    parser.add_argument("output", help="Output file or directory")
    parser.add_argument("--quality", type=int, default=100, help="Quality 1-100 (default 100, only used when lossless=false)")
    parser.add_argument("--lossless", action="store_true", default=True, help="Use lossless compression (default)")
    parser.add_argument("--no-lossless", action="store_false", dest="lossless", help="Use lossy compression")
    parser.add_argument("--include", default="*.png", help="Glob pattern for input files (default: *.png)")
    args = parser.parse_args()

    input_path = Path(args.input)
    output_path = Path(args.output)

    if not input_path.exists():
        print(f"Error: input '{input_path}' does not exist", file=sys.stderr)
        sys.exit(1)

    if input_path.is_file():
        # Single file conversion
        if output_path.suffix.lower() not in (".webp",):
            output_path = output_path.with_suffix(".webp")
        print(f"Converting: {input_path}")
        convert_image(input_path, output_path, args.quality, args.lossless)
        print("Done.")
        return

    # Directory batch conversion
    if not output_path.exists():
        output_path.mkdir(parents=True, exist_ok=True)

    pattern = args.include
    files = list(input_path.glob(pattern))
    if not files:
        print(f"No files matching '{pattern}' found in {input_path}")
        sys.exit(0)

    print(f"Converting {len(files)} images from {input_path} → {output_path}")
    for f in sorted(files):
        out = output_path / f.with_suffix(".webp").name
        try:
            convert_image(f, out, args.quality, args.lossless)
        except Exception as e:
            print(f"  ERROR: {f.name}: {e}", file=sys.stderr)

    total_src = sum(f.stat().st_size for f in files)
    webp_files = list(output_path.glob("*.webp"))
    total_dst = sum(f.stat().st_size for f in webp_files if f.is_file())
    ratio = (1 - total_dst / total_src) * 100
    print(f"\nTotal: {total_src // 1024}KB → {total_dst // 1024}KB ({ratio:.0f}% saved)")


if __name__ == "__main__":
    main()
