import onnxruntime as ort
import numpy as np
import sys
import os

def test_vision_model():
    print("--- Testing Vision Model ---")
    model_path = os.path.join("app", "src", "main", "assets", "vision_model_int8.onnx")
    
    try:
        session = ort.InferenceSession(model_path)
        input_name = session.get_inputs()[0].name
        output_name = session.get_outputs()[0].name
        
        print(f"Loaded successfully!")
        print(f"Input node name: {input_name}")
        print(f"Output node name: {output_name}")
        
        # Create a dummy image tensor: [batch=1, channels=3, height=256, width=256]
        dummy_image = np.random.randn(1, 3, 256, 256).astype(np.float32)
        
        # Run inference
        outputs = session.run([output_name], {input_name: dummy_image})
        
        print(f"Output shape: {outputs[0].shape}")
        print(f"Successfully generated vision embeddings!")
        
    except Exception as e:
        print(f"Failed to run vision model: {e}")

def test_text_model():
    print("\n--- Testing Text Model ---")
    model_path = os.path.join("app", "src", "main", "assets", "text_model_int8.onnx")
    
    try:
        session = ort.InferenceSession(model_path)
        inputs = session.get_inputs()
        output_name = session.get_outputs()[0].name
        
        print(f"Loaded successfully!")
        print(f"Output node name: {output_name}")
        
        # Create dummy text tensors
        dummy_input_ids = np.ones((1, 77), dtype=np.int64)
        dummy_attention_mask = np.ones((1, 77), dtype=np.int64)
        
        input_dict = {}
        for inp in inputs:
            print(f"Input node required: {inp.name} (shape: {inp.shape})")
            if "input_ids" in inp.name.lower():
                input_dict[inp.name] = dummy_input_ids
            elif "attention" in inp.name.lower():
                input_dict[inp.name] = dummy_attention_mask
            else:
                input_dict[inp.name] = dummy_input_ids # Fallback
                
        # Run inference
        outputs = session.run([output_name], input_dict)
        
        print(f"Output shape: {outputs[0].shape}")
        print(f"Successfully generated text embeddings!")
        
    except Exception as e:
        print(f"Failed to run text model: {e}")

if __name__ == "__main__":
    test_vision_model()
    test_text_model()
