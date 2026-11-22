"""
Exact Python port of the app's search path: ClipTokenizer + ImageEncoder + TextEncoder.

Mirrors, line for line, what the Android app does at query time:

  ImageEncoder.kt  resize shortest edge -> 256 (bilinear), centre crop 256x256, RGB / 255,
                   no mean/std (preprocessor_config.json says do_normalize:false - the shipped
                   graph applies them internally), L2-normalise the output.
  TextTokenizer.kt CLIP BPE over the lowercased text, the caption prefix "a photo of " added
                   when missing, <|startoftext|>/<|endoftext|>, padded to 77.
  TextEncoder.kt   input_ids + attention_mask, flatten the first output, L2-normalise.
  QueryVariants    the same phrasings the app tries, de-duplicated after the prefix.

Anything that changes here changes every number the calibration reports, so the selftest in
calibrate.py checks the properties this module has to keep (unit norms, prefix idempotence,
distinct phrasings) instead of trusting the port.

Usage:
    from app_clip import AppClip
    clip = AppClip()
    image_vectors = clip.encode_images(paths)     # [N, 512], unit length
    text_vector = clip.encode_text("dog")         # [512], unit length
"""

from __future__ import annotations

import json
import os

import numpy as np
import onnxruntime as ort
import regex as re
from PIL import Image, ImageOps

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")

CONTEXT_LENGTH = 77
IMAGE_SIZE = 256
PHOTO_PREFIX = "a photo of "

_TOKEN_PATTERN = re.compile(
    # Single digits, not runs: the BPE merges were trained over one digit per piece (see the note in
    # TextTokenizer.kt). This file is a port of that tokenizer, so the pattern has to match it.
    r"""<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+""",
    re.IGNORECASE,
)


def _bytes_to_unicode() -> dict[int, str]:
    """The GPT-2 byte <-> unicode table the CLIP vocab was built on."""
    bs = list(range(33, 127)) + list(range(161, 173)) + list(range(174, 256))
    cs = bs[:]
    n = 0
    for b in range(256):
        if b not in bs:
            bs.append(b)
            cs.append(256 + n)
            n += 1
    return {b: chr(c) for b, c in zip(bs, cs)}


def with_photo_prefix(query: str) -> str:
    """TextTokenizer.withPhotoPrefix - idempotent, so the app may apply it twice."""
    cleaned = re.sub(r"\s+", " ", query.strip())
    if not cleaned:
        return "a photo"
    return cleaned if cleaned.lower().startswith(PHOTO_PREFIX) else PHOTO_PREFIX + cleaned


def query_variants(query: str) -> list[str]:
    """QueryVariants.build - the same three captions the app encodes for a plain query."""
    cleaned = re.sub(r"\s+", " ", query.strip())
    if not cleaned:
        return [query]
    shapes = [cleaned, "a photo of " + cleaned, "a picture of " + cleaned, cleaned + " photo"]
    return list(dict.fromkeys(with_photo_prefix(shape) for shape in shapes))


def l2(vector: np.ndarray) -> np.ndarray:
    vector = np.asarray(vector, dtype=np.float32).reshape(-1)
    norm = float(np.sqrt((vector * vector).sum()))
    return vector if norm < 1e-8 else vector / norm


class ClipTokenizer:
    def __init__(self, tokenizer_json: str):
        with open(tokenizer_json, "r", encoding="utf-8") as handle:
            model = json.load(handle)["model"]
        self.vocab: dict[str, int] = model["vocab"]
        self.ranks: dict[tuple[str, str], int] = {}
        for index, merge in enumerate(model["merges"]):
            parts = merge.split(" ") if isinstance(merge, str) else list(merge)
            if len(parts) == 2:
                self.ranks[(parts[0], parts[1])] = index
        self.byte_encoder = _bytes_to_unicode()
        self.sot = self.vocab.get("<|startoftext|>", 49406)
        self.eot = self.vocab.get("<|endoftext|>", 49407)
        self.cache: dict[str, str] = {}

    def _bpe(self, token: str) -> str:
        if token in self.cache:
            return self.cache[token]
        word = list(token)
        word[-1] += "</w>"
        while len(word) > 1:
            pairs = {(word[i], word[i + 1]) for i in range(len(word) - 1)}
            best = min(pairs, key=lambda pair: self.ranks.get(pair, 1 << 30))
            if best not in self.ranks:
                break
            merged, index = [], 0
            while index < len(word):
                if index < len(word) - 1 and (word[index], word[index + 1]) == best:
                    merged.append(word[index] + word[index + 1])
                    index += 2
                else:
                    merged.append(word[index])
                    index += 1
            word = merged
        result = " ".join(word)
        self.cache[token] = result
        return result

    def tokenize(self, text: str) -> list[int]:
        out: list[int] = []
        for match in _TOKEN_PATTERN.findall(text.lower()):
            if match == "<|startoftext|>":
                out.append(self.sot)
                continue
            if match == "<|endoftext|>":
                out.append(self.eot)
                continue
            encoded = "".join(self.byte_encoder[b] for b in match.encode("utf-8"))
            out.extend(self.vocab.get(piece, self.eot) for piece in self._bpe(encoded).split(" "))
        return out

    def encode(self, query: str) -> tuple[np.ndarray, np.ndarray]:
        tokens = [self.sot]
        for token in self.tokenize(with_photo_prefix(query)):
            if len(tokens) >= CONTEXT_LENGTH - 1:
                break
            tokens.append(token)
        tokens.append(self.eot)
        ids = np.zeros(CONTEXT_LENGTH, dtype=np.int64)
        mask = np.zeros(CONTEXT_LENGTH, dtype=np.int64)
        used = min(len(tokens), CONTEXT_LENGTH)
        ids[:used] = tokens[:used]
        mask[:used] = 1
        if used == CONTEXT_LENGTH:
            ids[-1] = self.eot
        return ids, mask


