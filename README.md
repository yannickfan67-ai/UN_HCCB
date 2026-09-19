# UN_HCCB

**UN HCCB** is an unofficial, clean-room Microsoft Tag / HCCB research toolkit and Android app.

It can generate 5×10 four-colour Microsoft Tag symbols and decode the numeric Tag ID offline. The historic Microsoft resolver service is gone, so decoding a Tag ID does **not** automatically recover the URL/content that Microsoft used to store server-side.

> Microsoft, Microsoft Tag and related marks belong to Microsoft. This project is not affiliated with or endorsed by Microsoft. No Microsoft proprietary APK/native binary is redistributed here.

## Android app

Current `0.1.0` features:

- Generate a 5×10 colour tag from `prefix + code`.
- Save generated symbols as PNG.
- Pick an image and decode the numeric Tag ID offline.
- Capture a photo through the system camera and decode it.
- CRC-16/X-25 validation, GF(32) RS parity verification and up to two colour-cell corrections.
- No network permission and no resolver dependency.

The Android image decoder is intentionally dependency-free and currently targets screenshots / fairly front-on photographs. For strong perspective correction, use the Python research tool in `tools/mstag_hccb_v4.py`.

## Codec format recovered so far

For a 5×10 colour Tag:

- 50 colour cells, symbol indices `0..3`.
- First 44 cells carry 11 encoded bytes.
- Cells 44–45 are fixed filler `[0,1]`.
- Cells 46–49 are calibration `[0,1,2,3]`.
- Payload is `CRC16-X25 (LE16) + prefix (LE16) + code (LE32)`.
- RS uses GF(32), primitive polynomial `x^5 + x^2 + 1`, `t=2`, four parity symbols.
- 11-byte block is whitened with the recovered XOR table before 2-bit colour packing.
- Print palette mapping: 0=yellow, 1=magenta, 2=black, 3=cyan.

## Build

The repository has no third-party runtime dependency. GitHub Actions builds the debug APK automatically.

```bash
gradle :app:assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Python research tool

```bash
python3 tools/mstag_hccb_v4.py self-test --rounds 5000
python3 tools/mstag_hccb_v4.py encode 1 9692 --png tag.png
python3 tools/mstag_hccb_v4.py decode-photo photo.jpg
```

## Status

This project is reverse-engineering / interoperability research. The numeric ID codec has been cross-checked against multiple historical Microsoft Tag images, but the old server-side ID → destination mapping was never embedded in the printed symbol.
