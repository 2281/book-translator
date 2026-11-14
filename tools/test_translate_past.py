"""Тест перевода с KV-кэшем: первый шаг — decoder_model.onnx (выдаёт present.*),
остальные — decoder_with_past_model.onnx (один токен за прогон)."""
import sys
import time
import numpy as np
import onnxruntime as ort
from onnxruntime_extensions import get_library_path

MODEL_DIR = sys.argv[1] if len(sys.argv) > 1 else "tools/onnx-int8"

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
decoder_past = session(f"{MODEL_DIR}/decoder_with_past_model.onnx")


def translate(text: str) -> str:
    (ids,) = tok_enc.run(None, {"text": [text]})
    ids = ids.astype(np.int64)[None, :]
    mask = np.ones_like(ids)
    (hidden,) = encoder.run(None, {"input_ids": ids, "attention_mask": mask})

    # Шаг 0: полный прогон без кэша -> логиты + present.*
    outs = decoder.run(None, {
        "input_ids": np.array([[PAD_ID]], dtype=np.int64),
        "encoder_hidden_states": hidden,
        "encoder_attention_mask": mask,
    })
    names = [o.name for o in decoder.get_outputs()]
    logits = outs[names.index("logits")]
    past = {
        f"past_key_values.{n.split('.', 1)[1]}": v
        for n, v in zip(names, outs) if n.startswith("present.")
    }

    output = []
    next_id = int(np.argmax(logits[0, -1]))
    names_p = [o.name for o in decoder_past.get_outputs()]
    while next_id != EOS_ID and len(output) < MAX_LEN:
        output.append(next_id)
        outs = decoder_past.run(None, {
            "input_ids": np.array([[next_id]], dtype=np.int64),
            "encoder_attention_mask": mask,
            **past,
        })
        logits = outs[names_p.index("logits")]
        # обновляем только decoder self-attention кэш (encoder-кросс — из шага 0)
        past.update({
            f"past_key_values.{n.split('.', 1)[1]}": v
            for n, v in zip(names_p, outs) if n.startswith("present.")
        })
        next_id = int(np.argmax(logits[0, -1]))

    ids_out = np.array(output, dtype=np.int64)
    (text_out,) = tok_dec.run(None, {"ids": ids_out})
    return text_out[0]


if __name__ == "__main__":
    words = ["traveler", "journey", "house", "forest", "morning",
             "understand", "smiled", "bridge"]
    translate("warmup")  # прогрев сессий
    total = 0.0
    for word in words:
        t0 = time.time()
        tr = translate(word)
        dt = time.time() - t0
        total += dt
        print(f"{word:15s} -> {tr}   ({dt:.2f}s)")
    print(f"среднее: {total / len(words):.2f}s")
