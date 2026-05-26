# PixelNERF

PixelNERF is a starter Android/ARCore capture app for recording Pixel camera frames with native ARCore alignment data for NeRF and 3D Gaussian Splatting workflows.

The goal is to capture the data COLMAP usually has to estimate after the fact:

- RGB frames
- ARCore camera pose per frame
- camera intrinsics
- timestamps
- optional raw depth
- optional depth confidence

This is intended for Pixel phones such as the Pixel 10 Pro and other ARCore-supported Android devices.

## Status

Early starter project. The app structure, capture session format, and conversion tooling are laid out, but this should be treated as a development baseline rather than a finished Play Store app.

## Dataset layout

```text
PixelNERF/session_YYYYMMDD_HHMMSS/
  images/
    frame_000001.jpg
    frame_000002.jpg
  depth_raw/
    frame_000001_depth_u16.raw
  confidence/
    frame_000001_conf_u8.raw
  poses.jsonl
  session.json
```

Each `poses.jsonl` line contains one JSON object with frame path, timestamp, ARCore camera pose matrix, intrinsics, and optional depth paths.

## Build requirements

- Android Studio
- Android Gradle Plugin 8.x
- Kotlin
- ARCore-supported Android phone
- Google Play Services for AR installed

## Convert to Nerfstudio

After copying a session folder to your PC:

```bash
python tools/arcore_to_nerfstudio.py /path/to/session_YYYYMMDD_HHMMSS --output transforms.json
```

Then put `transforms.json` in the session folder and point Nerfstudio or your converter at it.

## Important coordinate note

ARCore uses an OpenGL-style camera convention. NeRF and Gaussian Splatting tools vary in what camera-to-world convention they expect. The included converter preserves the ARCore matrix by default and has a `--flip-yz` option for pipelines that need a coordinate adjustment.

Expect to test coordinate presets against your trainer.

## Roadmap

- Add live camera preview and capture UI polish
- Add exposure/focus locking
- Add capture-rate controls
- Add automatic ZIP export/share
- Add Nerfstudio/OpenCV/3DGS coordinate presets
- Add checker/debug viewer for camera frustums
- Add optional ARCore Recording API mode
- Add optional RealityCapture/COLMAP export bridge
