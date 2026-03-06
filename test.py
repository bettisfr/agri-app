import os
import subprocess
import requests
from datetime import datetime

# Server endpoint on the SAME Raspberry Pi
SERVER_URL = "http://127.0.0.1:5000/receive"

# Ensure uploads folder exists
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
UPLOAD_DIR = os.path.join(BASE_DIR, "static", "uploads", "images")
os.makedirs(UPLOAD_DIR, exist_ok=True)

def take_picture():
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    file_path = os.path.join(UPLOAD_DIR, f"test_{timestamp}.jpg")

    # Capture with rpicam-still
    subprocess.run([
        "rpicam-still",
        "-n",
        "-o", file_path,
        "-t", "1"
    ], check=True)

    print(f"[OK] Picture taken: {file_path}")
    return file_path

def push_to_server(path):
    with open(path, "rb") as f:
        files = {"image": f}
        try:
            r = requests.post(SERVER_URL, files=files, timeout=5)
            r.raise_for_status()
            print("[OK] Image pushed to server")
        except Exception as e:
            print("[ERR] Failed to push image:", e)

if __name__ == "__main__":
    img_path = take_picture()
    push_to_server(img_path)
