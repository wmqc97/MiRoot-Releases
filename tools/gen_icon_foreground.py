from __future__ import annotations

from collections import deque
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res"
DENSITIES = ["mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"]
SRC_NAME = "ic_launcher_app.png"
DST_NAME = "ic_launcher_card.png"

# target orange as it appears in the PNG (slightly different from the vector bg)
TARGET = (255, 102, 24)
TOL = 42  # per-channel tolerance (Manhattan distance is used)


def close_to_orange(r: int, g: int, b: int) -> bool:
    return abs(r - TARGET[0]) + abs(g - TARGET[1]) + abs(b - TARGET[2]) <= TOL * 3


def process(src: Path) -> Path:
    img = Image.open(src).convert("RGBA")
    w, h = img.size
    px = img.load()

    visited = [[False] * h for _ in range(w)]
    q: deque[tuple[int, int]] = deque()

    def try_add(x: int, y: int) -> None:
        if visited[x][y]:
            return
        r, g, b, a = px[x, y]
        if a == 0:
            return
        if close_to_orange(r, g, b):
            visited[x][y] = True
            q.append((x, y))

    # Seed flood-fill from a few likely "background orange" points.
    # The rounded-square icon often has transparent corners, so edge-seeding
    # might not hit the orange region at all.
    seed_points = [
        (w // 2, h // 10),
        (w // 2, h // 8),
        (w // 10, h // 2),
        (w // 8, h // 2),
        (w - 1 - w // 10, h // 2),
    ]
    for x, y in seed_points:
        if 0 <= x < w and 0 <= y < h:
            try_add(x, y)

    # As a fallback, scan for the first orange pixel (excluding transparent).
    if not q:
        for y in range(h):
            for x in range(w):
                r, g, b, a = px[x, y]
                if a != 0 and close_to_orange(r, g, b):
                    try_add(x, y)
                    break
            if q:
                break

    # Flood only through edge-connected orange pixels.
    while q:
        x, y = q.popleft()
        for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if 0 <= nx < w and 0 <= ny < h and not visited[nx][ny]:
                r, g, b, a = px[nx, ny]
                if a != 0 and close_to_orange(r, g, b):
                    visited[nx][ny] = True
                    q.append((nx, ny))

    # Make those pixels transparent.
    for x in range(w):
        for y in range(h):
            if visited[x][y]:
                r, g, b, _a = px[x, y]
                px[x, y] = (r, g, b, 0)

    out = src.with_name(DST_NAME)
    img.save(out)
    return out


def main() -> None:
    outs: list[Path] = []
    for d in DENSITIES:
        src = ROOT / d / SRC_NAME
        if src.exists():
            outs.append(process(src))

    if not outs:
        raise SystemExit(f"No {SRC_NAME} found under {ROOT}")

    print("Generated:")
    for p in outs:
        print(f"- {p}")


if __name__ == "__main__":
    main()

