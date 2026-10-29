# /// script
# requires-python = ">=3.12"
# dependencies = [
#     "jinja2==3.1.6",
#     "numpy==2.5.4",
#     "onnx==1.23.2",
#     "onnxruntime==1.31.1",
#     "scipy==1.18.1",
#     "tokenizers==0.23.3",
#     "transformers==5.21.0",
# ]
# ///
"""
Writes the golden data of the engine's ONNX tests (engine/src/test/resources/golden/engine) with
Liquid AI's reference inference, liquidonnx's lfm3_audio.session.ONNXAudioModel.generate_ids and
lfm3_omni.session.ONNXOmniModel, on the original graphs of the q4 bundle (the base model: the
engine compares them with EngineOptions(useLora = false)).

    generations  prompt token ids (the chat template through transformers, media expanded as the
                 processors do) and the slots the reference generates: greedy text answers,
                 text to speech, interleaved text and speech, speech recognition, image questions.
                 A short text to speech and the interleaved answer run to the end of their turn,
                 through the end-of-speech frame that is fed back and the <|im_end|> after it.
                 Speech codes are greedy with the checkpoint's repetition penalty, and the MaskGIT
                 order is the identity (codebooks 0..11), so that no random draw is involved.
                 The prefill runs in the engine's chunks (PrefillChunks: split at the prompt's
                 checkpoints, the start of every message and the end): how a prompt is split
                 into decoder runs changes the float rounding, which can flip greedy speech codes
                 at near-ties, so the reference splits it the same way. Everything else is
                 generate_ids as it is.
    audio        the speech clip (a copy of the WAV), the SHA-256 of its log-mel features and some
                 rows of the audio encoder's features.
    vision       a procedural test image (both sides draw it from the same integer formula), the
                 SHA-256 of its pixel_values and some rows of the vision encoder's features.
    codec        the 24 kHz waveform of the text to speech frames (decode_audio), and the codes of
                 a longer utterance for the streaming decoder's chunking measurement.

liquidonnx is imported from --liquidonnx-src (its src folder). ONNXOmniModel needs torch only for
its image processor, so the script loads the sessions and ONNXAudioModel's preprocessing (tokenizer
and mel filters) and patchifies the test image itself; stft_to_wave imports istft_same_padding from
a module that needs onnxruntime_genai, so that one function is compiled from its source alone.

Binary files are raw little-endian float32 (*.f32). manifest.json lists every case.

Usage:
    uv run tools/golden/generate-engine-golden.py \\
        --model-dir /path/to/LFM3-Omni-2B-ONNX \\
        --liquidonnx-src /path/to/liquidonnx/src \\
        --speech-wav /path/to/liquidonnx/samples/audio/woodworks_question.wav
"""

import argparse
import ast
import hashlib
import json
import logging
import pathlib
import shutil
import sys
import time
import types

import numpy as np
import onnxruntime

logger = logging.getLogger(__name__)

REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT_DIR = REPOSITORY_ROOT / "engine/src/test/resources/golden/engine"
MAX_TOTAL_BYTES = 1024 * 1024

IM_START_TOKEN = 124899
IM_END_TOKEN = 124900

# Prefill runs (PrefillChunks.MAX_CHUNK)
MAX_CHUNK = 256

# Sampling: greedy text; greedy speech codes with the checkpoint's repetition penalty
AUDIO_REPETITION_PENALTY = 1.2
AUDIO_REPETITION_WINDOW = 64
NUM_CODEBOOKS = 12

# Vision (processor_config.json)
PATCH_SIZE = 16
MERGE_SIZE = 2
TEMPORAL_PATCH_SIZE = 2
PIXEL_CENTER = 127.5

# The procedural test image: a red disk on a blue background, already at a resize target
IMAGE_SIZE = 256
DISK_RADIUS = 80
DISK_COLOR = (220, 30, 30)
BACKGROUND_COLOR = (40, 90, 200)

