# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "numpy==2.5.4",
#     "pillow==12.3.0",
#     "scipy==1.18.1",
#     "soxr==1.1.0",
#     "transformers==5.21.0",
# ]
# ///
"""
Writes the golden data of the engine's media tests (engine/src/test/resources/golden/media) from
the references that engine/.../media ports:

    resize       Pillow's BICUBIC resampling of 8-bit RGB images, one axis at a time: the
                 horizontal pass, then the vertical pass, the order of torchvision's antialiased
                 uint8 resize (the processors'). A single Image.resize call picks the order by
                 rules that change between releases: Pillow 12.3 runs the vertical pass first for
                 images over 100 times taller than wide that shrink vertically, Pillow 13 whenever
                 the height shrinks by more than twice as many pixels as the width.
    vision       Lfm3VlImageProcessor.patchify and Lfm3VlVideoProcessor.patchify (omni-src) with
                 the fused rescale + normalize, ported from torch to numpy, then reshaped per
                 temporal group as liquidonnx lfm3_vl.session.run_vision feeds the vision encoder.
    mel          liquidonnx lfm3_audio.session.log_mel with transformers' mel_filter_bank.
    istft        liquidonnx lfm3_audio.session.stft_to_wave (lfm2_audio.infer.istft_same_padding).
    resample     scipy.signal.resample_poly of band-limited signals, and soxr's HQ preset
                 (librosa's default resampler) of full-band speech.

Binary files are raw little-endian: *.rgb holds uint8 RGB pixels row by row, *.f32 float32 values
in row-major order. manifest.json lists every case with its shapes and parameters.

Usage:
    uv run tools/golden/generate-media-golden.py \\
        --speech-wav /path/to/liquidonnx/samples/audio/woodworks_question.wav \\
        --full-band-wav /path/to/liquidonnx/samples/audio/fool_me_once_mono.wav
"""

import argparse
import json
import logging
import math
import pathlib
import shutil

import numpy as np
import numpy.typing as npt
import PIL
import scipy
import scipy.io.wavfile
import scipy.signal
import soxr
import transformers
from PIL import Image
from transformers.audio_utils import mel_filter_bank

logger = logging.getLogger(__name__)

REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT_DIR = REPOSITORY_ROOT / "engine/src/test/resources/golden/media"
MAX_TOTAL_BYTES = 3 * 1024 * 1024

# Vision (processor_config.json image_processor / video_processor)
PATCH_SIZE = 16
MERGE_SIZE = 2
TEMPORAL_PATCH_SIZE = 2
RESCALE_FACTOR = 0.00392156862745098
IMAGE_MEAN = 0.5
IMAGE_STD = 0.5

# Log-mel front end (processor_config.json feature_extractor, liquidonnx MelConfig)
SAMPLING_RATE = 16000
NUM_MEL_BINS = 128
N_FFT = 400
WIN_LENGTH = 400
HOP_LENGTH = 160
LOG_FLOOR = -12.0
NORMALIZATION_OFFSET = 4.0
NORMALIZATION_DIVISOR = 4.0

# Codec inverse STFT (audio_tokenizer/config.json)
CODEC_N_FFT = 1280
CODEC_HOP_LENGTH = 320

# resample_poly is within 0.02 dB of flat up to 0.84 of the lower Nyquist frequency, but 0.66 dB
# down at 0.9 and lets aliases through near it: the signals compared with it stay below 0.8 of it
RESAMPLE_POLY_BAND = 0.8

# The length of the full-band speech excerpts: whole numbers of samples at 11025, 16000 and 44100 Hz
FULL_BAND_SECONDS = 0.8

# The names of the resampling references in manifest.json
RESAMPLE_POLY = "resample_poly"
SOXR_HQ = "soxr_hq"


class GoldenWriter:
    """Writes the binary files under the output folder and remembers their total size."""

    def __init__(self, output_dir: pathlib.Path):
        self.output_dir = output_dir
        self.total_bytes = 0

    def write(self, relative_path: str, array: np.ndarray, dtype: npt.DTypeLike) -> str:
        path = self.output_dir / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        data = np.ascontiguousarray(array, dtype=dtype)
        data.tofile(path)
        self.total_bytes += data.nbytes
        return relative_path

    def rgb(self, relative_path: str, pixels: np.ndarray) -> str:
        return self.write(relative_path, pixels, np.uint8)

    def f32(self, relative_path: str, values: np.ndarray) -> str:
        return self.write(relative_path, values, "<f4")


