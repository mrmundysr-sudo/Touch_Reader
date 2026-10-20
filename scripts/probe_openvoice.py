"""
Feasibility probe: OpenVoice V2 tone-colour converter as a "make this voice sound like that reference" stage
after a fast base TTS (Supertonic). Measures model size, memory, CPU speed, ONNX exportability/parity, ONNX Runtime
speed (the runtime Android would use), and whether timbre moves towards the reference. Synthetic voices only.
"""
import json, os, resource, sys, time
import numpy as np
import torch

sys.path.insert(0, os.environ["OPENVOICE_SRC"])          # cloned repo (pinned commit)
from openvoice import utils                                # noqa: E402
from openvoice.models import SynthesizerTrn               # noqa: E402
from openvoice.mel_processing import spectrogram_torch    # noqa: E402
import librosa, soundfile                                  # noqa: E402
from huggingface_hub import HfApi, hf_hub_download         # noqa: E402

REFS = os.environ["REF_DIR"]; OUT = os.environ.get("PROBE_OUT", "probe-out"); os.makedirs(OUT, exist_ok=True)
rss = lambda: round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0)
res = {"cpu_count": os.cpu_count(), "torch": torch.__version__}

REPO = "myshell-ai/OpenVoiceV2"
rev = HfApi().model_info(REPO).sha; res["hf_revision"] = rev; print("HF_REVISION", rev)
ckpt_path = hf_hub_download(REPO, "converter/checkpoint.pth", revision=rev)
cfg_path = hf_hub_download(REPO, "converter/config.json", revision=rev)
res["checkpoint_bytes"] = os.path.getsize(ckpt_path); res["rss_start_mb"] = rss()

