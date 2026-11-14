"""Создание ONNX-графов токенизации/детокенизации SentencePiece с remap'ом
spm id <-> id модели (особенность Helsinki-NLP opus-mt: vocab.json задаёт
свою нумерацию, отличную от нумерации внутри .spm).

Проверка против эталона: transformers AutoTokenizer (HF)."""
import json
import numpy as np
import onnx
from onnx import helper, TensorProto
import onnxruntime as ort
import sentencepiece as spm
from onnxruntime_extensions import get_library_path

CONTRIB = "ai.onnx.contrib"
UNK_MODEL_ID = 1


def scalar_init(name, dtype, value):
    np_dtype = helper.tensor_dtype_to_np_dtype(dtype)
    arr = np.array(value, dtype=np_dtype)
    return helper.make_tensor(name, dtype, [], arr.flatten().tolist())


def build_tables():
    vocab = json.load(open("tools/hf/vocab.json"))
    src = spm.SentencePieceProcessor(model_file="tools/hf/source.spm")
    tgt = spm.SentencePieceProcessor(model_file="tools/hf/target.spm")

    # source: spm id -> id модели
    src_table = np.zeros(src.vocab_size(), dtype=np.int64)
    for i in range(src.vocab_size()):
        src_table[i] = vocab.get(src.id_to_piece(i), UNK_MODEL_ID)

    # target: id модели -> spm id
    tgt_table = np.zeros(len(vocab), dtype=np.int64)
    for i in range(tgt.vocab_size()):
        model_id = vocab.get(tgt.id_to_piece(i))
        if model_id is not None:
            tgt_table[model_id] = i
    return src_table, tgt_table


def save_encoder_graph(src_table, out_path):
    model_bytes = open("tools/hf/source.spm", "rb").read()
    table_init = helper.make_tensor(
        "spm_to_model", TensorProto.INT64, [len(src_table)], src_table.tolist())
    nodes = [
        helper.make_node(
            "SentencepieceTokenizer",
            inputs=["text", "nbest_size", "alpha", "add_bos", "add_eos", "reverse"],
            outputs=["spm_ids_i32", "indices"],
            domain=CONTRIB,
            model=model_bytes,
        ),
        helper.make_node("Cast", ["spm_ids_i32"], ["spm_ids"], to=TensorProto.INT64),
        helper.make_node("Gather", ["spm_to_model", "spm_ids"], ["token_ids"], axis=0),
    ]
    graph = helper.make_graph(
        nodes, "spm-encode",
        inputs=[helper.make_tensor_value_info("text", TensorProto.STRING, [None])],
        outputs=[helper.make_tensor_value_info("token_ids", TensorProto.INT64, [None])],
        initializer=[
            table_init,
            scalar_init("nbest_size", TensorProto.INT64, 0),
            scalar_init("alpha", TensorProto.FLOAT, 1.0),
            scalar_init("add_bos", TensorProto.BOOL, False),
            scalar_init("add_eos", TensorProto.BOOL, True),
            scalar_init("reverse", TensorProto.BOOL, False),
        ],
    )
    model = helper.make_model(graph, opset_imports=[
        helper.make_opsetid("", 18), helper.make_opsetid(CONTRIB, 1)])
    model.ir_version = 10
    onnx.checker.check_model(model)
    onnx.save_model(model, out_path)
    print("saved:", out_path)


def save_decoder_graph(tgt_table, out_path):
    model_bytes = open("tools/hf/target.spm", "rb").read()
    table_init = helper.make_tensor(
        "model_to_spm", TensorProto.INT64, [len(tgt_table)], tgt_table.tolist())
    nodes = [
        helper.make_node("Gather", ["model_to_spm", "ids"], ["spm_ids"], axis=0),
        helper.make_node(
            "SentencepieceDecoder",
            inputs=["spm_ids"],
            outputs=["text"],
            domain=CONTRIB,
            model=model_bytes,
        ),
    ]
    graph = helper.make_graph(
        nodes, "spm-decode",
        inputs=[helper.make_tensor_value_info("ids", TensorProto.INT64, [None])],
        outputs=[helper.make_tensor_value_info("text", TensorProto.STRING, [None])],
        initializer=[table_init],
    )
    model = helper.make_model(graph, opset_imports=[
        helper.make_opsetid("", 18), helper.make_opsetid(CONTRIB, 1)])
    model.ir_version = 10
    onnx.checker.check_model(model)
    onnx.save_model(model, out_path)
    print("saved:", out_path)


def run(graph_path, feeds):
    so = ort.SessionOptions()
    so.register_custom_ops_library(get_library_path())
    sess = ort.InferenceSession(graph_path, so, providers=["CPUExecutionProvider"])
    return sess.run(None, feeds)


if __name__ == "__main__":
    src_table, tgt_table = build_tables()
    save_encoder_graph(src_table, "tools/tok-encode.onnx")
    save_decoder_graph(tgt_table, "tools/tok-decode.onnx")

    from transformers import AutoTokenizer
    hf = AutoTokenizer.from_pretrained("Helsinki-NLP/opus-mt-en-ru")

    for text in ["traveler", "The morning sun rose slowly.", "don't", "red roof"]:
        ref = hf(text).input_ids
        (onnx_ids,) = run("tools/tok-encode.onnx", {"text": [text]})
        onnx_ids = onnx_ids.tolist()
        status = "OK " if onnx_ids == ref else "MISMATCH"
        print(f"{status} encode {text!r}: onnx={onnx_ids} ref={ref}")

    for ids, ref in [([8913], "discrimination"),
                     (hf("путешественник").input_ids, "путешественник")]:
        (decoded,) = run("tools/tok-decode.onnx", {"ids": np.array(ids, dtype=np.int64)})
        got = decoded[0]
        status = "OK " if got.strip("⁇ ") == ref or got == ref else "CHECK"
        print(f"{status} decode {ids} -> {got!r} (ожидали {ref!r})")
