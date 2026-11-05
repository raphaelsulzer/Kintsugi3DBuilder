#!/usr/bin/env python3
"""Evaluate a Kintsugi3D project's specular fit by re-rendering each view under its own
actual captured lighting and comparing to the real photo (PSNR), using
kintsugi3d.builder.fit.SpecularFitProcess.reconstructAllToArray() -- Kintsugi's own
image-reconstruction/RMSE machinery, generalized in this session to sum LIGHTS_PER_VIEW
lights (see this repo's claude/triple_flash_design.md).

Usage: PYTHONPATH=<this repo>/python python3 eval_psnr.py <project_dir> <jar_path> [texture_size]
<project_dir> must contain project.vset and a supporting/ directory (i.e. a run_kintsugi3d.py
output_dir).
"""
import math
import sys
from pathlib import Path

import jpype
import numpy as np

from kintsugi3d import Kintsugi3DPipeline, new_specular_fit_settings


def evaluate(project_dir: Path, jar_path: Path, texture_size: int = 2048):
    with Kintsugi3DPipeline(jar_path=jar_path) as pipeline:
        pipeline.set_preview_cache_directory(project_dir / "supporting" / "preview_cache")
        pipeline.load_from_vset(project_dir / "project.vset", project_dir / "supporting")

        SpecularFitProcess = jpype.JClass("kintsugi3d.builder.fit.SpecularFitProcess")
        settings = new_specular_fit_settings(texture_size)
        process = SpecularFitProcess(settings)

        rows = process.reconstructAllToArray(pipeline.resources)
        rows = np.array([[float(v) for v in row] for row in rows])
        return rows


def summarize(rows: np.ndarray, label: str):
    # columns: viewIndex, sampleCount, encodedGroundTruthRMSE, normalizedSRGB_RMSE, normalizedLinearRMSE
    encoded_rmse = rows[:, 2]
    sample_counts = rows[:, 1]

    # Sample-count-weighted mean RMSE across views (more populated views count more), then PSNR from that.
    weighted_mean_rmse = np.average(encoded_rmse, weights=sample_counts)
    psnr_from_weighted_mean_rmse = 20 * math.log10(1.0 / max(weighted_mean_rmse, 1e-12))

    # Also report the mean of per-view PSNRs (a different, also-common aggregation).
    per_view_psnr = 20 * np.log10(1.0 / np.maximum(encoded_rmse, 1e-12))

    print(f"=== {label} ===")
    print(f"  views: {len(rows)}")
    print(f"  sample-count-weighted mean encoded RMSE: {weighted_mean_rmse:.5f}")
    print(f"  PSNR from weighted-mean RMSE: {psnr_from_weighted_mean_rmse:.3f} dB")
    print(f"  mean of per-view PSNR: {per_view_psnr.mean():.3f} dB (std {per_view_psnr.std():.3f})")
    print(f"  median of per-view PSNR: {np.median(per_view_psnr):.3f} dB")
    return {
        "weighted_mean_rmse": weighted_mean_rmse,
        "psnr_from_weighted_mean_rmse": psnr_from_weighted_mean_rmse,
        "mean_per_view_psnr": float(per_view_psnr.mean()),
        "median_per_view_psnr": float(np.median(per_view_psnr)),
        "rows": rows,
    }


if __name__ == "__main__":
    project_dir = Path(sys.argv[1])
    jar_path = Path(sys.argv[2])
    texture_size = int(sys.argv[3]) if len(sys.argv) > 3 else 2048

    rows = evaluate(project_dir, jar_path, texture_size)
    summarize(rows, str(project_dir))
