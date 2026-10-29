# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "numpy>=2.2",
#     "onnx==1.23.2",
#     "onnxruntime==1.31.1",
#     "safetensors>=0.6",
# ]
# ///
"""
Checks the assets of build-lora-assets.py against a downloaded LFM3-Omni-2B-ONNX q4 bundle with
onnxruntime:

    1. each patched graph with an all-zero adapter reproduces the original graph,
    2. the patched depth decoder with the real adapter matches the original depth decoder whose
       float32 weights have the LoRA merged in (W + scale * (B @ A).T),
    3. the patched decoder with the real adapter moves the logits (the LoRA is live).

Usage:
    uv run tools/lora/verify-lora-assets.py --model-dir /path/to/LFM3-Omni-2B-ONNX \\
        --assets-dir app/src/main/assets/lora --adapter-dir tmp/lfm3-audio-curie-lora
"""

import argparse
import contextlib
import json
import logging
import pathlib
import shutil
from dataclasses import dataclass

import numpy as np
import onnx
import onnxruntime
from onnx import numpy_helper
from safetensors.numpy import load_file

logger = logging.getLogger(__name__)

HIDDEN_SIZE = 2048
NUM_CODEBOOKS = 12
AUDIO_EOS_CODE = 2016
DEPTH_PREFIX = "base_model.model.model.depth_decoder.model.layers."
DEPTH_WEIGHTS = {
    "self_attn.q_proj": "attn.q_proj",
    "self_attn.k_proj": "attn.k_proj",
    "self_attn.v_proj": "attn.v_proj",
    "self_attn.o_proj": "attn.o_proj",
    "feed_forward.w1": "mlp.gate_proj",
    "feed_forward.w3": "mlp.up_proj",
    "feed_forward.w2": "mlp.down_proj",
}


@contextlib.contextmanager
def staged_assets(assets_dir: pathlib.Path, onnx_dir: pathlib.Path):
    """Copies the LoRA assets next to the downloaded weights for the duration of the check.

    onnxruntime resolves symlinks and rejects external data outside the graph's real folder, so the
    patched graphs must be real files in the folder of the weights (as they are on the device).
    """
    created = []
    try:
        for path in assets_dir.iterdir():
            target = onnx_dir / path.name
            if target.exists():
                raise FileExistsError(f"{target} exists; remove it before verifying")
            shutil.copyfile(path, target)
            created.append(target)
        yield created
    finally:
        for path in created:
            path.unlink(missing_ok=True)


def zero_adapter(adapter_path: pathlib.Path, out_path: pathlib.Path) -> None:
    """A copy of the adapter whose B matrices are zero: its LoRA branches add nothing."""
    source = onnxruntime.AdapterFormat.read_adapter(str(adapter_path))
    parameters = {}
    for name, value in source.get_parameters().items():
        array = value.numpy()
        if name.endswith(".B"):
            array = np.zeros_like(array)
        parameters[name] = onnxruntime.OrtValue.ortvalue_from_numpy(array)

    adapter = onnxruntime.AdapterFormat()
    adapter.set_adapter_version(1)
    adapter.set_model_version(1)
    adapter.set_parameters(parameters)
    adapter.export_adapter(str(out_path))


@dataclass
class AdapterRun:
    """Run options with an active adapter; the adapter memory-maps its file and must outlive the runs."""

    adapter: onnxruntime.LoraAdapter
    options: onnxruntime.RunOptions


def run_options(adapter_path: pathlib.Path) -> AdapterRun:
    adapter = onnxruntime.LoraAdapter()
    adapter.Load(str(adapter_path))

    options = onnxruntime.RunOptions()
    options.add_active_adapter(adapter)
    return AdapterRun(adapter, options)


def session(path: pathlib.Path) -> onnxruntime.InferenceSession:
    # Errors only: the LoRA inputs are overridable initializers, which onnxruntime warns about by design
    options = onnxruntime.SessionOptions()
    options.log_severity_level = 3
    return onnxruntime.InferenceSession(str(path), sess_options=options, providers=["CPUExecutionProvider"])


def merged_depth_decoder(work_dir: pathlib.Path, adapter_dir: pathlib.Path) -> pathlib.Path:
    """The original depth decoder with the LoRA merged into its float32 projection weights."""
    config = json.loads((adapter_dir / "adapter_config.json").read_text())
    scale = config["lora_alpha"] / config["r"]
    tensors = load_file(adapter_dir / "adapter_model.safetensors")

    model = onnx.load(str(work_dir / "depth_decoder.onnx"))
    initializers = {tensor.name: tensor for tensor in model.graph.initializer}
    merged = 0
    for layer in range(3):
        for peft_name, onnx_name in DEPTH_WEIGHTS.items():
            stem = f"{DEPTH_PREFIX}{layer}.{peft_name}"
            lora_a = tensors[f"{stem}.lora_A.weight"]
            lora_b = tensors[f"{stem}.lora_B.weight"]

            # ONNX MatMul weights are [in, out], the transpose of torch's nn.Linear weight
            tensor = initializers[f"model.layers.{layer}.{onnx_name}.MatMul.weight"]
            weight = numpy_helper.to_array(tensor)
            delta = scale * (lora_b @ lora_a).T
            tensor.CopyFrom(numpy_helper.from_array((weight + delta).astype(np.float32), tensor.name))
            merged += 1

    path = work_dir / "depth_decoder.merged.onnx"
    onnx.save(model, str(path), save_as_external_data=True, location="depth_decoder.merged.onnx_data")
    logger.info(f"Merged the LoRA into {merged} depth decoder weights")
    return path


