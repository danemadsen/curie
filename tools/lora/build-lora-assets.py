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
Builds the LoRA assets that ship inside the Curie APK.

The base LFM3-Omni weights are downloaded by the app at runtime. The Curie voice LoRA (a PEFT adapter
trained on LFM3-Audio-2B, whose language model and depth decoder LFM3-Omni-2B shares) is bundled. ONNX
Runtime applies LoRA adapters by feeding their tensors as graph inputs (OrtLoraAdapter +
RunOptions.addActiveLoraAdapter), so this script:

    1. downloads the decoder_q4 and depth_decoder graph files (no weights) at the pinned revision,
    2. adds a LoRA branch next to every targeted projection:

           x ──► MatMul(W) ─────────────► Add ──► y      (y keeps its name, so consumers are untouched)
             └─► MatMul(A) ─► MatMul(B) ──┘

       where A = lora_A.T [in, rank] and B = (alpha / r) * lora_B.T [rank, out] are new graph inputs.
       Each defaults to an empty rank-0 initializer, so without an active adapter the branch adds
       zeros and the graph computes the base model,
    3. writes one .onnx_adapter file per graph holding A and B.

The patched graphs keep referencing decoder_q4.onnx_data and depth_decoder.onnx_data, so they must sit
next to those downloaded files at runtime.

Usage:
    uv run tools/lora/build-lora-assets.py --adapter-dir tmp/lfm3-audio-curie-lora \\
        --out-dir app/src/main/assets/lora
