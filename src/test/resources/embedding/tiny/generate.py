"""Erzeugt das Spielzeugmodell für OnnxEmbeddingProviderTest (#89): Token-ID -> fester 4er-Vektor (Gather), dazu
token_type_ids und attention_mask als Eingaben ohne Einfluss, und einen WordLevel-Tokenizer mit <s> … </s>.

    python3 -m pip install onnx numpy && python3 generate.py
"""
import json, numpy as np, onnx
from onnx import helper, TensorProto, numpy_helper
vocab = {"<s>":0,"<pad>":1,"</s>":2,"<unk>":3,"query:":4,"passage:":5,"alpha":6,"beta":7,"gamma":8}
dim = 4
table = np.array([[(k+1)*(j+1)%7 - 3 + 0.5*j for j in range(dim)] for k in range(len(vocab))], dtype=np.float32)
ids = helper.make_tensor_value_info("input_ids", TensorProto.INT64, ["batch","seq"])
mask = helper.make_tensor_value_info("attention_mask", TensorProto.INT64, ["batch","seq"])
tt = helper.make_tensor_value_info("token_type_ids", TensorProto.INT64, ["batch","seq"])
out = helper.make_tensor_value_info("last_hidden_state", TensorProto.FLOAT, ["batch","seq",dim])
w = numpy_helper.from_array(table, "table")
# token_type_ids und attention_mask fließen ein, ohne das Ergebnis zu ändern: (mask*0 + tt*0) als Float addiert
nodes = [
  helper.make_node("Gather", ["table","input_ids"], ["emb"], axis=0),
  helper.make_node("Add", ["attention_mask","token_type_ids"], ["mt"]),
  helper.make_node("Cast", ["mt"], ["mtf"], to=TensorProto.FLOAT),
  helper.make_node("Constant", [], ["zero"], value=numpy_helper.from_array(np.array(0, dtype=np.float32))),
  helper.make_node("Mul", ["mtf","zero"], ["z"]),
  helper.make_node("Unsqueeze", ["z","axes"], ["z3"]),
  helper.make_node("Add", ["emb","z3"], ["last_hidden_state"]),
]
axes = numpy_helper.from_array(np.array([-1], dtype=np.int64), "axes")
g = helper.make_graph(nodes, "tiny", [ids, mask, tt], [out], initializer=[w, axes])
m = helper.make_model(g, opset_imports=[helper.make_opsetid("", 13)])
m.ir_version = 8
onnx.checker.check_model(m)
onnx.save(m, "model.onnx")
tok = {"version":"1.0","truncation":None,"padding":None,"added_tokens":[
  {"id":0,"content":"<s>","single_word":False,"lstrip":False,"rstrip":False,"normalized":False,"special":True},
  {"id":1,"content":"<pad>","single_word":False,"lstrip":False,"rstrip":False,"normalized":False,"special":True},
  {"id":2,"content":"</s>","single_word":False,"lstrip":False,"rstrip":False,"normalized":False,"special":True},
  {"id":3,"content":"<unk>","single_word":False,"lstrip":False,"rstrip":False,"normalized":False,"special":True}],
 "normalizer":None,"pre_tokenizer":{"type":"WhitespaceSplit"},
 "post_processor":{"type":"TemplateProcessing","single":[{"SpecialToken":{"id":"<s>","type_id":0}},{"Sequence":{"id":"A","type_id":0}},{"SpecialToken":{"id":"</s>","type_id":0}}],
   "pair":[{"SpecialToken":{"id":"<s>","type_id":0}},{"Sequence":{"id":"A","type_id":0}},{"SpecialToken":{"id":"</s>","type_id":0}},{"Sequence":{"id":"B","type_id":0}},{"SpecialToken":{"id":"</s>","type_id":0}}],
   "special_tokens":{"<s>":{"id":"<s>","ids":[0],"tokens":["<s>"]},"</s>":{"id":"</s>","ids":[2],"tokens":["</s>"]}}},
 "decoder":None,"model":{"type":"WordLevel","vocab":vocab,"unk_token":"<unk>"}}
json.dump(tok, open("tokenizer.json","w"), indent=1)
print(table.tolist())
