import onnxruntime as ort
import numpy as np
import json
import os
from tokenizers import Tokenizer

def l2_normalize(x):
    denom = np.linalg.norm(x) + 1e-8
    return x / denom

def compute_cosine(a, b):
    return float(np.dot(l2_normalize(a), l2_normalize(b)))

def load_tokenizer():
    tokenizer_path = os.path.join("app", "src", "main", "assets", "tokenizer.json")
    return Tokenizer.from_file(tokenizer_path)

def tokenize(tokenizer, text, max_len=77):
    # The tokenizer might add special tokens.
    output = tokenizer.encode(text)
    ids = output.ids
    
    # PAD with 0s (assuming 0 is pad token for this CLIP tokenizer)
    # CLIP typically uses 49406 for BOS, 49407 for EOS
    if len(ids) > max_len:
        ids = ids[:max_len]
        # Force EOS
        ids[-1] = 49407
    else:
        ids = ids + [0] * (max_len - len(ids))
        
    mask = [1 if i != 0 else 0 for i in ids]
    
    return np.array([ids], dtype=np.int64), np.array([mask], dtype=np.int64)

def test():
    # Load Models
    vision_model_path = os.path.join("app", "src", "main", "assets", "vision_model_fp16.onnx")
    text_model_path = os.path.join("app", "src", "main", "assets", "text_model_int8.onnx")
    
    vision_session = ort.InferenceSession(vision_model_path, providers=["CPUExecutionProvider"])
    text_session = ort.InferenceSession(text_model_path, providers=["CPUExecutionProvider"])
    
    tokenizer = load_tokenizer()
    
    # 1. Create Image Tensors (1, 3, 256, 256)
    # Normal image simulation (random normal, mean=0, std=1)
    np.random.seed(42)
    img_normal_1 = np.random.randn(1, 3, 256, 256).astype(np.float32)
    img_normal_2 = np.random.randn(1, 3, 256, 256).astype(np.float32)
    
    # Solid Black (after normalization, black is (0 - mean)/std)
    mean = np.array([0.48145466, 0.4578275, 0.40821073]).reshape(3, 1, 1)
    std = np.array([0.26862954, 0.26130258, 0.27577711]).reshape(3, 1, 1)
    
    img_black = np.zeros((1, 3, 256, 256), dtype=np.float32)
    img_black = (img_black - mean) / std
    img_black = img_black.astype(np.float32)
    
    # Solid Red (1, 0, 0)
    img_red = np.zeros((1, 3, 256, 256), dtype=np.float32)
    img_red[:, 0, :, :] = 1.0
    img_red = (img_red - mean) / std
    img_red = img_red.astype(np.float32)
    
    # Blurred/Noise (uniform noise, which is unstructured like severe blur)
    img_blur = np.random.uniform(0.4, 0.6, (1, 3, 256, 256)).astype(np.float32)
    img_blur = (img_blur - mean) / std
    img_blur = img_blur.astype(np.float32)
    
    images = {
        "Random Noise (Normal 1)": img_normal_1,
        "Random Noise (Normal 2)": img_normal_2,
        "Solid Black": img_black,
        "Solid Red": img_red,
        "Blurred/Uniform": img_blur
    }
    
    # Generate image embeddings
    img_embeddings = {}
    v_input_name = vision_session.get_inputs()[0].name
    for name, tensor in images.items():
        out = vision_session.run(None, {v_input_name: tensor})[0][0]
        img_embeddings[name] = l2_normalize(out)
        
    # 2. Texts
    texts = [
        "a photo of a cat",
        "blurred",
        "black",
        "a solid red background",
        "qewryasdf zxvc", # Gibberish
        "asdfasdf",       # Gibberish 2
        "nothingness",
    ]
    
    # Generate text embeddings
    txt_embeddings = {}
    t_input_ids = text_session.get_inputs()[0].name
    try:
        t_attention = text_session.get_inputs()[1].name
    except:
        t_attention = None
        
    for text in texts:
        ids, mask = tokenize(tokenizer, text)
        inputs = {t_input_ids: ids}
        if t_attention:
            inputs[t_attention] = mask
        out = text_session.run(None, inputs)[0][0]
        txt_embeddings[text] = l2_normalize(out)
        
    # 3. Calculate Scores & Test Logic
    print("=== SIMULATION RESULTS ===")
    
    results_json = {}
    for text in texts:
        print(f"\nQuery: '{text}'")
        scores = {}
        for img_name in images.keys():
            score = compute_cosine(txt_embeddings[text], img_embeddings[img_name])
            scores[img_name] = score
            
        # Sort scores
        sorted_scores = sorted(scores.items(), key=lambda x: x[1], reverse=True)
        best_score = sorted_scores[0][1]
        
        print(f"  Best Score: {best_score:.4f} ({sorted_scores[0][0]})")
        results_json[text] = {"best_score": best_score, "matches": {}}
        
        for name, score in sorted_scores:
            print(f"  - {name}: {score:.4f}")
            results_json[text]["matches"][name] = float(score)

    with open("simulation_scores.json", "w") as f:
        json.dump(results_json, f, indent=2)

if __name__ == "__main__":
    test()