"""

import argparse
import hashlib
import json
import logging
import pathlib
import re
import urllib.request

import numpy as np
import onnx
import onnxruntime
from onnx import TensorProto, helper
from safetensors.numpy import load_file

logger = logging.getLogger(__name__)

MODEL_REPO = "LiquidAI/LFM3-Omni-2B-ONNX"
MODEL_REVISION = "7d6bd3fd59a0622e5ce7fefc141145a8bba1435a"

# Graph file -> its SHA-256 at MODEL_REVISION
GRAPH_FILES = {
    "decoder_q4.onnx": "4cabe6d2a638698c74aa97df5b7f485d2621f30b4276d0f88bdaace6e977d35a",
    "depth_decoder.onnx": "58884765498d20c4493ff04df12ef1f7d79630b61fd1aceaa2e8b884a72ab8f5",
}

# PEFT module (after the model prefix) -> the ONNX node name stem of the same projection
PROJECTION_NAMES = {
    "conv.in_proj": "conv/in_proj",
    "conv.out_proj": "conv/out_proj",
    "self_attn.q_proj": "attn/q_proj",
    "self_attn.k_proj": "attn/k_proj",
    "self_attn.v_proj": "attn/v_proj",
    "self_attn.o_proj": "attn/o_proj",
    "feed_forward.w1": "mlp/gate_proj",
    "feed_forward.w3": "mlp/up_proj",
    "feed_forward.w2": "mlp/down_proj",
}

# PEFT module prefix -> (graph file, adapter file)
GRAPH_PREFIXES = {
    "base_model.model.model.language_model.layers.": ("decoder_q4.onnx", "decoder_q4.onnx_adapter"),
    "base_model.model.model.depth_decoder.model.layers.": ("depth_decoder.onnx", "depth_decoder.onnx_adapter"),
}

LORA_KEY_PATTERN = re.compile(r"^(?P<prefix>.+\.layers\.)(?P<layer>\d+)\.(?P<module>.+)\.lora_(?P<part>[AB])\.weight$")

RANK_DIM = "lora_rank"


def download_graph(name: str, cache_dir: pathlib.Path) -> pathlib.Path:
    """The graph file at MODEL_REVISION, downloaded once and checked against GRAPH_FILES."""
    path = cache_dir / name
    if not path.exists():
        url = f"https://huggingface.co/{MODEL_REPO}/resolve/{MODEL_REVISION}/onnx/{name}"
        logger.info(f"Downloading {url}")
        cache_dir.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(url, path)

    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != GRAPH_FILES[name]:
        raise ValueError(f"{path} has SHA-256 {digest}, expected {GRAPH_FILES[name]} (revision {MODEL_REVISION})")
    return path


def lora_scale(config: dict) -> float:
    """alpha / r, after rejecting the PEFT options this script does not implement."""
    unsupported = {
        "peft_type": config.get("peft_type") != "LORA",
        "use_dora": bool(config.get("use_dora")),
        "fan_in_fan_out": bool(config.get("fan_in_fan_out")),
        "lora_bias": bool(config.get("lora_bias")),
        "rank_pattern": bool(config.get("rank_pattern")),
        "alpha_pattern": bool(config.get("alpha_pattern")),
    }
    rejected = [name for name, is_rejected in unsupported.items() if is_rejected]
    if rejected:
        raise NotImplementedError(f"unsupported adapter options: {rejected}")

    rank = config["r"]
    alpha = config["lora_alpha"]
    if config.get("use_rslora"):
        return alpha / np.sqrt(rank)
    return alpha / rank


def group_adapter_tensors(tensors: dict[str, np.ndarray]) -> dict[str, dict[str, dict[str, np.ndarray]]]:
    """graph file -> ONNX node stem -> {"A": lora_A, "B": lora_B}."""
    grouped: dict[str, dict[str, dict[str, np.ndarray]]] = {}
    for key, tensor in tensors.items():
        match = LORA_KEY_PATTERN.match(key)
        if match is None:
            raise ValueError(f"unexpected adapter tensor {key}")

        prefix = match["prefix"]
        module = match["module"]
        if prefix not in GRAPH_PREFIXES or module not in PROJECTION_NAMES:
            raise ValueError(f"adapter tensor {key} targets a module this script cannot map")

        graph_file, _ = GRAPH_PREFIXES[prefix]
        node_stem = f"/model/layers.{match['layer']}/{PROJECTION_NAMES[module]}/"
        targets = grouped.setdefault(graph_file, {})
        targets.setdefault(node_stem, {})[match["part"]] = tensor
    return grouped


def find_projection(graph: onnx.GraphProto, node_stem: str) -> onnx.NodeProto:
    """The single MatMul / MatMulNBits node of a projection."""
    candidates = [
        node
        for node in graph.node
        if node.name.startswith(node_stem) and node.op_type in ("MatMul", "MatMulNBits")
    ]
    if len(candidates) != 1:
        names = [node.name for node in candidates]
        raise ValueError(f"expected one projection node under {node_stem}, found {names}")
    return candidates[0]


def input_name(node_stem: str, part: str) -> str:
    """lora./model/layers.3/conv/in_proj/.A -> lora.layers.3.conv.in_proj.A"""
    path = node_stem.strip("/").removeprefix("model/")
    return f"lora.{path.replace('/', '.')}.{part}"


def patch_graph(
    model: onnx.ModelProto,
    targets: dict[str, dict[str, np.ndarray]],
    scale: float,
) -> dict[str, np.ndarray]:
    """Adds a LoRA branch to every target projection of model (in place); returns the adapter tensors."""
    graph = model.graph
    adapter: dict[str, np.ndarray] = {}
    patched_nodes = []

    for node_stem, parts in sorted(targets.items()):
        if set(parts) != {"A", "B"}:
            raise ValueError(f"{node_stem} needs both lora_A and lora_B")

        node = find_projection(graph, node_stem)
        lora_a = parts["A"].astype(np.float32)
        lora_b = parts["B"].astype(np.float32)
        rank, in_features = lora_a.shape
        out_features, rank_b = lora_b.shape
        if rank != rank_b:
            raise ValueError(f"{node_stem}: lora_A rank {rank} != lora_B rank {rank_b}")

        a_name = input_name(node_stem, "A")
        b_name = input_name(node_stem, "B")
        adapter[a_name] = np.ascontiguousarray(lora_a.T)
        adapter[b_name] = np.ascontiguousarray((lora_b * scale).T)

        a_input = helper.make_tensor_value_info(a_name, TensorProto.FLOAT, [in_features, RANK_DIM])
        b_input = helper.make_tensor_value_info(b_name, TensorProto.FLOAT, [RANK_DIM, out_features])
        graph.input.extend([a_input, b_input])

        # Rank-0 defaults make the inputs optional (overridable initializers): MatMul over an empty
        # inner dimension yields zeros, so an inactive adapter leaves the projection unchanged
        a_default = helper.make_tensor(a_name, TensorProto.FLOAT, [in_features, 0], [])
        b_default = helper.make_tensor(b_name, TensorProto.FLOAT, [0, out_features], [])
        graph.initializer.extend([a_default, b_default])

        projection_input = node.input[0]
        projection_output = node.output[0]
        base_output = f"{node_stem}lora/base_output"
        down_output = f"{node_stem}lora/down_output"
        delta_output = f"{node_stem}lora/delta_output"
        node.output[0] = base_output

        down = helper.make_node("MatMul", [projection_input, a_name], [down_output], name=f"{node_stem}lora/down")
        up = helper.make_node("MatMul", [down_output, b_name], [delta_output], name=f"{node_stem}lora/up")
        add = helper.make_node("Add", [base_output, delta_output], [projection_output], name=f"{node_stem}lora/add")
        patched_nodes.append((node, [down, up, add]))

    # Insert each branch right after its projection, which keeps the node list topologically sorted
    for node, branch in patched_nodes:
        position = list(graph.node).index(node) + 1
        for offset, branch_node in enumerate(branch):
            graph.node.insert(position + offset, branch_node)

    return adapter


def write_adapter(tensors: dict[str, np.ndarray], path: pathlib.Path) -> None:
    adapter = onnxruntime.AdapterFormat()
    adapter.set_adapter_version(1)
    adapter.set_model_version(1)

    parameters = {name: onnxruntime.OrtValue.ortvalue_from_numpy(array) for name, array in tensors.items()}
    adapter.set_parameters(parameters)

    adapter.export_adapter(str(path))


def patched_graph_name(graph_file: str) -> str:
    """decoder_q4.onnx -> decoder_q4.lora.onnx"""
    return graph_file.removesuffix(".onnx") + ".lora.onnx"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--adapter-dir", type=pathlib.Path, required=True, help="PEFT adapter folder")
    parser.add_argument("--out-dir", type=pathlib.Path, required=True, help="Where the assets are written")
    parser.add_argument(
        "--graph-cache",
        type=pathlib.Path,
        default=pathlib.Path("build/lora-graphs"),
        help="Where the downloaded graph files are kept",
    )
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(message)s")

    config = json.loads((args.adapter_dir / "adapter_config.json").read_text())
    scale = lora_scale(config)
    tensors = load_file(args.adapter_dir / "adapter_model.safetensors")
    grouped = group_adapter_tensors(tensors)
    logger.info(f"LoRA rank {config['r']}, alpha {config['lora_alpha']}, scale {scale}")

    args.out_dir.mkdir(parents=True, exist_ok=True)
    for prefix, (graph_file, adapter_file) in GRAPH_PREFIXES.items():
        targets = grouped.get(graph_file, {})
        if not targets:
            raise ValueError(f"the adapter has no tensors for {prefix}")

        graph_path = download_graph(graph_file, args.graph_cache)
        model = onnx.load(str(graph_path), load_external_data=False)
        adapter = patch_graph(model, targets, scale)

        # The LFM Open License asks modified files to carry a prominent notice of the change
        model.doc_string = (
            f"Modified by Curie: LoRA inputs added to {graph_file} of {MODEL_REPO} at revision {MODEL_REVISION} "
            "by tools/lora/build-lora-assets.py"
        )

        # onnx.checker needs the external weights, which only exist next to a downloaded model:
        # verify-lora-assets.py validates the patched graphs with onnxruntime instead
        patched_path = args.out_dir / patched_graph_name(graph_file)
        onnx.save(model, str(patched_path))
        write_adapter(adapter, args.out_dir / adapter_file)

        parameter_count = sum(array.size for array in adapter.values())
        logger.info(f"{patched_path.name}: {len(targets)} LoRA branches, {parameter_count:,} adapter parameters")


if __name__ == "__main__":
    main()