# The speech answers whose limit leaves room for the whole turn: through the end-of-speech frame
# fed back and the <|im_end|> after it
END_OF_TURN_CASES = ("speech-to-the-end", "interleaved")

TTS_SYSTEM = "Perform TTS in American English using a female voice."
INTERLEAVED_SYSTEM = "Respond with interleaved text and audio."
ASR_SYSTEM = "Perform ASR in English."

WEATHER_TOOL = {
    "type": "function",
    "function": {
        "name": "get_weather",
        "description": "Gets the current weather in a city.",
        "parameters": {
            "type": "object",
            "properties": {"city": {"type": "string", "description": "The city name"}},
            "required": ["city"],
        },
    },
}


class GoldenWriter:
    """Writes the binary files under the output folder and remembers their total size."""

    def __init__(self, output_dir: pathlib.Path):
        self.output_dir = output_dir
        self.total_bytes = 0

    def f32(self, relative_path: str, values: np.ndarray) -> str:
        path = self.output_dir / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        data = np.ascontiguousarray(values, dtype="<f4")
        data.tofile(path)
        self.total_bytes += data.nbytes
        return relative_path

    def copy(self, relative_path: str, source: pathlib.Path) -> str:
        path = self.output_dir / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, path)
        self.total_bytes += path.stat().st_size
        return relative_path


def sha256_f32(values: np.ndarray) -> str:
    data = np.ascontiguousarray(values, dtype="<f4")
    return hashlib.sha256(data.tobytes()).hexdigest()


def identity_orders():
    """MaskGIT orders that reveal the codebooks in index order."""
    order = np.arange(NUM_CODEBOOKS, dtype=np.int64)
    return lambda: order.copy()


def greedy_speech(session_module):
    return session_module.AudioSampling(
        temperature=0.0,
        repetition_penalty=AUDIO_REPETITION_PENALTY,
        repetition_window=AUDIO_REPETITION_WINDOW,
    )


def test_image() -> np.ndarray:
    """[256, 256, 3] uint8: the red disk on blue that EngineGolden.testImage draws in Kotlin."""
    y, x = np.mgrid[0:IMAGE_SIZE, 0:IMAGE_SIZE]
    center = IMAGE_SIZE // 2
    inside = (x - center) ** 2 + (y - center) ** 2 < DISK_RADIUS**2
    image = np.empty((IMAGE_SIZE, IMAGE_SIZE, 3), dtype=np.uint8)
    image[:, :] = BACKGROUND_COLOR
    image[inside] = DISK_COLOR
    return image


def pixel_values(image: np.ndarray) -> np.ndarray:
    """The vision encoder input of an image already at its resize target, as VisionPreprocessor
    lays it out: [rows / 2, columns / 2, 2, 2, 3 * 2 * 16 * 16] with each patch [channel][temporal]
    [y][x], the image in both temporal slots, normalized as (v - 127.5) / 127.5 in float32."""
    height, width, _ = image.shape
    rows, columns = height // PATCH_SIZE, width // PATCH_SIZE
    center = np.float32(PIXEL_CENTER)
    normalized = (image.astype(np.float32) - center) / center
    frames = np.stack([normalized] * TEMPORAL_PATCH_SIZE)
    blocks = frames.reshape(
        TEMPORAL_PATCH_SIZE,
        rows // MERGE_SIZE,
        MERGE_SIZE,
        PATCH_SIZE,
        columns // MERGE_SIZE,
        MERGE_SIZE,
        PATCH_SIZE,
        3,
    )
    # [block row, block column, merge row, merge column, channel, temporal, y, x]
    ordered = blocks.transpose(1, 4, 2, 5, 7, 0, 3, 6)
    patch_dim = 3 * TEMPORAL_PATCH_SIZE * PATCH_SIZE * PATCH_SIZE
    shape = (rows // MERGE_SIZE, columns // MERGE_SIZE, MERGE_SIZE, MERGE_SIZE, patch_dim)
    return np.ascontiguousarray(ordered).reshape(shape)


def sampled_rows(row_count: int, step: int) -> list[int]:
    """Every step-th row and the last one."""
    rows = list(range(0, row_count, step))
    if rows[-1] != row_count - 1:
        rows.append(row_count - 1)
    return rows


def install_istft(liquidonnx_src: pathlib.Path):
    """Makes liquidonnx.lfm2_audio.infer importable for stft_to_wave without onnxruntime_genai:
    a module holding only its istft_same_padding, compiled from the reference source."""
    path = liquidonnx_src / "liquidonnx/lfm2_audio/infer.py"
    tree = ast.parse(path.read_text(), filename=str(path))
    functions = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "istft_same_padding"]
    if len(functions) != 1:
        raise SystemExit(f"{path} has no istft_same_padding")
    module = types.ModuleType("liquidonnx.lfm2_audio.infer")
    module.__dict__["np"] = np
    code = compile(ast.Module(body=functions, type_ignores=[]), str(path), "exec")
    # The one function of the reference source given on the command line, nothing else
    exec(code, module.__dict__)  # noqa: S102
    sys.modules[module.__name__] = module


