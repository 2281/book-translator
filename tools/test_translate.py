"""Сквозной тест перевода: tok-encode -> encoder -> greedy decoder -> tok-decode.
Именно этот путь будет выполняться на Android."""
import sys
import numpy as np
import onnxruntime as ort
from onnxruntime_extensions import get_library_path

MODEL_DIR = sys.argv[1] if len(sys.argv) > 1 else "tools/onnx-fp32"

PAD_ID = 62517
EOS_ID = 0
MAX_LEN = 40


def session(path, extensions=False):
    so = ort.SessionOptions()
    so.intra_op_num_threads = 4
    if extensions:
        so.register_custom_ops_library(get_library_path())
    return ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])


tok_enc = session("tools/tok-encode.onnx", extensions=True)
tok_dec = session("tools/tok-decode.onnx", extensions=True)
encoder = session(f"{MODEL_DIR}/encoder_model.onnx")
decoder = session(f"{MODEL_DIR}/decoder_model.onnx")

print("decoder inputs:", [(i.name, i.shape) for i in decoder.get_inputs()])


def translate(text: str) -> str:
    (ids,) = tok_enc.run(None, {"text": [text]})
    ids = ids.astype(np.int64)[None, :]  # [1, N]
    mask = np.ones_like(ids)
    (hidden,) = encoder.run(None, {"input_ids": ids, "attention_mask": mask})

    dec_ids = np.array([[PAD_ID]], dtype=np.int64)
    for _ in range(MAX_LEN):
        (logits,) = decoder.run(["logits"], {
            "input_ids": dec_ids,
            "encoder_hidden_states": hidden,
            "encoder_attention_mask": mask,
        })
        next_id = int(np.argmax(logits[0, -1]))
        if next_id == EOS_ID:
            break
        dec_ids = np.concatenate([dec_ids, [[next_id]]], axis=1)

    out = dec_ids[0, 1:].astype(np.int64)
    (text_out,) = tok_dec.run(None, {"ids": out})
    return text_out[0]


if __name__ == "__main__":
    import time
    for word in ["traveler", "journey", "house", "forest", "morning",
                 "understand", "smiled", "bridge"]:
        t0 = time.time()
        print(f"{word:15s} -> {translate(word)}   ({time.time() - t0:.2f}s)")