def depth_feed(rng: np.random.Generator) -> dict[str, np.ndarray]:
    batch = 4
    return {
        "hidden_states": rng.standard_normal((batch, HIDDEN_SIZE)).astype(np.float32),
        "codes": rng.integers(0, AUDIO_EOS_CODE, (batch, NUM_CODEBOOKS)).astype(np.int64),
        "masked": rng.random((batch, NUM_CODEBOOKS)) < 0.5,
    }


def decoder_feed(base: onnxruntime.InferenceSession) -> dict[str, np.ndarray]:
    """A 12-token text prompt with empty media inputs and empty caches."""
    ids = np.array([[124894, 124899, 882, 198, 9707, 11, 1268, 527, 499, 30, 124900, 198]], dtype=np.int64)
    sequence_length = ids.shape[1]
    feed = {
        "input_ids": ids,
        "attention_mask": np.ones((1, sequence_length), dtype=np.int64),
        "position_ids": np.arange(sequence_length, dtype=np.int64)[None],
        "image_features": np.zeros((0, HIDDEN_SIZE), dtype=np.float32),
        "video_features": np.zeros((0, HIDDEN_SIZE), dtype=np.float32),
        "audio_features": np.zeros((0, HIDDEN_SIZE), dtype=np.float32),
        "audio_out_codes": np.zeros((0, NUM_CODEBOOKS), dtype=np.int64),
        "logits_to_keep": np.array([1], dtype=np.int64),
    }
    for graph_input in base.get_inputs():
        if graph_input.name.startswith("past_conv."):
            feed[graph_input.name] = np.zeros((1, HIDDEN_SIZE, 7), dtype=np.float32)
        elif graph_input.name.startswith("past_key_values."):
            feed[graph_input.name] = np.zeros((1, 4, 0, 128), dtype=np.float32)
    return feed


def max_difference(left: np.ndarray, right: np.ndarray) -> float:
    return float(np.abs(left - right).max())


def check(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)
    logger.info(f"PASS {message}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model-dir", type=pathlib.Path, required=True, help="Folder with onnx/*.onnx_data")
    parser.add_argument("--assets-dir", type=pathlib.Path, required=True, help="Output of build-lora-assets.py")
    parser.add_argument("--adapter-dir", type=pathlib.Path, required=True, help="PEFT adapter folder")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    rng = np.random.default_rng(0)

    work_dir = args.model_dir / "onnx"
    with staged_assets(args.assets_dir, work_dir) as created:
        scratch_files = [
            work_dir / "depth_zero.onnx_adapter",
            work_dir / "decoder_zero.onnx_adapter",
            work_dir / "depth_decoder.merged.onnx",
            work_dir / "depth_decoder.merged.onnx_data",
        ]
        created.extend(scratch_files)
        zero_adapter(work_dir / "depth_decoder.onnx_adapter", work_dir / "depth_zero.onnx_adapter")
        zero_adapter(work_dir / "decoder_q4.onnx_adapter", work_dir / "decoder_zero.onnx_adapter")

        # === 1. Depth decoder ===
        feed = depth_feed(rng)
        base = session(work_dir / "depth_decoder.onnx").run(["logits"], feed)[0]
        patched_session = session(work_dir / "depth_decoder.lora.onnx")
        zero_options = run_options(work_dir / "depth_zero.onnx_adapter")
        lora_options = run_options(work_dir / "depth_decoder.onnx_adapter")
        patched_none = patched_session.run(["logits"], feed)[0]
        patched_zero = patched_session.run(["logits"], feed, zero_options.options)[0]
        patched_lora = patched_session.run(["logits"], feed, lora_options.options)[0]
        merged = session(merged_depth_decoder(work_dir, args.adapter_dir)).run(["logits"], feed)[0]

        check(max_difference(patched_none, base) < 1e-5, "depth decoder: no adapter == original")
        check(max_difference(patched_zero, base) < 1e-5, "depth decoder: zero adapter == original")
        lora_vs_merged = max_difference(patched_lora, merged)
        lora_vs_base = max_difference(patched_lora, base)
        logger.info(f"depth decoder: |lora - merged| = {lora_vs_merged:.2e}, |lora - original| = {lora_vs_base:.2e}")
        check(lora_vs_merged < 1e-3 * lora_vs_base, "depth decoder: adapter == merged weights")

        # === 2. Decoder ===
        base_session = session(work_dir / "decoder_q4.onnx")
        feed = decoder_feed(base_session)
        base = base_session.run(["logits", "hidden_states"], feed)
        patched_session = session(work_dir / "decoder_q4.lora.onnx")
        zero_options = run_options(work_dir / "decoder_zero.onnx_adapter")
        lora_options = run_options(work_dir / "decoder_q4.onnx_adapter")
        patched_none = patched_session.run(["logits", "hidden_states"], feed)
        patched_zero = patched_session.run(["logits", "hidden_states"], feed, zero_options.options)
        patched_lora = patched_session.run(["logits", "hidden_states"], feed, lora_options.options)

        check(max_difference(patched_none[0], base[0]) < 1e-4, "decoder: no adapter logits == original")
        check(max_difference(patched_zero[0], base[0]) < 1e-4, "decoder: zero adapter logits == original")
        check(max_difference(patched_zero[1], base[1]) < 1e-4, "decoder: zero adapter hidden states == original")
        hidden_shift = max_difference(patched_lora[1], base[1])
        logger.info(f"decoder: |lora - original| hidden states = {hidden_shift:.2e}")
        check(hidden_shift > 1e-2, "decoder: the adapter changes the hidden states")
        base_token = int(base[0][0, -1].argmax())
        lora_token = int(patched_lora[0][0, -1].argmax())
        logger.info(f"decoder: next token {base_token} (original), {lora_token} (LoRA)")


if __name__ == "__main__":
    main()