def checkpoints(ids: list[int]) -> list[int]:
    """ChatTemplate's checkpoints: the start of every message (its <|im_start|>) and the end."""
    starts = [position for position, token in enumerate(ids) if token == IM_START_TOKEN]
    return starts + [len(ids)]


def prefill_chunks(start: int, end: int, checkpoint_positions: list[int]) -> list[tuple[int, int]]:
    """PrefillChunks.of: runs of at most MAX_CHUNK positions split at the checkpoints between,
    except that the last run never feeds a single position when more are fed."""
    chunks = []
    while start < end:
        limit = min(start + MAX_CHUNK, end)
        if end - limit == 1 and limit - start > 1:
            limit -= 1
        candidates = [c for c in checkpoint_positions if start < c <= limit and c != end - 1]
        stop = candidates[0] if candidates else limit
        chunks.append((start, stop))
        start = stop
    return chunks


class ChunkedPrefill:
    """A CachedDecoder whose first step, the prefill, runs in the given chunks, each with the
    feature rows of its placeholders; the steps after it run as they are."""

    def __init__(self, decoder, chunks: list[tuple[int, int]], feature_tokens: dict[str, int]):
        self.decoder = decoder
        self.chunks = chunks
        self.feature_tokens = feature_tokens
        self.prefilled = False

    @property
    def last_outputs(self):
        return self.decoder.last_outputs

    def step(self, ids: list[int], **inputs: np.ndarray) -> np.ndarray:
        if self.prefilled:
            return self.decoder.step(ids, **inputs)
        self.prefilled = True
        if self.chunks[-1][1] != len(ids):
            raise ValueError(f"chunks end at {self.chunks[-1][1]}, the prefill has {len(ids)} ids")
        used = {name: 0 for name in self.feature_tokens}
        logits = None
        for start, end in self.chunks:
            chunk_ids = ids[start:end]
            chunk_inputs = dict(inputs)
            for name, token in self.feature_tokens.items():
                count = chunk_ids.count(token)
                chunk_inputs[name] = inputs[name][used[name] : used[name] + count]
                used[name] += count
            logits = self.decoder.step(chunk_ids, **chunk_inputs)
        return logits