hps = utils.get_hparams_from_file(cfg_path)
SR = hps.data.sampling_rate; res["converter_sample_rate"] = SR
t0 = time.time()
model = SynthesizerTrn(len(getattr(hps, "symbols", [])), hps.data.filter_length // 2 + 1, n_speakers=hps.data.n_speakers, **hps.model)
model.eval()
state = torch.load(ckpt_path, map_location="cpu", weights_only=False)   # third-party pickle: CI only, never in the app
missing = model.load_state_dict(state["model"], strict=False)
res["load_s"] = round(time.time() - t0, 2); res["rss_after_load_mb"] = rss()
needed = {"ref_enc", "enc_q", "flow", "dec"}
res["params_m"] = {k: round(sum(p.numel() for p in v.parameters()) / 1e6, 2) for k, v in model.named_children() if k in needed}
res["params_total_m"] = round(sum(p.numel() for p in model.parameters()) / 1e6, 2)
res["missing_keys"] = len(missing.missing_keys); res["unexpected_keys"] = len(missing.unexpected_keys)
print("PARAMS", json.dumps(res["params_m"]), "total", res["params_total_m"], "ckpt_bytes", res["checkpoint_bytes"])

def spec_of(path_or_audio):
    a = librosa.load(path_or_audio, sr=SR)[0] if isinstance(path_or_audio, str) else path_or_audio
    y = torch.FloatTensor(a).unsqueeze(0)
    return spectrogram_torch(y, hps.data.filter_length, SR, hps.data.hop_length, hps.data.win_length, center=False), a

def extract_se(path):
    spec, _ = spec_of(path)
    with torch.no_grad():
        return model.ref_enc(spec.transpose(1, 2)).unsqueeze(-1)

def convert(audio, src_se, tgt_se, tau=0.3):
    spec, _ = spec_of(audio)
    with torch.no_grad():
        return model.voice_conversion(spec, torch.LongTensor([spec.size(-1)]), sid_src=src_se, sid_tgt=tgt_se, tau=tau)[0][0, 0].float().numpy()

refs = {"M1": os.path.join(REFS, "ref_M1.wav"), "F1": os.path.join(REFS, "ref_F1.wav")}
torch.set_num_threads(2)
t = time.time(); se = {v: extract_se(p) for v, p in refs.items()}; res["extract_se_s_per_clip"] = round((time.time() - t) / 2, 3)
res["se_bytes"] = int(se["M1"].numel() * 4); print("SE shape", tuple(se["M1"].shape), "bytes", res["se_bytes"])
src_audio = {v: librosa.load(p, sr=SR)[0] for v, p in refs.items()}

# --- PyTorch speed (14.5 s clips and a 58 s tiled clip) ---
convert(src_audio["M1"][:SR * 3], se["M1"], se["F1"])  # warm-up
outs, rows = {}, []
for name, (s, d) in {"M1_to_F1": ("M1", "F1"), "F1_to_M1": ("F1", "M1")}.items():
    t = time.time(); o = convert(src_audio[s], se[s], se[d]); dt = time.time() - t
    outs[name] = o; soundfile.write(os.path.join(OUT, f"convert_{name}.wav"), o, SR)
    rows.append({"case": name, "audio_s": round(len(o) / SR, 2), "conv_s": round(dt, 2), "rtf": round(dt / (len(o) / SR), 3)}); print("TORCH", json.dumps(rows[-1]))
long = np.tile(src_audio["M1"], 4); t = time.time(); o = convert(long, se["M1"], se["F1"]); dt = time.time() - t
rows.append({"case": "58s_tiled", "audio_s": round(len(o) / SR, 2), "conv_s": round(dt, 2), "rtf": round(dt / (len(o) / SR), 3)}); print("TORCH", json.dumps(rows[-1]))
res["torch_runs"] = rows; res["rss_after_torch_mb"] = rss()

# --- does timbre move? (same encoder that the converter uses: biased; plus an independent one if installable) ---
def emb_ov(a):
    spec, _ = spec_of(a)
    with torch.no_grad(): e = model.ref_enc(spec.transpose(1, 2)).reshape(-1).numpy()
    return e / (np.linalg.norm(e) + 1e-9)
cos = lambda a, b: round(float(np.dot(a, b)), 3)
sim = {}
for name, (s, d) in {"M1_to_F1": ("M1", "F1"), "F1_to_M1": ("F1", "M1")}.items():
    e = emb_ov(outs[name]); sim[name] = {"vs_source": cos(e, emb_ov(src_audio[s])), "vs_target": cos(e, emb_ov(src_audio[d]))}
res["similarity_openvoice_encoder"] = sim; print("SIM_OV", json.dumps(sim))
try:
    from resemblyzer import VoiceEncoder, preprocess_wav
    enc = VoiceEncoder("cpu")
    r = lambda a: enc.embed_utterance(preprocess_wav(librosa.resample(a, orig_sr=SR, target_sr=16000), source_sr=16000))
    sim2 = {}
    for name, (s, d) in {"M1_to_F1": ("M1", "F1"), "F1_to_M1": ("F1", "M1")}.items():
        e = r(outs[name]); sim2[name] = {"vs_source": cos(e, r(src_audio[s])), "vs_target": cos(e, r(src_audio[d]))}
    res["similarity_resemblyzer"] = sim2; print("SIM_RESEMBLYZER", json.dumps(sim2))
except Exception as e:
    res["resemblyzer_error"] = repr(e)[:200]; print("resemblyzer unavailable:", repr(e)[:200])

# --- ONNX export of the conversion path + ONNX Runtime speed/parity ---
try:
    import onnx, onnxruntime as ort
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
    spec, _ = spec_of(src_audio["M1"][:SR * 4]); sl = torch.LongTensor([spec.size(-1)])
    vc_path, se_path = os.path.join(OUT, "vc.onnx"), os.path.join(OUT, "se.onnx")
    torch.onnx.export(VC(model), (spec, sl, se["M1"], se["F1"]), vc_path, opset_version=17, input_names=["spec", "spec_len", "g_src", "g_tgt"],
                      output_names=["audio"], dynamic_axes={"spec": {2: "T"}, "audio": {2: "S"}}, dynamo=False)
    torch.onnx.export(SE(model), (spec.transpose(1, 2),), se_path, opset_version=17, input_names=["spec_t"], output_names=["se"],
                      dynamic_axes={"spec_t": {1: "T"}}, dynamo=False)
    res["onnx_bytes"] = {"vc": os.path.getsize(vc_path), "se": os.path.getsize(se_path)}; print("ONNX_BYTES", json.dumps(res["onnx_bytes"]))
    so = ort.SessionOptions(); so.intra_op_num_threads = 2
    vc = ort.InferenceSession(vc_path, so, providers=["CPUExecutionProvider"])
    full_spec, _ = spec_of(src_audio["M1"])
    feeds = {"spec": full_spec.numpy(), "spec_len": np.array([full_spec.size(-1)], dtype=np.int64), "g_src": se["M1"].numpy(), "g_tgt": se["F1"].numpy()}
    vc.run(None, feeds)  # warm-up
    t = time.time(); oo = vc.run(None, feeds)[0][0, 0]; dt = time.time() - t
    res["onnx_rtf"] = round(dt / (len(oo) / SR), 3); res["onnx_audio_s"] = round(len(oo) / SR, 2)
    # parity: enc_q adds random noise scaled by tau, so compare timbre statistics instead of samples
    eo = emb_ov(oo.astype(np.float32)); res["onnx_timbre_vs_target"] = cos(eo, emb_ov(src_audio["F1"])); res["onnx_timbre_vs_source"] = cos(eo, emb_ov(src_audio["M1"]))
    soundfile.write(os.path.join(OUT, "convert_M1_to_F1_onnx.wav"), oo, SR)
    sess = ort.InferenceSession(se_path, so, providers=["CPUExecutionProvider"])
    se_o = sess.run(None, {"spec_t": full_spec.transpose(1, 2).numpy()})[0]
    res["onnx_se_max_abs_diff"] = float(np.abs(se_o - se["M1"].numpy()).max())
    print("ONNX_RUN", json.dumps({k: res[k] for k in res if k.startswith("onnx_")}))
except Exception as e:
    import traceback; traceback.print_exc()
    res["onnx_error"] = repr(e)[:400]; print("ONNX FAILED:", repr(e)[:400])

res["peak_rss_mb_final"] = rss()
json.dump(res, open(os.path.join(OUT, "results.json"), "w"), indent=2)
print("PROBE_RESULT", json.dumps(res))
