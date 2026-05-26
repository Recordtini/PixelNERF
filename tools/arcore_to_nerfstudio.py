#!/usr/bin/env python3
"""Convert a PixelNERF ARCore session to a Nerfstudio-style transforms.json.

Usage:
    python tools/arcore_to_nerfstudio.py /path/to/session --output transforms.json
    python tools/arcore_to_nerfstudio.py /path/to/session --flip-yz

The coordinate conventions between ARCore, Nerfstudio, original 3DGS, and COLMAP-derived
pipelines can vary. This converter starts conservative: it preserves the ARCore 4x4 matrix
unless a coordinate option is requested.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


def row_major_to_matrix(values: list[float]) -> list[list[float]]:
    if len(values) != 16:
        raise ValueError(f"Expected 16 transform values, got {len(values)}")
    return [values[i:i + 4] for i in range(0, 16, 4)]


def matmul(a: list[list[float]], b: list[list[float]]) -> list[list[float]]:
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]


def flip_yz(matrix: list[list[float]]) -> list[list[float]]:
    # Simple coordinate test preset. Verify visually before trusting for production.
    flip = [
        [1.0, 0.0, 0.0, 0.0],
        [0.0, -1.0, 0.0, 0.0],
        [0.0, 0.0, -1.0, 0.0],
        [0.0, 0.0, 0.0, 1.0],
    ]
    return matmul(matrix, flip)


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows = []
    with path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            rows.append(json.loads(line))
    return rows


def convert(session_dir: Path, output: Path, apply_flip_yz: bool) -> None:
    records = read_jsonl(session_dir / "poses.jsonl")
    if not records:
        raise SystemExit("No pose records found.")

    first_intr = records[0]["intrinsics"]
    out: dict[str, Any] = {
        "camera_model": "OPENCV",
        "fl_x": first_intr["fx"],
        "fl_y": first_intr["fy"],
        "cx": first_intr["cx"],
        "cy": first_intr["cy"],
        "w": first_intr["width"],
        "h": first_intr["height"],
        "k1": 0.0,
        "k2": 0.0,
        "p1": 0.0,
        "p2": 0.0,
        "frames": [],
        "pixelnerf_notes": {
            "source": "ARCore camera.imageIntrinsics + camera.pose",
            "coordinate_warning": "Verify camera axes in your trainer. Use --flip-yz or add a preset if needed.",
        },
    }

    for rec in records:
        matrix = row_major_to_matrix(rec["transform_matrix_row_major_4x4"])
        if apply_flip_yz:
            matrix = flip_yz(matrix)
        frame = {
            "file_path": rec["file_path"],
            "transform_matrix": matrix,
            "timestamp_ns": rec.get("timestamp_ns"),
        }
        if "raw_depth_path" in rec:
            frame["raw_depth_path"] = rec["raw_depth_path"]
        if "raw_depth_confidence_path" in rec:
            frame["raw_depth_confidence_path"] = rec["raw_depth_confidence_path"]
        out["frames"].append(frame)

    output_path = output if output.is_absolute() else session_dir / output
    output_path.write_text(json.dumps(out, indent=2), encoding="utf-8")
    print(f"Wrote {output_path} with {len(out['frames'])} frames")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("session_dir", type=Path)
    parser.add_argument("--output", type=Path, default=Path("transforms.json"))
    parser.add_argument("--flip-yz", action="store_true", help="Apply a simple Y/Z axis flip test preset")
    args = parser.parse_args()
    convert(args.session_dir, args.output, args.flip_yz)


if __name__ == "__main__":
    main()