class Reference:
    """The liquidonnx model and the generations of the golden cases."""

    def __init__(self, model_dir: pathlib.Path, liquidonnx_src: pathlib.Path):
        sys.path.insert(0, str(liquidonnx_src))
        install_istft(liquidonnx_src)
        from liquidonnx.lfm3_audio import session as audio_session
        from liquidonnx.lfm3_omni.session import ONNXOmniModel
        from liquidonnx.lfm3_vl.session import expand_image_tokens, run_vision

        self.audio_session = audio_session
        self.expand_image_tokens = expand_image_tokens
        self.run_vision = run_vision
        self.model = ONNXOmniModel(model_dir, precision="q4", force_cpu=True)
        self.model.load_sessions()
        audio_session.ONNXAudioModel.load_preprocessing(self.model)
        self.mel_config = self.model.config.mel
        self.cached_decoder = self.model.cached_decoder

    def prompt(self, messages, tools=None, audio_counts=(), image_grids=None) -> list[int]:
        """The chat template's generation prompt, tokenized, with media placeholders expanded."""
        text = self.model.tokenizer.apply_chat_template(
            messages, tools=tools, tokenize=False, add_generation_prompt=True
        )
        ids = self.model.tokenizer(text, add_special_tokens=False)["input_ids"]
        audio_token = self.model.config.tokens.audio
        counts = iter(audio_counts)
        expanded = []
        for token in ids:
            repeat = next(counts) if token == audio_token else 1
            expanded.extend([token] * repeat)
        if image_grids is not None:
            image_token = self.model.vision_tokens.image
            expanded = self.expand_image_tokens(expanded, image_grids, image_token, MERGE_SIZE)
        return expanded

    def generate(self, ids, mode, max_slots, orders=None, **features) -> dict:
        """The slots after ids (EOS frames included) and the marker the mode appended."""
        model, tokens = self.model, self.model.config.tokens
        text_only = mode == "text"

        # generate_ids appends the mode's first marker unless the prompt ends with one of them
        markers = {
            "audio": (tokens.audio_start, tokens.audio_out),
            "interleaved": (tokens.mixed_start,),
        }
        marker = None
        if mode in markers and ids[-1] not in markers[mode]:
            marker = markers[mode][0]
        fed = len(ids) + (marker is not None)
        prompt_checkpoints = checkpoints(ids)
        chunks = prefill_chunks(0, fed, prompt_checkpoints)
        model.cached_decoder = lambda capacity: ChunkedPrefill(
            self.cached_decoder(capacity), chunks, model.feature_tokens
        )

        start = time.perf_counter()
        generation = model.generate_ids(
            ids,
            max_new_tokens=max_slots,
            generation_mode=None if mode == "auto" else mode,
            audio_sampling=greedy_speech(self.audio_session),
            suppress_mixed=text_only,
            suppress_audio_out=text_only,
            return_audio_eos=True,
            orders=orders or identity_orders(),
            **features,
        )
        elapsed = time.perf_counter() - start
        model.cached_decoder = self.cached_decoder

        text_ids = [t for t in generation.tokens if t != tokens.audio_out]
        text = model.tokenizer.decode(text_ids, skip_special_tokens=True)
        frames = generation.audio_codes.astype(int).tolist()
        logger.info(f"  {len(generation.tokens)} slots ({len(frames)} frames) in {elapsed:.1f} s: {text!r}")
        return {
            "checkpoints": prompt_checkpoints,
            "chunks": [list(chunk) for chunk in chunks],
            "marker": marker,
            "slots": [int(t) for t in generation.tokens],
            "frames": frames,
            "text": text,
        }


def chat_case(reference: Reference, name, messages, mode, max_slots, tools=None) -> dict:
    logger.info(f"{name}")
    ids = reference.prompt(messages, tools=tools)
    result = reference.generate(ids, mode, max_slots)
    return {
        "name": name,
        "messages": messages,
        "tools": tools or [],
        "mode": mode,
        "maxSlots": max_slots,
        "prompt": ids,
        **result,
    }


