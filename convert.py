import os
from huggingface_hub import hf_hub_download
from ultralytics import YOLO

# 1. Define model parameters
repo_id = "erax-ai/EraX-Anti-NSFW-V1.1"
# We specifically target the 'n' (nano) version for mobile performance
filename = "erax-anti-nsfw-yolo11n-v1.1.pt" 
local_dir = "./model_files"

# Ensure the local directory exists
os.makedirs(local_dir, exist_ok=True)

print(f"Downloading {filename} from Hugging Face...")

# 2. Download the model from Hugging Face
model_path = hf_hub_download(
    repo_id=repo_id,
    filename=filename,
    local_dir=local_dir
)

print(f"Model downloaded successfully to: {model_path}")
print("Starting quantization and export to TFLite...")

# 3. Load the downloaded PyTorch model using Ultralytics YOLO
model = YOLO(model_path)

# 4. Export the model to TFLite with INT8 Quantization
# imgsz=320 reduces the input resolution (from default 640) for better speed on mobile
# int8=True forces the weights to be quantized to 8-bit integers, drastically reducing file size
export_path = model.export(
    format="tflite",
    int8=True,       # Crucial for mobile performance and memory
    imgsz=320,       # Downscale the input image size
    optimize=True    # Apply TensorFlow Lite optimizations
)

print(f"\nSuccess! Your quantized TFLite model is located at: {export_path}")