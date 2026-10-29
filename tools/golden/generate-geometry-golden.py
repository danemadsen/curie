# /// script
# requires-python = ">=3.11"
# dependencies = ["numpy>=2.2"]
# ///
"""
Golden cases for engine/media/MediaGeometry.kt, computed with the reference processors' own code:
smart_resize of image_processing_lfm3_vl.py and video_processing_lfm3_vl.py, the frame sampler of
Lfm3VlVideoProcessor.sample_frames, the timestamps and soundtrack split of Lfm3OmniProcessor, and the
log-mel frame count of Lfm3OmniFeatureExtractor (all copied verbatim below).

Usage:
    uv run tools/golden/generate-geometry-golden.py engine/src/test/resources/golden/geometry.json
"""

import json
import math
import random
import sys

import numpy as np

TOKEN_PIXELS = 32
IMAGE_MIN_PIXELS = 65_536
VIDEO_MIN_PIXELS = 4_096


def image_smart_resize(height, width, factor, min_pixels, max_pixels):
    if max(height, width) / min(height, width) > 200:
        raise ValueError("aspect ratio")
    h_bar = round(height / factor) * factor
    w_bar = round(width / factor) * factor
    if h_bar * w_bar > max_pixels:
        beta = math.sqrt((height * width) / max_pixels)
        h_bar = max(factor, math.floor(height / beta / factor) * factor)
        w_bar = max(factor, math.floor(width / beta / factor) * factor)
    elif h_bar * w_bar < min_pixels:
        beta = math.sqrt(min_pixels / (height * width))
        h_bar = math.ceil(height * beta / factor) * factor
        w_bar = math.ceil(width * beta / factor) * factor
    return h_bar, w_bar


def video_smart_resize(num_frames, height, width, temporal_factor, factor, min_pixels, max_pixels):
    if num_frames < temporal_factor:
        raise ValueError("frames")
    if height < factor or width < factor:
        scale = max(factor / height, factor / width)
        height = int(height * scale)
        width = int(width * scale)
    if max(height, width) / min(height, width) > 200:
        raise ValueError("aspect ratio")
    h_bar = round(height / factor) * factor
    w_bar = round(width / factor) * factor
    t_bar = round(num_frames / temporal_factor) * temporal_factor
    if t_bar * h_bar * w_bar > max_pixels:
        beta = math.sqrt((num_frames * height * width) / max_pixels)
        h_bar = max(factor, math.floor(height / beta / factor) * factor)
        w_bar = max(factor, math.floor(width / beta / factor) * factor)
    elif t_bar * h_bar * w_bar < min_pixels:
        beta = math.sqrt(min_pixels / (num_frames * height * width))
        h_bar = math.ceil(height * beta / factor) * factor
        w_bar = math.ceil(width * beta / factor) * factor
    return h_bar, w_bar


def sample_frames(total_num_frames, video_fps, max_frames, fps=2.0, min_frames=4):
    num_frames = int(total_num_frames / video_fps * fps)
    num_frames = min(max(num_frames, min_frames), max_frames, total_num_frames)
    return np.linspace(0, total_num_frames - 1, num_frames).round().astype(int).tolist()


def calculate_timestamps(indices, video_fps, temporal_patch_size=2):
    indices = list(indices)
    if pad := -len(indices) % temporal_patch_size:
        indices.extend([indices[-1]] * pad)
    return [
        (indices[i] + indices[i + temporal_patch_size - 1]) / (2 * video_fps)
        for i in range(0, len(indices), temporal_patch_size)
    ]


def soundtrack_bounds(frames_indices, fps, soundtrack_tokens, groups, temporal=2):
    starts = [index / fps for index in list(frames_indices)[::temporal]][:groups]
    seconds_per_token = 160 * 8 / 16000
    bounds = (
        [0]
        + [min(soundtrack_tokens, round(start / seconds_per_token)) for start in starts[1:]]
        + [soundtrack_tokens]
    )
    return [max(bounds[: i + 1]) for i in range(len(bounds))]


def mel_frames(num_samples, n_fft=400, hop=160):
    padded = n_fft if num_samples < n_fft else num_samples + (-(num_samples - n_fft) % hop)
    return 1 + (padded - n_fft) // hop


def main():
    rng = random.Random(1234)
    sizes = [(32, 32), (1, 1), (17, 3000), (3000, 17), (640, 480), (480, 640), (1920, 1080), (4032, 3024),
             (512, 512), (800, 600), (720, 1280), (100, 100), (48, 9000), (2000, 15)]
    sizes += [(rng.randint(10, 5000), rng.randint(10, 5000)) for _ in range(150)]

    images = []
    for width, height in sizes:
        for max_tokens in (64, 256, 2048):
            if max(width, height) / min(width, height) > 200:
                continue
            h, w = image_smart_resize(height, width, TOKEN_PIXELS, IMAGE_MIN_PIXELS, max_tokens * TOKEN_PIXELS ** 2)
            images.append({"width": width, "height": height, "maxTokens": max_tokens, "outWidth": w, "outHeight": h})

    videos = []
    for _ in range(200):
        width = rng.randint(16, 3840)
        height = rng.randint(16, 2160)
        frame_count = rng.randint(1, 20000)
        fps = rng.choice([23.976, 24.0, 25.0, 29.97, 30.0, 30.000000000000004, 59.94, 60.0, 12.5, 1.0, 120.0])
        max_frames = rng.choice([32, 384])
        max_tokens = rng.choice([256, 1024])
        if max(width, height) / min(width, height) > 200:
            continue
        indices = sample_frames(frame_count, fps, max_frames)
        rounded_fps = round(fps, 6)
        timestamps = calculate_timestamps(indices, rounded_fps)
        case = {"width": width, "height": height, "frameCount": frame_count, "fps": fps, "maxFrames": max_frames,
                "maxTokens": max_tokens, "indices": indices, "timestamps": timestamps,
                "timestampTexts": [f"<{t:.1f} seconds>" for t in timestamps]}
        if len(indices) >= 2:
            h, w = video_smart_resize(len(indices), height, width, 2, TOKEN_PIXELS, VIDEO_MIN_PIXELS,
                                      max_tokens * 2 * TOKEN_PIXELS ** 2)
            case["outWidth"] = w
            case["outHeight"] = h
        tokens = rng.randint(0, 3000)
        case["soundtrackTokens"] = tokens
        case["soundtrackBounds"] = soundtrack_bounds(indices, rounded_fps, tokens, len(timestamps))
        videos.append(case)

    # Exact .x5 ties: Python formats the binary value half-to-even
    ties = [0.25, 0.75, 1.25, 2.5, 0.05, 0.15, 0.35, 12.25, 0.125]
    texts = [{"seconds": t, "text": f"<{t:.1f} seconds>"} for t in ties]

    audio = [{"samples": n, "melFrames": mel_frames(n), "tokens": -(mel_frames(n) // -8)}
             for n in [0, 1, 159, 160, 399, 400, 401, 559, 560, 561, 16000, 16001, 48000, 160000, 123457]]

    out = {"images": images, "videos": videos, "timestampTexts": texts, "audio": audio}
    with open(sys.argv[1], "w") as handle:
        json.dump(out, handle, separators=(",", ":"))
    print(f"{len(images)} image, {len(videos)} video, {len(texts)} text, {len(audio)} audio cases")


if __name__ == "__main__":
    main()