def audio_case(reference: Reference, writer: GoldenWriter, speech_wav: pathlib.Path) -> tuple[dict, dict]:
    """The clip's features (sampled rows) and a speech recognition generation (automatic mode)."""
    logger.info("asr")
    model = reference.model
    wave = reference.audio_session.load_audio(speech_wav, reference.mel_config.sampling_rate)
    mel = reference.audio_session.log_mel(wave, reference.mel_config, model.mel_filters)
    features = model.encode_audio([wave])
    rows = sampled_rows(len(features), 8)
    clip = writer.copy("audio/speech.wav", speech_wav)
    expected = writer.f32("audio/encoder-rows.f32", features[rows])
    audio = {
        "clip": clip,
        "sampleCount": len(wave),
        "melFrames": int(mel.shape[1]),
        "melSha256": sha256_f32(mel),
        "rowCount": len(features),
        "rows": rows,
        "expected": expected,
    }

    messages = [
        {"role": "system", "content": ASR_SYSTEM},
        {"role": "user", "content": [{"type": "audio"}]},
    ]
    ids = reference.prompt(messages, audio_counts=[len(features)])
    result = reference.generate(ids, "auto", 40, audio_features=features)
    generation = {"name": "asr", "mode": "auto", "maxSlots": 40, "prompt": ids, **result}
    return audio, generation


def vision_case(reference: Reference, writer: GoldenWriter) -> tuple[dict, dict]:
    """The test image's features (sampled rows) and a question about it."""
    logger.info("image question")
    image = test_image()
    values = pixel_values(image)
    rows, columns = IMAGE_SIZE // PATCH_SIZE, IMAGE_SIZE // PATCH_SIZE
    grids = np.array([[1, rows, columns]], dtype=np.int64)
    flat = values.reshape(-1, values.shape[-1])
    features = reference.run_vision(reference.model.vision_encoder, flat, grids, MERGE_SIZE)
    sampled = sampled_rows(len(features), 8)
    expected = writer.f32("vision/encoder-rows.f32", features[sampled])
    vision = {
        "size": IMAGE_SIZE,
        "pixelValuesSha256": sha256_f32(values),
        "rowCount": len(features),
        "rows": sampled,
        "expected": expected,
    }

    question = "What shape is in the picture, and what color is it? Answer in a few words."
    messages = [{"role": "user", "content": [{"type": "image"}, {"type": "text", "text": question}]}]
    ids = reference.prompt(messages, image_grids=grids)
    result = reference.generate(ids, "text", 24, image_features=features)
    generation = {
        "name": "image-question",
        "question": question,
        "mode": "text",
        "maxSlots": 24,
        "prompt": ids,
        **result,
    }
    return vision, generation


def codec_case(reference: Reference, writer: GoldenWriter, tts_frames: list[list[int]]) -> dict:
    """decode_audio of the text to speech frames, and a longer utterance's codes."""
    logger.info("codec")
    eos = reference.model.config.audio_eos_code
    frames = np.array([frame for frame in tts_frames if frame[0] != eos], dtype=np.int64)
    wave = reference.model.decode_audio(frames)
    expected = writer.f32("codec/tts-wave.f32", wave)

    messages = [
        {"role": "system", "content": TTS_SYSTEM},
        {
            "role": "user",
            "content": "The quick brown fox jumps over the lazy dog, then it runs all the way back home.",
        },
    ]
    ids = reference.prompt(messages)
    long_utterance = reference.generate(ids, "audio", 120)
    long_frames = [frame for frame in long_utterance["frames"] if frame[0] != eos]
    return {"frames": frames.tolist(), "expected": expected, "longFrames": long_frames}


def require_end_of_turn(case: dict, eos_code: int):
    """Fails unless the case's answer ran through an end-of-speech frame to <|im_end|>."""
    ended_turn = case["slots"][-1] == IM_END_TOKEN
    ended_speech = any(frame[0] == eos_code for frame in case["frames"])
    if not (ended_turn and ended_speech):
        raise SystemExit(f"{case['name']} did not end its speech and turn within {case['maxSlots']} slots")


