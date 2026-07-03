"""
Faithful Python port of the app's ClipTokenizer + ONNX image/text encoders (MobileCLIP-S2).

Kept deliberately close to ClipTokenizer.kt / TextEncoder.kt / ImageEncoder.kt so the embeddings
produced here match what the Android app computes on-device.
"""
import json
import os
import regex as re
import numpy as np
from PIL import Image
import onnxruntime as ort

ASSETS = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "assets")

CONTEXT_LENGTH = 77
PHOTO_PREFIX = "a photo of "
IMAGE_SIZE = 256
MEAN = np.array([0.48145466, 0.4578275, 0.40821073], dtype=np.float32)
STD = np.array([0.26862954, 0.26130258, 0.27577711], dtype=np.float32)

_PAT = re.compile(
    r"""<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]+|[^\s\p{L}\p{N}]+""",
    re.IGNORECASE,
)


def _bytes_to_unicode():
    bs = list(range(33, 127)) + list(range(161, 173)) + list(range(174, 256))
    cs = bs[:]
    n = 0
    for b in range(256):
        if b not in bs:
            bs.append(b)
            cs.append(256 + n)
            n += 1
    return {b: chr(c) for b, c in zip(bs, cs)}


class ClipTokenizer:
    def __init__(self, tokenizer_json):
        with open(tokenizer_json, "r", encoding="utf-8") as f:
            model = json.load(f)["model"]
        self.vocab = model["vocab"]
        merges = model["merges"]
        self.bpe_ranks = {}
        for i, m in enumerate(merges):
            parts = m.split(" ") if isinstance(m, str) else list(m)
            if len(parts) == 2:
                self.bpe_ranks[(parts[0], parts[1])] = i
        self.byte_encoder = _bytes_to_unicode()
        self.sot = self.vocab.get("<|startoftext|>", 49406)
        self.eot = self.vocab.get("<|endoftext|>", 49407)
        self.cache = {}

    def _normalize(self, q):
        cleaned = re.sub(r"\s+", " ", q.strip())
        if not cleaned:
            return "a photo"
        return cleaned if cleaned.lower().startswith(PHOTO_PREFIX) else PHOTO_PREFIX + cleaned

    def _get_pairs(self, word):
        return {(word[i], word[i + 1]) for i in range(len(word) - 1)}

    def _bpe(self, token):
        if token in self.cache:
            return self.cache[token]
        if not token:
            return token
        word = list(token)
        word[-1] = word[-1] + "</w>"
        while True:
            pairs = self._get_pairs(word)
            if not pairs:
                break
            best = min(pairs, key=lambda p: self.bpe_ranks.get(p, 1 << 30))
            if best not in self.bpe_ranks:
                break
            new_word = []
            i = 0
            while i < len(word):
                if i < len(word) - 1 and word[i] == best[0] and word[i + 1] == best[1]:
                    new_word.append(best[0] + best[1])
                    i += 2
                else:
                    new_word.append(word[i])
                    i += 1
            word = new_word
            if len(word) == 1:
                break
        result = " ".join(word)
        self.cache[token] = result
        return result

    def _tokenize(self, text):
        out = []
        for match in _PAT.findall(text.lower()):
            if match == "<|startoftext|>":
                out.append(self.sot); continue
            if match == "<|endoftext|>":
                out.append(self.eot); continue
            byte_encoded = "".join(self.byte_encoder[b] for b in match.encode("utf-8"))
            for piece in self._bpe(byte_encoded).split(" "):
                out.append(self.vocab.get(piece, self.eot))
        return out

    def encode(self, query):
        tokens = [self.sot]
        for t in self._tokenize(self._normalize(query)):
            tokens.append(t)
            if len(tokens) >= CONTEXT_LENGTH - 1:
                break
        tokens.append(self.eot)
        input_ids = np.zeros(CONTEXT_LENGTH, dtype=np.int64)
        attention = np.zeros(CONTEXT_LENGTH, dtype=np.int64)
        n = min(len(tokens), CONTEXT_LENGTH)
        for i in range(n):
            input_ids[i] = tokens[i]
            attention[i] = 1
        if n == CONTEXT_LENGTH:
            input_ids[-1] = self.eot
        return input_ids, attention


def l2(v):
    v = np.asarray(v, dtype=np.float32).reshape(-1)
    n = np.sqrt((v * v).sum())
    return v if n < 1e-8 else v / n


class TextEncoder:
    def __init__(self, model_path, tokenizer):
        self.sess = ort.InferenceSession(model_path, providers=["CPUExecutionProvider"])
        names = [i.name for i in self.sess.get_inputs()]
        self.ids_name = next((n for n in names if "input_ids" in n.lower()), names[0])
        self.mask_name = next((n for n in names if "attention" in n.lower()), None)
        self.out_name = self.sess.get_outputs()[0].name
        self.tok = tokenizer

    def encode(self, text):
        ids, mask = self.tok.encode(text)
        feeds = {self.ids_name: ids[None, :]}
        if self.mask_name is not None:
            feeds[self.mask_name] = mask[None, :]
        out = self.sess.run([self.out_name], feeds)[0]
        return l2(out)


class ImageEncoder:
    def __init__(self, model_path):
        self.sess = ort.InferenceSession(model_path, providers=["CPUExecutionProvider"])
        self.in_name = self.sess.get_inputs()[0].name
        self.out_name = self.sess.get_outputs()[0].name

    def _preprocess(self, img: Image.Image):
        img = img.convert("RGB")
        w, h = img.size
        scale = IMAGE_SIZE / min(w, h)
        nw, nh = max(IMAGE_SIZE, round(w * scale)), max(IMAGE_SIZE, round(h * scale))
        img = img.resize((nw, nh), Image.BILINEAR)
        left = max(0, (nw - IMAGE_SIZE) // 2)
        top = max(0, (nh - IMAGE_SIZE) // 2)
        img = img.crop((left, top, left + IMAGE_SIZE, top + IMAGE_SIZE))
        arr = np.asarray(img, dtype=np.float32) / 255.0
        arr = (arr - MEAN) / STD
        chw = np.transpose(arr, (2, 0, 1))[None, :, :, :].astype(np.float32)
        return chw

    def encode(self, img: Image.Image):
        chw = self._preprocess(img)
        out = self.sess.run([self.out_name], {self.in_name: chw})[0]
        return l2(out)


def load_all(vision_path=None):
    tok = ClipTokenizer(os.path.join(ASSETS, "tokenizer.json"))
    text = TextEncoder(os.path.join(ASSETS, "text_model_int8.onnx"), tok)
    # Default to the local fp32 promotion (fp16 -> NaN on CPU EP); app still ships fp16.
    if vision_path is None:
        local = os.path.join(os.path.dirname(__file__), "vision_model_fp32.onnx")
        vision_path = local if os.path.exists(local) else os.path.join(ASSETS, "vision_model_fp16.onnx")
    image = ImageEncoder(vision_path)
    return text, image
