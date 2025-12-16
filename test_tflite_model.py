"""
TFLite YOLO Document Detection Model Tester
============================================
This script tests the best.tflite YOLO model with any image.

Usage:
    python test_tflite_model.py <image_path>

Example:
    python test_tflite_model.py "C:\path\to\your\drivers_license.jpg"

Requirements:
    pip install numpy pillow tflite-runtime
    OR
    pip install numpy pillow tensorflow
"""

import sys
import os
import numpy as np
from PIL import Image

# Try to import tflite interpreter
try:
    import tflite_runtime.interpreter as tflite
    print("[OK] Using tflite-runtime")
except ImportError:
    try:
        import tensorflow as tf
        tflite = tf.lite
        print("[OK] Using TensorFlow Lite")
    except ImportError:
        print("[ERROR] Please install tflite-runtime or tensorflow:")
        print("  pip install tflite-runtime")
        print("  OR")
        print("  pip install tensorflow")
        sys.exit(1)


def load_model(model_path):
    """Load the TFLite model and get input/output details."""
    interpreter = tflite.Interpreter(model_path=model_path)
    interpreter.allocate_tensors()

    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()

    print("\n=== MODEL INFO ===")
    print(f"Input shape:  {input_details[0]['shape']}")
    print(f"Input dtype:  {input_details[0]['dtype']}")
    print(f"Output shape: {output_details[0]['shape']}")
    print(f"Output dtype: {output_details[0]['dtype']}")

    return interpreter, input_details, output_details


def preprocess_image(image_path, input_shape):
    """Preprocess image to match model input requirements."""
    # Load image
    img = Image.open(image_path)
    print(f"\nOriginal image size: {img.size}")
    print(f"Original image mode: {img.mode}")

    # Convert to RGB if needed
    if img.mode != 'RGB':
        img = img.convert('RGB')
        print(f"Converted to RGB")

    # Get target size from input shape [batch, height, width, channels]
    target_height = input_shape[1]
    target_width = input_shape[2]

    # Resize
    img_resized = img.resize((target_width, target_height), Image.Resampling.LANCZOS)
    print(f"Resized to: {img_resized.size}")

    # Convert to numpy array and normalize to [0, 1]
    img_array = np.array(img_resized, dtype=np.float32) / 255.0

    # Add batch dimension
    img_array = np.expand_dims(img_array, axis=0)
    print(f"Input tensor shape: {img_array.shape}")

    return img_array


def run_inference(interpreter, input_details, output_details, input_data):
    """Run inference and return output."""
    interpreter.set_tensor(input_details[0]['index'], input_data)
    interpreter.invoke()
    output = interpreter.get_tensor(output_details[0]['index'])
    return output


def analyze_yolo_output(output, conf_thresholds=[0.05, 0.1, 0.25, 0.5]):
    """Analyze YOLO output format [batch, 5, 8400]."""
    print("\n=== YOLO OUTPUT ANALYSIS ===")
    print(f"Output shape: {output.shape}")

    # YOLO format: [batch, 5, num_predictions]
    # Row 0-3: x, y, w, h (bounding box)
    # Row 4: confidence score

    if len(output.shape) == 3 and output.shape[1] == 5:
        confidences = output[0, 4, :]  # All confidence scores

        print(f"\nConfidence Statistics:")
        print(f"  Min confidence:  {confidences.min():.6f}")
        print(f"  Max confidence:  {confidences.max():.6f}")
        print(f"  Mean confidence: {confidences.mean():.6f}")
        print(f"  Std confidence:  {confidences.std():.6f}")

        print(f"\nTop 10 confidence scores:")
        top_indices = np.argsort(confidences)[-10:][::-1]
        for i, idx in enumerate(top_indices):
            conf = confidences[idx]
            x, y, w, h = output[0, 0, idx], output[0, 1, idx], output[0, 2, idx], output[0, 3, idx]
            print(f"  {i+1}. Index {idx}: conf={conf:.6f}, bbox=({x:.2f}, {y:.2f}, {w:.2f}, {h:.2f})")

        print(f"\nDetection counts at different thresholds:")
        for thresh in conf_thresholds:
            count = np.sum(confidences >= thresh)
            print(f"  Threshold {thresh}: {count} detections")

        best_conf = confidences.max()
        return best_conf > 0
    else:
        print(f"Unexpected output shape: {output.shape}")
        return False


def main():
    if len(sys.argv) < 2:
        print("Usage: python test_tflite_model.py <image_path>")
        print("\nExample:")
        print('  python test_tflite_model.py "C:\\path\\to\\image.jpg"')
        sys.exit(1)

    image_path = sys.argv[1]

    # Model path
    script_dir = os.path.dirname(os.path.abspath(__file__))
    model_path = os.path.join(script_dir, "veritypro-sdk", "src", "main", "assets", "best.tflite")

    print("=" * 50)
    print("TFLite YOLO Document Detection Model Tester")
    print("=" * 50)

    # Check paths
    if not os.path.exists(image_path):
        print(f"\n[ERROR] Image not found: {image_path}")
        sys.exit(1)

    if not os.path.exists(model_path):
        print(f"\n[ERROR] Model not found: {model_path}")
        sys.exit(1)

    print(f"\nImage: {image_path}")
    print(f"Model: {model_path}")

    # Load model
    interpreter, input_details, output_details = load_model(model_path)

    # Preprocess image
    input_shape = input_details[0]['shape']
    input_data = preprocess_image(image_path, input_shape)

    # Run inference
    print("\nRunning inference...")
    output = run_inference(interpreter, input_details, output_details, input_data)

    # Analyze output
    detected = analyze_yolo_output(output)

    print("\n" + "=" * 50)
    if detected:
        print("RESULT: Document MAY be detected (check confidence)")
    else:
        print("RESULT: No document detected (all confidences are 0)")
    print("=" * 50)


if __name__ == "__main__":
    main()
