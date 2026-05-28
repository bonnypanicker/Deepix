import collections
import os
import sys
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
from PIL import Image
from onnxruntime.quantization import (
    CalibrationDataReader,
    QuantFormat,
    QuantType,
    quantize_static,
)


INPUT_MODEL = "vision_model.onnx"
OUTPUT_MODEL = "vision_model_android_int8.onnx"


def mb(path: str) -> float:
    return os.path.getsize(path) / (1024 * 1024)


class RandomCalibDataReader(CalibrationDataReader):
    def __init__(self, input_name: str, num_samples: int = 20, seed: int = 0):
        self._input_name = input_name
        self._num_samples = num_samples
        self._rng = np.random.default_rng(seed)
        self._i = 0

        self._mean = np.array([0.48145466, 0.4578275, 0.40821073], dtype=np.float32).reshape(
            1, 3, 1, 1
        )
        self._std = np.array([0.26862954, 0.26130258, 0.27577711], dtype=np.float32).reshape(
            1, 3, 1, 1
        )

    def get_next(self):
        if self._i >= self._num_samples:
            return None
        x = self._rng.random((1, 3, 256, 256), dtype=np.float32)
        x = (x - self._mean) / self._std
        self._i += 1
        return {self._input_name: x}


def preprocess_image(path: str) -> np.ndarray | None:
    mean = np.array([0.48145466, 0.4578275, 0.40821073], dtype=np.float32).reshape(1, 3, 1, 1)
    std = np.array([0.26862954, 0.26130258, 0.27577711], dtype=np.float32).reshape(1, 3, 1, 1)
    try:
        with Image.open(path) as img:
            img = img.convert("RGB")
            w, h = img.size
            if w <= 0 or h <= 0:
                return None
            scale = 256 / min(w, h)
            new_w = int(round(w * scale))
            new_h = int(round(h * scale))
            new_w = max(new_w, 256)
            new_h = max(new_h, 256)
            img = img.resize((new_w, new_h), Image.Resampling.BICUBIC)
            left = (new_w - 256) // 2
            top = (new_h - 256) // 2
            img = img.crop((left, top, left + 256, top + 256))
            arr = (np.asarray(img).astype(np.float32) / 255.0).transpose(2, 0, 1)[None, ...]
            arr = (arr - mean) / std
            return arr
    except Exception:
        return None


class ImageCalibDataReader(CalibrationDataReader):
    def __init__(self, input_name: str, image_paths: list[str]):
        self._input_name = input_name
        self._paths = image_paths
        self._i = 0

    def get_next(self):
        while self._i < len(self._paths):
            path = self._paths[self._i]
            self._i += 1
            x = preprocess_image(path)
            if x is None:
                continue
            return {self._input_name: x}
        return None


def scan_ops(path: str) -> collections.Counter:
    model = onnx.load(path)
    return collections.Counter(node.op_type for node in model.graph.node)


def find_calib_images(limit: int = 200) -> list[str]:
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


def main() -> None:
    if not os.path.exists(INPUT_MODEL):
        raise SystemExit(
            f"Missing {INPUT_MODEL}. Download it from:\n"
            f"https://huggingface.co/Xenova/mobileclip_s2/resolve/main/onnx/vision_model.onnx?download=true"
        )

    print(f"Input:  {INPUT_MODEL} ({mb(INPUT_MODEL):.1f} MB)")
    if os.path.exists(OUTPUT_MODEL):
        os.remove(OUTPUT_MODEL)

    sess = ort.InferenceSession(INPUT_MODEL, providers=["CPUExecutionProvider"])
    input_name = sess.get_inputs()[0].name
    print(f"Input name: {input_name}")
    print("Quantizing (static, QOperator)...")

    calib_reader: CalibrationDataReader
    image_paths = find_calib_images(limit=200)
    if len(image_paths) >= 25:
        print(f"Calibration: using {len(image_paths)} local images")
        calib_reader = ImageCalibDataReader(input_name=input_name, image_paths=image_paths)
    else:
        print("Calibration: using synthetic images (local image set not found)")
        calib_reader = RandomCalibDataReader(input_name=input_name, num_samples=50, seed=0)

    attempted = []
    for activation_type in (QuantType.QUInt8, QuantType.QInt8):
        attempted.append(activation_type.name)
        try:
            quantize_static(
                model_input=INPUT_MODEL,
                model_output=OUTPUT_MODEL,
                calibration_data_reader=calib_reader,
                quant_format=QuantFormat.QOperator,
                activation_type=activation_type,
                weight_type=QuantType.QInt8,
                op_types_to_quantize=["Conv", "MatMul"],
                per_channel=True,
            )
            break
        except Exception as exc:
            if os.path.exists(OUTPUT_MODEL):
                os.remove(OUTPUT_MODEL)
            print(f"Quantization failed with activation_type={activation_type.name}: {exc}")
    else:
        raise SystemExit(f"Quantization failed. Tried activation types: {attempted}")

    print(f"Output: {OUTPUT_MODEL} ({mb(OUTPUT_MODEL):.1f} MB)")
    ops = scan_ops(OUTPUT_MODEL)
    print("Operator summary:")
    print(f"  ConvInteger:    {ops.get('ConvInteger', 0)}")
    print(f"  QLinearConv:    {ops.get('QLinearConv', 0)}")
    print(f"  MatMulInteger:  {ops.get('MatMulInteger', 0)}")
    print(f"  QLinearMatMul:  {ops.get('QLinearMatMul', 0)}")
    print(f"  QuantizeLinear: {ops.get('QuantizeLinear', 0)}")
    print(f"  DequantizeLinear:{ops.get('DequantizeLinear', 0)}")

    if ops.get("ConvInteger", 0) != 0:
        raise SystemExit("Output model still contains ConvInteger (Android incompatible).")
    if ops.get("QLinearConv", 0) == 0:
        raise SystemExit("Output model has no QLinearConv nodes (unexpected).")

    print("OK: Output model is Android-compatible (no ConvInteger; has QLinearConv).")


if __name__ == "__main__":
    sys.exit(main())