class AppClip:
    """The two shipped encoders, loaded once."""

    def __init__(self, vision: str | None = None, text: str | None = None, threads: int = 4):
        options = ort.SessionOptions()
        options.intra_op_num_threads = threads
        self.vision = ort.InferenceSession(
            vision or os.path.join(ASSETS, "s1.ort"),
            sess_options=options,
            providers=["CPUExecutionProvider"],
        )
        self.vision_input = self.vision.get_inputs()[0].name
        self.vision_output = self.vision.get_outputs()[0].name
        self.tokenizer = ClipTokenizer(os.path.join(ASSETS, "tokenizer.json"))
        self.text = ort.InferenceSession(
            text or os.path.join(ASSETS, "s2.ort"),
            sess_options=options,
            providers=["CPUExecutionProvider"],
        )
        names = [i.name for i in self.text.get_inputs()]
        self.ids_input = next((n for n in names if "input_ids" in n.lower()), names[0])
        self.mask_input = next((n for n in names if "attention" in n.lower()), None)
        self.text_output = self.text.get_outputs()[0].name

    @property
    def embedding_dim(self) -> int:
        return int(self.vision.get_outputs()[0].shape[-1])

    @staticmethod
    def preprocess(image: Image.Image) -> np.ndarray:
        # GalleryRepository.loadBitmap decodes with decodeOrientedBitmap, so the model never sees an
        # unrotated sensor image. Same here: EXIF orientation is applied before resize.
        image = ImageOps.exif_transpose(image).convert("RGB")
        width, height = image.size
        scale = IMAGE_SIZE / min(width, height)
        new_width = max(IMAGE_SIZE, round(width * scale))
        new_height = max(IMAGE_SIZE, round(height * scale))
        image = image.resize((new_width, new_height), Image.BILINEAR)
        left = max(0, (new_width - IMAGE_SIZE) // 2)
        top = max(0, (new_height - IMAGE_SIZE) // 2)
        image = image.crop((left, top, left + IMAGE_SIZE, top + IMAGE_SIZE))
        pixels = np.asarray(image, dtype=np.float32) / 255.0
        return np.transpose(pixels, (2, 0, 1))[None, :, :, :]

    def encode_images(self, paths: list[str], batch: int = 4) -> np.ndarray:
        """Encode image files; returns [N, 512]. Raises on the first unreadable file."""
        vectors: list[np.ndarray] = []
        for start in range(0, len(paths), batch):
            chunk = paths[start : start + batch]
            prepared = np.concatenate([self.preprocess(Image.open(p)) for p in chunk], axis=0)
            out = self.vision.run([self.vision_output], {self.vision_input: prepared})[0]
            out = np.asarray(out, dtype=np.float32).reshape(len(chunk), -1)
            vectors.extend(l2(row) for row in out)
        return np.stack(vectors) if vectors else np.zeros((0, self.embedding_dim), np.float32)

    def encode_text(self, query: str) -> np.ndarray:
        ids, mask = self.tokenizer.encode(query)
        feeds = {self.ids_input: ids[None, :]}
        if self.mask_input is not None:
            feeds[self.mask_input] = mask[None, :]
        out = self.text.run([self.text_output], feeds)[0]
        return l2(out)

    def scores(self, query: str, image_vectors: np.ndarray) -> np.ndarray:
        """The app's per-photo score: max cosine over its query variants."""
        texts = np.stack([self.encode_text(variant) for variant in query_variants(query)])
        return (image_vectors @ texts.T).max(axis=1)