def clear_output_dir(output_dir: pathlib.Path):
    """Removes the golden data of a previous run; refuses to touch a non-empty folder without a
    manifest.json, which is not golden data."""
    if not output_dir.exists():
        return
    if any(output_dir.iterdir()) and not (output_dir / "manifest.json").is_file():
        raise SystemExit(f"{output_dir} is not empty and has no manifest.json: refusing to replace it")
    shutil.rmtree(output_dir)


def main():
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model-dir", type=pathlib.Path, required=True, help="the Hugging Face export (onnx/ inside)")
    parser.add_argument("--liquidonnx-src", type=pathlib.Path, required=True, help="liquidonnx's src folder")
    parser.add_argument("--speech-wav", type=pathlib.Path, required=True, help="a 16 kHz mono speech WAV")
    parser.add_argument("--output-dir", type=pathlib.Path, default=DEFAULT_OUTPUT_DIR)
    args = parser.parse_args()

    reference = Reference(args.model_dir, args.liquidonnx_src)

    generations = [
        chat_case(
            reference,
            "capital",
            [{"role": "user", "content": "What is the capital of France? Answer in one short sentence."}],
            "text",
            32,
        ),
        chat_case(
            reference,
            "multi-turn",
            [
                {"role": "system", "content": "You are a helpful assistant."},
                {"role": "user", "content": "My name is Ada. Please remember it."},
                {"role": "assistant", "content": "Nice to meet you, Ada! I will remember your name."},
                {"role": "user", "content": "What is my name?"},
            ],
            "text",
            24,
        ),
        chat_case(
            reference,
            "tool-call",
            [{"role": "user", "content": "What is the weather like in Paris right now?"}],
            "text",
            48,
            tools=[WEATHER_TOOL],
        ),
        chat_case(
            reference,
            "speech",
            [
                {"role": "system", "content": TTS_SYSTEM},
                {"role": "user", "content": "Hello! My name is Curie. How can I help you today?"},
            ],
            "audio",
            20,
        ),
        chat_case(
            reference,
            "speech-to-the-end",
            [
                {"role": "system", "content": TTS_SYSTEM},
                {"role": "user", "content": "Hello! My name is Curie."},
            ],
            "audio",
            60,
        ),
        chat_case(
            reference,
            "interleaved",
            [
                {"role": "system", "content": INTERLEAVED_SYSTEM},
                {"role": "user", "content": "What is two plus two?"},
            ],
            "interleaved",
            100,
        ),
    ]
    eos_code = reference.model.config.audio_eos_code
    for case in generations:
        if case["name"] in END_OF_TURN_CASES:
            require_end_of_turn(case, eos_code)

    # The first file written: until here a failed run leaves the previous golden data in place
    clear_output_dir(args.output_dir)
    writer = GoldenWriter(args.output_dir)
    audio, asr = audio_case(reference, writer, args.speech_wav)
    vision, image_question = vision_case(reference, writer)
    generations += [asr, image_question]
    speech = next(case for case in generations if case["name"] == "speech")
    codec = codec_case(reference, writer, speech["frames"])

    manifest = {
        "generator": "tools/golden/generate-engine-golden.py",
        "versions": {"numpy": np.__version__, "onnxruntime": onnxruntime.__version__},
        "audioSampling": {
            "temperature": 0.0,
            "repetitionPenalty": AUDIO_REPETITION_PENALTY,
            "repetitionWindow": AUDIO_REPETITION_WINDOW,
        },
        "generations": generations,
        "audio": audio,
        "vision": vision,
        "codec": codec,
    }
    manifest_path = args.output_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=1) + "\n")

    total_bytes = writer.total_bytes + manifest_path.stat().st_size
    logger.info(f"Wrote {args.output_dir} ({total_bytes / 1024:.0f} KiB)")
    if total_bytes > MAX_TOTAL_BYTES:
        raise SystemExit(f"golden data is {total_bytes} bytes, over the {MAX_TOTAL_BYTES} byte budget")


if __name__ == "__main__":
    main()
