# Real source separation handoff — isolated desktop research

## Recommended backend
Open-Unmix **UMX-HQ** (not the default UMX-L). Four independent learned estimators: vocals / drums / bass / other. Original weights official https://zenodo.org/records/3370489 Version 1.0.1 explicitly MIT-licensed as **weights**; the code is separately MIT. Commercial use and redistribution permitted subject to keeping copyright and license notices. `licenses/OPENUNMIX-MIT.txt` and package-shipped notices retained. Models are inference dependencies, NOT third-party sample audio. No training dataset audio or sample library downloaded. If the owner means “no obligations” to also prohibit model notice requirements, the specific policy decision is acceptance of these MIT model notices; do not silently recategorize them as CC0.

Official code revision fb672c9584997c2b05e148eeaa65b4c23ed4693b. Published implementation used: openunmix 1.3.0. Weight provenance, official MD5 and computed SHA256, exported graph hashes/operators: `weight-provenance.json` when export completes. Source official code LICENSE: https://github.com/sigsep/open-unmix-pytorch/blob/fb672c9584997c2b05e148eeaa65b4c23ed4693b/LICENSE

Demucs NOT cleared: current official maintained README at revision 829d21890023800713e5dd231c67e43ecc06069b explicitly says weight license unclear and 6-source likely CC-NC-BY. Do not infer MIT from code. https://github.com/adefossez/demucs/blob/829d21890023800713e5dd231c67e43ecc06069b/README.md#license
Spleeter/MDX not selected; no need to rely on unresolved weight licensing now that an explicit MIT checkpoint exists. UMX-L weights expressly noncommercial; never use default model selection.

## Files
- `prototype.py`: tested two-source ONNX vocals + residual proof; third-party ONNX was hash verified, HF revision 87f26a5b2ed19488b5e453059a0a1d530423fe4a, `vocals.onnx`. This is NOT four-stem.
- `export_four.py`: downloads four official MIT weights (bounded <37MB/file), verifies published MD5, exports ONNX opset 17, checks graph and PyTorch parity/dynamic-frame support.
- `onnx_export_wrapper.py`: MIT-noticed adaptation of published forward, avoiding `.data.shape` because legacy tracing otherwise silently fixes the example frame count. Real dynamic-frame test caught this; fixed wrapper keeps symbolic tensor shapes. Fixed batch=1, stereo=2.
- `models/{vocals,drums,bass,other}.onnx`: official-weight exports, FP32 ~35.6 MB each, ~142.5 MB total (check final provenance).
- `prototype_four.py`: four-model learned separation with shared STFT and normalized magnitude masks; no EM/Wiener refinement. Four-second chunks, one-second crossfade overlap, cancellation checks between chunks. Not fixed EQ; mask is predicted by three-layer bidirectional LSTM models.
- `outputs-four/{vocals,drums,bass,other}.wav`, `stem-manifest.json`, `test-report.json` once test completes.
- Reproduce: `venv/bin/python export_four.py` then `python3 prototype_four.py`.

## App contract
Input: authorized local decoded PCM at 44,100 Hz, interleaved stereo float32 [frames,2]. Decode/resample mono or other rates in host using a documented converter; preserve source rate metadata and an explicit mapping to project frames. No network user audio uploads. Reject Spotify links/protected/offline audio and torrent-sourced MP3s; user direct-file rights/origin confirmation required, not a fake automatic origin classifier.
Magnitude model input/output: `mag` -> `estimate`, float32 `[1,2,2049,T]`. Same mixture spectrogram goes to every stem graph. Periodic Hann 4096; hop1024; center reflect pad2048; real one-sided FFT. Models estimate nonnegative target magnitudes. Normalize four estimates to masks summing to 1, apply mixture complex phase, inverse STFT/window-overlap normalization, trim exactly to input frame count. Low-energy bins fall back to equal masks. This specific fast mask path differs from upstream Wiener/EM refinement and needs listening review.

Job states: decoding -> separating (completed chunks / total chunks, not invented time ETA) -> writing -> complete; failed/cancelled publish no complete manifest. Cancellation is checked between chunks. Persist original unchanged. Output manifest uses each stem's absolute local path, `rate`, `channels`, `frames`, `offsetFrames:0`, source reference and backend. Atomic completion publication recommended. No independent per-stem normalization. Quantized WAV sum differs by rounding; float result tested separately. All stem starts/lengths identical; map original offset to project timeline. Expose top solo tabs and separate editable track lanes only for actual outputs. `other` includes guitar/piano/etc.; no dedicated isolated guitar/piano claim.

## Android feasibility, NOT deployment proof
The graphs contain standard ONNX LSTM/MatMul/BatchNormalization/shape operators. ONNX Runtime CPU is the plausible Android path; use full CPU package or a custom reduced build with these operators enabled. Test the actual Android ABI/version, dynamic frame shape and all operators before claiming support. No assertion NNAPI/TFLite conversion works. Keeping FFT/complex STFT/ISTFT outside the neural graph avoids mobile complex-operator issues but requires correct host DSP implementation. Test reference DSP against this fixture.

Models are bidirectional/offline, not live source separation. Chunk/overlap/context affects quality; test long songs and seams. Desktop timing/RSS in JSON must NOT be reported as Android performance. Need physical-phone memory/heat/battery/cancel/storage/time benchmarks, audio listening, and project import roundtrip tests. Sequential session loading can bound model memory but costs I/O/time; no device measurements yet. Optional quantization is separate validation, not assumed working.

No app source, APK, emulator, external account, purchase, torrent or Spotify extraction touched. Generated input only. No instrument transcription in this pass.

## Verified completion (2:18 PM America/Regina)
All four official graphs exported and actual source separation ran successfully. `outputs-four/test-report.json` contains tested proof; `outputs-four/official-forward-parity.json` verifies modified export forward is exactly equal to the published implementation; `outputs-four/wav-metadata-checks.json` verifies saved files. All four graph operators are recorded in `weight-provenance.json`. Final model footprint 142,503,692 bytes. Four source WAVs actually exist; no app deployment is implied.
