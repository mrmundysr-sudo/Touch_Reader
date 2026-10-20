"""
Builds the voice-cloning add-on ("converter pack") from OpenVoice V2's tone-colour converter (MIT):
  vc.onnx, se.onnx          the converter and its voice-fingerprint network, exported to ONNX (no pickle files in the app)
  source_embeddings.json    fingerprints of the ten built-in Supertonic voices
  converter_config.json     audio settings the Kotlin front-end must reproduce
  parity.json               (CI only) reference values from PyTorch to verify the Kotlin spectrogram and fingerprint
  LICENSE.txt, NOTICE.txt   the MIT licence and a modification notice
"""
import json, os, shutil, sys
import numpy as np
import torch

sys.path.insert(0, os.environ["OPENVOICE_SRC"])
from openvoice import utils                                # noqa: E402
from openvoice.models import SynthesizerTrn               # noqa: E402
from openvoice.mel_processing import spectrogram_torch    # noqa: E402
import librosa                                             # noqa: E402
from huggingface_hub import hf_hub_download                # noqa: E402

REFS, PACK = os.environ["REF_DIR"], os.environ["PACK_DIR"]
REPO, REV = "myshell-ai/OpenVoiceV2", os.environ.get("HF_REVISION", "f36e7edfe1684461a8343844af60babc2efbb727")
PRESETS = ["M1", "M2", "M3", "M4", "M5", "F1", "F2", "F3", "F4", "F5"]
os.makedirs(PACK, exist_ok=True)

ckpt = hf_hub_download(REPO, "converter/checkpoint.pth", revision=REV)
cfg = hf_hub_download(REPO, "converter/config.json", revision=REV)
hps = utils.get_hparams_from_file(cfg)
SR = hps.data.sampling_rate
model = SynthesizerTrn(len(getattr(hps, "symbols", [])), hps.data.filter_length // 2 + 1, n_speakers=hps.data.n_speakers, **hps.model)
model.eval()
state = torch.load(ckpt, map_location="cpu", weights_only=False)   # third-party pickle: build machine only, never shipped
res = model.load_state_dict(state["model"], strict=False)
assert not res.missing_keys, res.missing_keys

def spec_of(a):
    return spectrogram_torch(torch.FloatTensor(a).unsqueeze(0), hps.data.filter_length, SR, hps.data.hop_length, hps.data.win_length, center=False)

def fingerprint(a):
    with torch.no_grad():
        return model.ref_enc(spec_of(a).transpose(1, 2)).reshape(-1).numpy()

# 1) fingerprints of the built-in voices (from the same Supertonic audio path the app uses)
src = {}
for v in PRESETS:
    a = librosa.load(os.path.join(REFS, f"ref_{v}.wav"), sr=SR)[0]
    src[v] = [float(x) for x in fingerprint(a)]
json.dump(src, open(os.path.join(PACK, "source_embeddings.json"), "w"))

# 2) ONNX export (weight norm removed; dynamic time axis)
import onnx  # noqa: F401
class VC(torch.nn.Module):
    def __init__(self, m): super().__init__(); self.m = m
    def forward(self, spec, spec_len, g_src, g_tgt):
        return self.m.voice_conversion(spec, spec_len, sid_src=g_src, sid_tgt=g_tgt, tau=0.3)[0]
class SE(torch.nn.Module):
    def __init__(self, m): super().__init__(); self.m = m
    def forward(self, spec_t): return self.m.ref_enc(spec_t).unsqueeze(-1)
for mod in (model.dec, model.flow, model.enc_q, model.ref_enc):
    try: mod.remove_weight_norm()
    except Exception: pass
a4 = librosa.load(os.path.join(REFS, "ref_M1.wav"), sr=SR)[0][: SR * 4]
spec = spec_of(a4); sl = torch.LongTensor([spec.size(-1)])
g = torch.tensor(src["M1"]).reshape(1, -1, 1); g2 = torch.tensor(src["F1"]).reshape(1, -1, 1)
torch.onnx.export(VC(model), (spec, sl, g, g2), os.path.join(PACK, "vc.onnx"), opset_version=17,
                  input_names=["spec", "spec_len", "g_src", "g_tgt"], output_names=["audio"],
                  dynamic_axes={"spec": {2: "T"}, "audio": {2: "S"}}, dynamo=False)
torch.onnx.export(SE(model), (spec.transpose(1, 2),), os.path.join(PACK, "se.onnx"), opset_version=17,
                  input_names=["spec_t"], output_names=["se"], dynamic_axes={"spec_t": {1: "T"}}, dynamo=False)

# 3) config the Kotlin front-end reads
json.dump({"data": {"sampling_rate": SR, "filter_length": hps.data.filter_length, "hop_length": hps.data.hop_length, "win_length": hps.data.win_length}},
          open(os.path.join(PACK, "converter_config.json"), "w"))

# 4) reference values from PyTorch for the Kotlin parity test (3 s clip)
a3 = librosa.load(os.path.join(REFS, "ref_M1.wav"), sr=SR)[0][SR : SR * 4]
s3 = spec_of(a3)
json.dump({"sample_rate": SR, "audio": [float(x) for x in a3], "spec_shape": list(s3.shape), "spec": [float(x) for x in s3.numpy().reshape(-1)],
           "fingerprint": [float(x) for x in fingerprint(a3)]}, open(os.path.join(PACK, "parity.json"), "w"))

# 5) licence + notice
shutil.copy(os.path.join(os.environ["OPENVOICE_SRC"], "LICENSE"), os.path.join(PACK, "LICENSE.txt"))
open(os.path.join(PACK, "NOTICE.txt"), "w").write(
    "Voice-cloning add-on for Touch Reader.\n\n"
    "vc.onnx and se.onnx are the OpenVoice V2 tone-colour converter (https://github.com/myshell-ai/OpenVoice, weights "
    f"{REPO} revision {REV}), Copyright 2024 MyShell.ai, MIT License (see LICENSE.txt).\n"
    "MODIFICATION NOTICE: the original PyTorch checkpoint was converted to ONNX (weight normalisation folded, dynamic time axis) by "
    "scripts/build_converter_pack.py in the Touch Reader repository. source_embeddings.json holds voice fingerprints of Touch Reader's built-in voices.\n"
    "Only change the timbre of voices you own or have permission to use.\n")
print("PACK_FILES", sorted(os.listdir(PACK)))
