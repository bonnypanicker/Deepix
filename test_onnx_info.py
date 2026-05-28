import onnx
import sys
import os

def check_model(model_path):
    print(f"\n--- Checking {os.path.basename(model_path)} ---")
    try:
        # Load the ONNX model
        model = onnx.load(model_path)
        # Check that the model is well formed
        onnx.checker.check_model(model)
        print("Model is well-formed and valid!")
        
        # Print input and output details
        print("\nInputs:")
        for input in model.graph.input:
            shape = []
            for dim in input.type.tensor_type.shape.dim:
                shape.append(dim.dim_value if dim.HasField("dim_value") else dim.dim_param)
            print(f" - Name: {input.name}")
            print(f" - Shape: {shape}")
            
        print("\nOutputs:")
        for output in model.graph.output:
            shape = []
            for dim in output.type.tensor_type.shape.dim:
                shape.append(dim.dim_value if dim.HasField("dim_value") else dim.dim_param)
            print(f" - Name: {output.name}")
            print(f" - Shape: {shape}")
            
    except Exception as e:
        print(f"Failed to load or check model: {e}")

if __name__ == "__main__":
    vision_path = os.path.join("app", "src", "main", "assets", "vision_model_int8.onnx")
    text_path = os.path.join("app", "src", "main", "assets", "text_model_int8.onnx")
    
    check_model(vision_path)
    check_model(text_path)
