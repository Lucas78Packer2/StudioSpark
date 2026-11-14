# Official license evidence

UMX-HQ weights (not inferred from code): https://zenodo.org/records/3370489
Publisher: Fabian-Robert Stöter, Antoine Liutkus / Inria. Version 1.0.1.
Description: "Weights of the UMX-HQ music separation model for pytorch."
Rights: "License: MIT License". Four published model files and MD5 hashes are recorded in weight-provenance.json. Official training provenance documented upstream: MUSDB18-HQ. No training audio downloaded. Express model publisher MIT grant is evidence; this is not a legal opinion or a claim that the underlying training recordings are public domain.

Code separately: https://github.com/sigsep/open-unmix-pytorch/blob/fb672c9584997c2b05e148eeaa65b4c23ed4693b/LICENSE
MIT Copyright (c) 2019 Inria (Fabian-Robert Stöter, Antoine Liutkus). Full notice retained in OPENUNMIX-MIT.txt and package distribution. MIT requires retaining copyright/license notices upon copies/substantial distribution; commercial use and redistribution allowed, no source-code disclosure/share-alike requirement. The generated/authorized input audio does not become MIT merely from inference; source-audio rights remain separate.

UMX-L, NOT selected: upstream README expressly says weights CC BY-NC-SA 4.0. Never use default umxl accidentally.

Demucs official README, commit 829d21890023800713e5dd231c67e43ecc06069b:
"The license of the weights is not clear: MusDB contains some data that were only usable for research purpose."
"The 6 source variant of htdemucs was trained only on Meta internal dataset, which likely means CC-NC-BY license is applicable". This is uncertainty, not a verified blanket license assignment; none downloaded or bundled.

Third-party preliminary vocals ONNX: https://huggingface.co/edgetools/umx-hq, revision 87f26a5b2ed19488b5e453059a0a1d530423fe4a; model card explicitly derived from MIT UMX-HQ weights; verified SHA256 3d05709ff7197bbd4a33aee759e4e82002acb491f41c94770227ab57507b6ccb. Final four-source path instead uses original official Zenodo weights converted locally.
