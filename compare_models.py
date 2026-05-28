import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image


def l2_normalize(x: np.ndarray) -> np.ndarray:
    denom = np.linalg.norm(x) + 1e-8
    return x / denom


def find_images(limit: int = 25) -> list[str]:
    home = Path.home()
    candidates = [
        home / "AppData" / "Local" / "Microsoft" / "Edge" / "User Data",
        home / "AppData" / "Local" / "Packages",
        home / "AppData" / "Local" / "Microsoft" / "Windows" / "INetCache",
        home / ".codex",
    ]
    exts = {".jpg", ".jpeg", ".png", ".webp"}
    paths: list[str] = []
    for root in candidates:
        if not root.exists():
            continue
        for p in root.rglob("*"):
            if len(paths) >= limit:
                return paths
            if p.is_file() and p.suffix.lower() in exts:
                paths.append(str(p))
    return paths


def preprocess_image(path: str) -> np.ndarray | None:
    mean = np.array([0.48145466, 0.4578275, 0.40821073], dtype=np.float32)
    std = np.array([0.26862954, 0.26130258, 0.27577711], dtype=np.float32)
    try:
        with Image.open(path) as img:
            img = img.convert("RGB")
            w, h = img.size
            if w <= 0 or h <= 0:
                return None
            scale = 256 / min(w, h)
            new_w = max(int(round(w * scale)), 256)
            new_h = max(int(round(h * scale)), 256)
            img = img.resize((new_w, new_h), Image.Resampling.BICUBIC)
            left = (new_w - 256) // 2
            top = (new_h - 256) // 2
            img = img.crop((left, top, left + 256, top + 256))
            arr = np.asarray(img).astype(np.float32) / 255.0
            arr = (arr - mean) / std
            arr = arr.transpose(2, 0, 1)[None, ...]
            return arr
    except Exception:
        return None


def main() -> None:
    if len(sys.argv) < 3:
        raise SystemExit("Usage: python compare_models.py <model_a.onnx> <model_b.onnx> [--images]")

    model_a = sys.argv[1]
    model_b = sys.argv[2]
    use_images = any(a == "--images" for a in sys.argv[3:])

    sess_a = ort.InferenceSession(model_a, providers=["CPUExecutionProvider"])
    sess_b = ort.InferenceSession(model_b, providers=["CPUExecutionProvider"])
    in_a = sess_a.get_inputs()[0].name
    in_b = sess_b.get_inputs()[0].name

    rng = np.random.default_rng(0)
    inputs: list[np.ndarray] = []

    if use_images:
        for p in find_images(limit=25):
            x = preprocess_image(p)
            if x is not None:
                inputs.append(x)
        if not inputs:
            raise SystemExit("No images found for --images mode")
    else:
        for _ in range(10):
            inputs.append(rng.random((1, 3, 256, 256), dtype=np.float32))

    scores: list[float] = []
    for i, x in enumerate(inputs, start=1):
        a = sess_a.run(None, {in_a: x})[0][0]
        b = sess_b.run(None, {in_b: x})[0][0]
        s = float(np.dot(l2_normalize(a), l2_normalize(b)))
        scores.append(s)
        print(f"{i:2d}: {s:.6f}")

    print("avg", sum(scores) / len(scores), "min", min(scores), "max", max(scores))


if __name__ == "__main__":
    main()