def synthetic_image(rng: np.random.Generator, width: int, height: int) -> np.ndarray:
    """[height, width, 3] uint8: gradients and noise, with a white band and a black line whose
    hard edges make the cubic overshoot past 0 and 255 (the clamping)."""
    y, x = np.mgrid[0:height, 0:width]
    red = 255.0 * x / max(width - 1, 1)
    green = 255.0 * y / max(height - 1, 1)
    blue = 127.5 + 127.5 * np.sin(0.7 * x + 0.3 * y)
    image = np.stack([red, green, blue], axis=-1)
    image += rng.normal(0.0, 40.0, image.shape)
    image[height // 3 : height // 2 + 1, :, :] = 255.0
    image[:, width // 4 : width // 4 + 2, :] = 0.0
    rounded = np.rint(image)
    clipped = np.clip(rounded, 0, 255)
    return clipped.astype(np.uint8)


def pillow_bicubic(pixels: np.ndarray, width: int, height: int) -> np.ndarray:
    """Pillow's BICUBIC resize of [height, width, 3] uint8 pixels, horizontally and then vertically:
    each Image.resize call changes one side only, so Pillow has no pass order to choose."""
    image = Image.fromarray(pixels)
    if image.mode != "RGB":
        raise ValueError(f"expected an RGB image, got {image.mode}")
    resized_rows = image.resize((width, image.height), Image.Resampling.BICUBIC)
    resized = resized_rows.resize((width, height), Image.Resampling.BICUBIC)
    return np.asarray(resized)


# === Resize ===


def resize_case(writer: GoldenWriter, source: np.ndarray, source_file: str, width: int, height: int) -> dict:
    source_height, source_width = source.shape[:2]
    name = f"{source_width}x{source_height}-to-{width}x{height}"
    expected = pillow_bicubic(source, width, height)
    expected_file = writer.rgb(f"resize/{name}.rgb", expected)
    return {
        "name": name,
        "source": source_file,
        "sourceWidth": source_width,
        "sourceHeight": source_height,
        "width": width,
        "height": height,
        "expected": expected_file,
    }


def resize_cases(writer: GoldenWriter, rng: np.random.Generator) -> list[dict]:
    sources = {
        (97, 61): synthetic_image(rng, 97, 61),
        (257, 131): synthetic_image(rng, 257, 131),
        (31, 17): synthetic_image(rng, 31, 17),
        (3, 2): synthetic_image(rng, 3, 2),
        (2, 2): synthetic_image(rng, 2, 2),
    }
    targets = [
        ((97, 61), (64, 32)),  # down by 1.5 and 1.9
        ((97, 61), (160, 96)),  # up
        ((97, 61), (97, 30)),  # vertical pass only
        ((97, 61), (45, 61)),  # horizontal pass only
        ((97, 61), (96, 64)),  # slightly narrower, slightly taller
        ((257, 131), (64, 32)),  # down by 4
        ((257, 131), (33, 17)),  # down by 7.8 and 7.7
        ((31, 17), (96, 64)),  # up by 3.1 and 3.8
        ((31, 17), (32, 16)),  # almost the same size (one Pillow 13 Image.resize call goes vertical first)
        ((3, 2), (7, 5)),  # tiny
        ((2, 2), (1, 1)),  # a single output pixel
    ]
    source_files = {}
    for (width, height), pixels in sources.items():
        source_files[(width, height)] = writer.rgb(f"resize/source-{width}x{height}.rgb", pixels)

    cases = []
    for source_size, (width, height) in targets:
        case = resize_case(writer, sources[source_size], source_files[source_size], width, height)
        cases.append(case)
    return cases


def tall_resize_cases(writer: GoldenWriter, rng: np.random.Generator) -> list[dict]:
    """A source over 100 times taller than wide, shrunk vertically: Pillow 12.3's Image.resize
    would run its vertical pass first here, which torchvision never does."""
    width, height = 5, 600
    pixels = synthetic_image(rng, width, height)
    source_file = writer.rgb(f"resize/source-{width}x{height}.rgb", pixels)

    cases = []
    for target_width, target_height in [(7, 30), (3, 50)]:
        case = resize_case(writer, pixels, source_file, target_width, target_height)
        cases.append(case)
    return cases


# === Vision patches ===


def rescale_and_normalize(pixels: np.ndarray) -> np.ndarray:
    """TorchvisionBackend.rescale_and_normalize with rescale and normalize fused: the mean and std
    are float32 tensors scaled by 1 / rescale_factor, then (x - mean) / std in float32."""
    mean = np.float32(np.float32(IMAGE_MEAN) * (1.0 / RESCALE_FACTOR))
    std = np.float32(np.float32(IMAGE_STD) * (1.0 / RESCALE_FACTOR))
    return (pixels.astype(np.float32) - mean) / std


def patchify_images(images: np.ndarray) -> tuple[np.ndarray, int, int]:
    """Lfm3VlImageProcessor.patchify: [batch, channel, height, width] -> [batch, patches, 1536]."""
    batch_size, channel, resized_height, resized_width = images.shape
    grid_h, grid_w = resized_height // PATCH_SIZE, resized_width // PATCH_SIZE
    patches = images.reshape(
        batch_size,
        channel,
        grid_h // MERGE_SIZE,
        MERGE_SIZE,
        PATCH_SIZE,
        grid_w // MERGE_SIZE,
        MERGE_SIZE,
        PATCH_SIZE,
    )
    # [batch, grid_h/merge, grid_w/merge, merge, merge, channel, patch, patch]
    patches = patches.transpose(0, 2, 5, 3, 6, 1, 4, 7)
    # unsqueeze(6).expand(..., temporal_patch_size, ...): the still image fills both temporal slots
    expanded_shape = patches.shape[:6] + (TEMPORAL_PATCH_SIZE,) + patches.shape[6:]
    expanded = np.broadcast_to(patches[:, :, :, :, :, :, None], expanded_shape)
    flatten_patches = expanded.reshape(
        batch_size,
        grid_h * grid_w,
        channel * TEMPORAL_PATCH_SIZE * PATCH_SIZE * PATCH_SIZE,
    )
    return flatten_patches, grid_h, grid_w


def patchify_videos(videos: np.ndarray) -> tuple[np.ndarray, int, int, int]:
    """Lfm3VlVideoProcessor.patchify: [batch, frames, channel, height, width] -> [batch,
    temporal groups * patches, 1536]; an odd frame count repeats the last frame."""
    batch_size, num_frames, channel, resized_height, resized_width = videos.shape
    if pad := -num_frames % TEMPORAL_PATCH_SIZE:
        repeats = np.repeat(videos[:, -1:], pad, axis=1)
        videos = np.concatenate((videos, repeats), axis=1)
        num_frames += pad

    grid_t = num_frames // TEMPORAL_PATCH_SIZE
    grid_h, grid_w = resized_height // PATCH_SIZE, resized_width // PATCH_SIZE
    patches = videos.reshape(
        batch_size,
        grid_t,
        TEMPORAL_PATCH_SIZE,
        channel,
        grid_h // MERGE_SIZE,
        MERGE_SIZE,
        PATCH_SIZE,
        grid_w // MERGE_SIZE,
        MERGE_SIZE,
        PATCH_SIZE,
    )
    patches = patches.transpose(0, 1, 4, 7, 5, 8, 3, 2, 6, 9)
    flatten_patches = patches.reshape(
        batch_size,
        grid_t * grid_h * grid_w,
        channel * TEMPORAL_PATCH_SIZE * PATCH_SIZE * PATCH_SIZE,
    )
    return flatten_patches, grid_t, grid_h, grid_w


def encoder_inputs(pixel_values: np.ndarray, grid_t: int, grid_h: int, grid_w: int) -> list[np.ndarray]:
    """liquidonnx run_vision: one [grid_h/2, grid_w/2, 2, 2, 1536] block per temporal group."""
    patch_dim = pixel_values.shape[-1]
    patches_per_group = grid_h * grid_w
    groups = []
    for group in range(grid_t):
        rows = pixel_values[group * patches_per_group : (group + 1) * patches_per_group]
        blocks = rows.reshape(grid_h // MERGE_SIZE, grid_w // MERGE_SIZE, MERGE_SIZE, MERGE_SIZE, patch_dim)
        groups.append(blocks)
    return groups


def image_patches(pixels: np.ndarray) -> tuple[np.ndarray, int, int]:
    """The encoder input of one already resized [height, width, 3] image."""
    channels_first = pixels.transpose(2, 0, 1)[None]
    normalized = rescale_and_normalize(channels_first)
    flatten_patches, grid_h, grid_w = patchify_images(normalized)
    [blocks] = encoder_inputs(flatten_patches[0], 1, grid_h, grid_w)
    return blocks, grid_h, grid_w


def video_patches(frames: list[np.ndarray]) -> tuple[list[np.ndarray], int, int]:
    """The encoder inputs, one per temporal group, of already resized [height, width, 3] frames."""
    channels_first_frames = [frame.transpose(2, 0, 1) for frame in frames]
    clip = np.stack(channels_first_frames)[None]
    normalized = rescale_and_normalize(clip)
    flatten_patches, grid_t, grid_h, grid_w = patchify_videos(normalized)
    groups = encoder_inputs(flatten_patches[0], grid_t, grid_h, grid_w)
    return groups, grid_h, grid_w


def smart_resize(
    height: int, width: int, factor: int = 28, min_pixels: int = 56 * 56, max_pixels: int = 14 * 14 * 4 * 1280
):
    """image_processing_lfm3_vl.smart_resize: (height, width) multiples of factor within the pixel bounds."""
    if max(height, width) / min(height, width) > 200:
        raise ValueError(
            f"absolute aspect ratio must be smaller than 200, got {max(height, width) / min(height, width)}"
        )
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


def image_patch_cases(writer: GoldenWriter, rng: np.random.Generator) -> list[dict]:
    cases = []

    # Already at a resize target: the patch layout alone
    pixels = synthetic_image(rng, 96, 64)
    blocks, grid_h, grid_w = image_patches(pixels)
    source_file = writer.rgb("vision/image-96x64.rgb", pixels)
    expected_file = writer.f32("vision/patchify-96x64.f32", blocks)
    case = {
        "name": "patchify-96x64",
        "source": source_file,
        "sourceWidth": 96,
        "sourceHeight": 64,
        "maxTokens": None,
        "rows": grid_h,
        "columns": grid_w,
        "expected": expected_file,
    }
    cases.append(case)

    # The whole path with a budget of 4 tokens (Lfm3VlImageProcessor size.longest_edge = tokens * 32²)
    pixels = synthetic_image(rng, 100, 60)
    factor = PATCH_SIZE * MERGE_SIZE
    target_height, target_width = smart_resize(60, 100, factor, min_pixels=65_536, max_pixels=4 * factor * factor)
    resized = pillow_bicubic(pixels, target_width, target_height)
    blocks, grid_h, grid_w = image_patches(resized)
    source_file = writer.rgb("vision/image-100x60.rgb", pixels)
    expected_file = writer.f32("vision/image-100x60-max-4-tokens.f32", blocks)
    case = {
        "name": "image-100x60-max-4-tokens",
        "source": source_file,
        "sourceWidth": 100,
        "sourceHeight": 60,
        "maxTokens": 4,
        "rows": grid_h,
        "columns": grid_w,
        "expected": expected_file,
    }
    cases.append(case)
    return cases


def video_patch_cases(writer: GoldenWriter, rng: np.random.Generator) -> list[dict]:
    cases = []
    specs = [
        # name, frame count, frame size, target size (None: the frames are at the target already)
        ("patchify-3-frames-64x32", 3, (64, 32), None),
        ("video-3-frames-50x40-to-64x32", 3, (50, 40), (64, 32)),
        ("video-4-frames-40x30-to-32x64", 4, (40, 30), (32, 64)),
    ]
    for name, frame_count, (width, height), target in specs:
        frames = [synthetic_image(rng, width, height) for _ in range(frame_count)]
        if target:
            target_width, target_height = target
            resized = [pillow_bicubic(frame, target_width, target_height) for frame in frames]
        else:
            target_width, target_height = width, height
            resized = frames
        groups, grid_h, grid_w = video_patches(resized)

        frame_files = []
        for index, frame in enumerate(frames):
            frame_file = writer.rgb(f"vision/{name}-frame-{index}.rgb", frame)
            frame_files.append(frame_file)
        expected_files = []
        for index, group in enumerate(groups):
            expected_file = writer.f32(f"vision/{name}-group-{index}.f32", group)
            expected_files.append(expected_file)
        case = {
            "name": name,
            "frames": frame_files,
            "frameWidth": width,
            "frameHeight": height,
            "targetWidth": target_width,
            "targetHeight": target_height,
            "rows": grid_h,
            "columns": grid_w,
            "expected": expected_files,
        }
        cases.append(case)
    return cases


# === Log-mel spectrogram ===


def mel_filters() -> np.ndarray:
    """liquidonnx lfm3_audio.session.mel_filters: [n_fft // 2 + 1, num_mel_bins] float32."""
    filters = mel_filter_bank(
        num_frequency_bins=N_FFT // 2 + 1,
        num_mel_filters=NUM_MEL_BINS,
        min_frequency=0.0,
        max_frequency=SAMPLING_RATE / 2,
        sampling_rate=SAMPLING_RATE,
        norm="slaney",
        mel_scale="slaney",
    )
    return filters.astype(np.float32)


def num_mel_frames(num_samples: int) -> int:
    """liquidonnx MelConfig.num_frames."""
    padded = max(N_FFT, num_samples + (-(num_samples - N_FFT) % HOP_LENGTH))
    return 1 + (padded - N_FFT) // HOP_LENGTH


def log_mel(wave: np.ndarray, filters: np.ndarray) -> np.ndarray:
    """liquidonnx lfm3_audio.session.log_mel: input_features [num_mel_bins, T] float32."""
    wave = np.asarray(wave, dtype=np.float32)
    frames = num_mel_frames(len(wave))
    padded = np.zeros((frames - 1) * HOP_LENGTH + N_FFT, dtype=np.float64)
    padded[: len(wave)] = wave
    # torch.hann_window (periodic, float32), centered in n_fft as torch.stft pads it
    window = np.zeros(N_FFT)
    offset = (N_FFT - WIN_LENGTH) // 2
    hann = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(WIN_LENGTH) / WIN_LENGTH)
    window[offset : offset + WIN_LENGTH] = hann.astype(np.float32)
    # [T, n_fft] -> [T, n_fft // 2 + 1], in float64
    all_segments = np.lib.stride_tricks.sliding_window_view(padded, N_FFT)
    segments = all_segments[::HOP_LENGTH]
    spectrum = np.fft.rfft(segments * window, axis=-1)
    power = np.abs(spectrum) ** 2
    filters_double = filters.T.astype(np.float64)
    mel_energy = filters_double @ power.T
    with np.errstate(divide="ignore"):
        features = np.log10(mel_energy)
    features = np.maximum(features, LOG_FLOOR)
    normalized = (features + NORMALIZATION_OFFSET) / NORMALIZATION_DIVISOR
    return normalized.astype(np.float32)


def read_wav(path: pathlib.Path) -> tuple[int, np.ndarray]:
    """The sample rate and mono float32 samples of a WAV file, read as liquidonnx's load_audio reads them."""
    rate, samples = scipy.io.wavfile.read(str(path))
    if samples.dtype.kind == "i":
        samples = samples / -np.iinfo(samples.dtype).min
    elif samples.dtype.kind != "f":
        raise ValueError(f"{path}: unsupported WAV sample type {samples.dtype}")
    samples = samples.astype(np.float32)
    if samples.ndim == 2:
        samples = samples.mean(axis=1)
    return rate, samples


def load_wav(path: pathlib.Path, sampling_rate: int) -> np.ndarray:
    """liquidonnx lfm3_audio.session.load_audio: mono float32 at sampling_rate."""
    rate, samples = read_wav(path)
    if rate != sampling_rate:
        gcd = math.gcd(rate, sampling_rate)
        samples = scipy.signal.resample_poly(samples, sampling_rate // gcd, rate // gcd)
    return samples.astype(np.float32)


def mel_cases(writer: GoldenWriter, rng: np.random.Generator, speech_wav: pathlib.Path) -> tuple[dict, list[dict]]:
    filters = mel_filters()
    filters_file = writer.f32("mel/filter-bank.f32", filters)
    filter_bank = {
        "frequencyBins": filters.shape[0],
        "melBins": filters.shape[1],
        "weights": filters_file,
    }

    speech = load_wav(speech_wav, SAMPLING_RATE)[:48_123]
    duration = 7_777
    time = np.arange(duration) / SAMPLING_RATE
    chirp = 0.5 * scipy.signal.chirp(time, f0=50.0, t1=time[-1], f1=7_900.0, method="logarithmic")
    chirp += rng.normal(0.0, 0.01, duration)
    clips = {
        "speech": speech,
        "chirp": chirp.astype(np.float32),
        "noise-123": rng.normal(0.0, 0.3, 123).astype(np.float32),
        "noise-400": rng.normal(0.0, 0.3, 400).astype(np.float32),
        "noise-561": rng.normal(0.0, 0.3, 561).astype(np.float32),
    }

    cases = []
    for name, samples in clips.items():
        features = log_mel(samples, filters)
        samples_file = writer.f32(f"mel/{name}.f32", samples)
        expected_file = writer.f32(f"mel/{name}-log-mel.f32", features)
        case = {
            "name": name,
            "samples": samples_file,
            "sampleCount": len(samples),
            "frames": features.shape[1],
            "expected": expected_file,
        }
        cases.append(case)
    return filter_bank, cases


# === Inverse STFT ===


def istft_same_padding(spectrum: np.ndarray, n_fft: int, hop_length: int) -> np.ndarray:
    """liquidonnx lfm2_audio.infer.istft_same_padding: [freq, frames] complex -> frames * hop."""
    window = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(n_fft) / n_fft)  # torch.hann_window
    frames = np.fft.irfft(spectrum, n_fft, axis=0) * window[:, None]
    size = (spectrum.shape[1] - 1) * hop_length + n_fft
    wave = np.zeros(size)
    envelope = np.zeros(size)
    for t in range(spectrum.shape[1]):
        wave[t * hop_length : t * hop_length + n_fft] += frames[:, t]
        envelope[t * hop_length : t * hop_length + n_fft] += window**2
    pad = (n_fft - hop_length) // 2
    return wave[pad:-pad] / envelope[pad:-pad]


def stft_to_wave(stft_features: np.ndarray, n_fft: int, hop_length: int) -> np.ndarray:
    """liquidonnx lfm3_audio.session.stft_to_wave: [rows, n_fft + 2] -> [rows * hop] float32."""
    bins = n_fft // 2 + 1
    spectrum = np.exp(stft_features[:, :bins]) * np.exp(1j * stft_features[:, bins:])
    wave = istft_same_padding(spectrum.T, n_fft, hop_length)
    return wave.astype(np.float32)


def inverse_stft_cases(writer: GoldenWriter, rng: np.random.Generator) -> list[dict]:
    bins = CODEC_N_FFT // 2 + 1
    cases = []
    for row_count in (1, 2, 23):
        random_log_magnitude = rng.normal(-4.0, 2.0, (row_count, bins))
        log_magnitude = np.clip(random_log_magnitude, -12.0, 3.0)
        phase = rng.uniform(-np.pi, np.pi, (row_count, bins))
        rows = np.concatenate([log_magnitude, phase], axis=1).astype(np.float32)
        wave = stft_to_wave(rows, CODEC_N_FFT, CODEC_HOP_LENGTH)
        name = f"random-{row_count}-rows"
        rows_file = writer.f32(f"istft/{name}.f32", rows)
        expected_file = writer.f32(f"istft/{name}-wave.f32", wave)
        case = {
            "name": name,
            "rows": rows_file,
            "rowCount": row_count,
            "nFft": CODEC_N_FFT,
            "hop": CODEC_HOP_LENGTH,
            "expected": expected_file,
        }
        cases.append(case)
    return cases


# === Resampling ===


def fade_edges(signal: np.ndarray, rate: int):
    """Fades signal in and out over 10 ms (a raised cosine), in place: its ends make no click."""
    fade_length = rate // 100
    fade = 0.5 - 0.5 * np.cos(np.pi * np.arange(fade_length) / fade_length)
    signal[:fade_length] *= fade
    signal[-fade_length:] *= fade[::-1]


def band_limited_signal(rng: np.random.Generator, rate: int, count: int, highest_frequency: float) -> np.ndarray:
    """Five tones up to highest_frequency, faded in and out."""
    time = np.arange(count) / rate
    frequencies = rng.uniform(50.0, highest_frequency, 5)
    phases = rng.uniform(0.0, 2 * np.pi, 5)
    signal = np.zeros(count)
    for frequency, phase in zip(frequencies, phases, strict=True):
        signal += 0.15 * np.sin(2 * np.pi * frequency * time + phase)
    fade_edges(signal, rate)
    return signal.astype(np.float32)


def resample_case(
    writer: GoldenWriter,
    name: str,
    reference: str,
    signal: np.ndarray,
    from_rate: int,
    to_rate: int,
    expected: np.ndarray,
) -> dict:
    input_file = writer.f32(f"resample/{name}-input.f32", signal)
    expected_file = writer.f32(f"resample/{name}-expected.f32", expected)
    return {
        "name": name,
        "reference": reference,
        "input": input_file,
        "fromRate": from_rate,
        "toRate": to_rate,
        "inputLength": len(signal),
        "expected": expected_file,
        "expectedLength": len(expected),
    }


def resample_cases(writer: GoldenWriter, rng: np.random.Generator) -> list[dict]:
    """scipy.signal.resample_poly of tones up to RESAMPLE_POLY_BAND of the lower Nyquist frequency."""
    rate_pairs = [
        (44_100, 16_000),
        (48_000, 16_000),
        (22_050, 16_000),
        (11_025, 16_000),
        (8_000, 16_000),
        (96_000, 16_000),
        (32_000, 16_000),
        (24_000, 16_000),
        (16_000, 24_000),
        (24_000, 48_000),
        (44_100, 48_000),
    ]
    cases = []
    for from_rate, to_rate in rate_pairs:
        count = from_rate // 10 + 7
        highest_frequency = RESAMPLE_POLY_BAND * min(from_rate, to_rate) / 2
        signal = band_limited_signal(rng, from_rate, count, highest_frequency)
        gcd = math.gcd(from_rate, to_rate)
        signal_double = signal.astype(np.float64)
        expected = scipy.signal.resample_poly(signal_double, to_rate // gcd, from_rate // gcd)
        name = f"{from_rate}-to-{to_rate}"
        case = resample_case(writer, name, RESAMPLE_POLY, signal, from_rate, to_rate, expected)
        cases.append(case)
    return cases


def full_band_resample_cases(writer: GoldenWriter, speech_wav: pathlib.Path, full_band_wav: pathlib.Path) -> list[dict]:
    """soxr's HQ preset of speech with content up to the Nyquist frequency: a recording at 32 kHz
    or more taken down to 16 kHz, and 16 kHz speech taken down to 11025 Hz (soxr's VHQ preset)
    and back up. resample_poly would not do as a reference: it rolls off from 0.84 of the Nyquist
    frequency and lets aliases through near it."""
    recording_rate, recording = read_wav(full_band_wav)
    if recording_rate < 32_000:
        raise SystemExit(f"{full_band_wav} is sampled at {recording_rate} Hz: the full-band case needs 32 kHz or more")
    speech = load_wav(speech_wav, SAMPLING_RATE)
    speech_11025 = soxr.resample(speech, SAMPLING_RATE, 11_025, quality="VHQ")
    clips = [
        # name, samples, sample rate, where the excerpt starts in seconds (speech with sibilants)
        (f"speech-{recording_rate}-to-{SAMPLING_RATE}", recording, recording_rate, 9.5),
        (f"speech-11025-to-{SAMPLING_RATE}", speech_11025, 11_025, 1.0),
    ]

    cases = []
    for name, samples, rate, start_seconds in clips:
        start = round(start_seconds * rate)
        count = round(FULL_BAND_SECONDS * rate)
        excerpt = samples[start : start + count].astype(np.float64)
        if len(excerpt) != count:
            raise SystemExit(f"{name}: the clip ends before {start_seconds + FULL_BAND_SECONDS} s")
        fade_edges(excerpt, rate)
        signal = excerpt.astype(np.float32)

        signal_double = signal.astype(np.float64)
        expected = soxr.resample(signal_double, rate, SAMPLING_RATE, quality="HQ")
        # soxr's length differs from resample_poly's ceil(n * to / from) when n * to / from is fractional
        expected_length = -(-count * SAMPLING_RATE // rate)
        if len(expected) != expected_length:
            raise SystemExit(f"{name}: soxr returned {len(expected)} samples, not {expected_length}")
        case = resample_case(writer, name, SOXR_HQ, signal, rate, SAMPLING_RATE, expected)
        cases.append(case)
    return cases


def clear_output_dir(output_dir: pathlib.Path):
    """Removes the golden data of a previous run, so that no stale file lingers; refuses to touch a
    non-empty folder without a manifest.json, which is not golden data."""
    if not output_dir.exists():
        return
    if any(output_dir.iterdir()) and not (output_dir / "manifest.json").is_file():
        raise SystemExit(f"{output_dir} is not empty and has no manifest.json: refusing to replace it")
    shutil.rmtree(output_dir)


def main():
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--speech-wav", type=pathlib.Path, required=True, help="a 16 kHz (or any rate) speech WAV")
    parser.add_argument("--full-band-wav", type=pathlib.Path, required=True, help="a speech WAV at 32 kHz or more")
    parser.add_argument("--output-dir", type=pathlib.Path, default=DEFAULT_OUTPUT_DIR)
    args = parser.parse_args()

    clear_output_dir(args.output_dir)
    writer = GoldenWriter(args.output_dir)
    rng = np.random.default_rng(20_261_029)

    # The sections draw from one generator in this order: keep it to reproduce the data, and put
    # new cases that draw from it after the last section
    filter_bank, log_mel_entries = mel_cases(writer, rng, args.speech_wav)
    resize_entries = resize_cases(writer, rng)
    image_patch_entries = image_patch_cases(writer, rng)
    video_patch_entries = video_patch_cases(writer, rng)
    inverse_stft_entries = inverse_stft_cases(writer, rng)
    resample_entries = resample_cases(writer, rng)
    tall_resize_entries = tall_resize_cases(writer, rng)
    full_band_resample_entries = full_band_resample_cases(writer, args.speech_wav, args.full_band_wav)

    versions = {
        "numpy": np.__version__,
        "pillow": PIL.__version__,
        "scipy": scipy.__version__,
        "soxr": soxr.__version__,
        "transformers": transformers.__version__,
    }
    manifest = {
        "generator": "tools/golden/generate-media-golden.py",
        "versions": versions,
        "resize": resize_entries + tall_resize_entries,
        "imagePatches": image_patch_entries,
        "videoPatches": video_patch_entries,
        "melFilterBank": filter_bank,
        "logMel": log_mel_entries,
        "inverseStft": inverse_stft_entries,
        "resample": resample_entries + full_band_resample_entries,
    }
    manifest_path = args.output_dir / "manifest.json"
    manifest_text = json.dumps(manifest, indent=2) + "\n"
    manifest_path.write_text(manifest_text)

    total_bytes = writer.total_bytes + manifest_path.stat().st_size
    logger.info(f"Wrote {manifest_path.parent} ({total_bytes / 1024:.0f} KiB)")
    if total_bytes > MAX_TOTAL_BYTES:
        raise SystemExit(f"golden data is {total_bytes} bytes, over the {MAX_TOTAL_BYTES} byte budget")


if __name__ == "__main__":
    main()
