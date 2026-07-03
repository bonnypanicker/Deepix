"""
Promotes an fp16 ONNX graph to fp32 so it runs correctly on onnxruntime's CPU provider
(fp16 matmuls overflow to NaN on CPU EP). For calibration only — the app keeps the fp16 model.
"""
import sys
import numpy as np
import onnx
from onnx import TensorProto, numpy_helper

FP16 = TensorProto.FLOAT16
FP32 = TensorProto.FLOAT


def retype_value(vi):
    t = vi.type.tensor_type
    if t.elem_type == FP16:
        t.elem_type = FP32


def convert(src, dst):
    model = onnx.load(src)
    g = model.graph

    # Initializers fp16 -> fp32
    new_inits = []
    for init in g.initializer:
        if init.data_type == FP16:
            arr = numpy_helper.to_array(init).astype(np.float32)
            new_inits.append(numpy_helper.from_array(arr, init.name))
        else:
            new_inits.append(init)
    del g.initializer[:]
    g.initializer.extend(new_inits)

    for vi in list(g.input) + list(g.output) + list(g.value_info):
        retype_value(vi)

    # Cast nodes targeting fp16 -> fp32
    for node in g.node:
        if node.op_type == "Cast":
            for attr in node.attribute:
                if attr.name == "to" and attr.i == FP16:
                    attr.i = FP32
        for attr in node.attribute:
            if attr.name == "value" and attr.t.data_type == FP16:
                arr = numpy_helper.to_array(attr.t).astype(np.float32)
                attr.t.CopyFrom(numpy_helper.from_array(arr, attr.t.name))

    onnx.save(model, dst)
    print(f"Saved fp32 model -> {dst}")


if __name__ == "__main__":
    convert(sys.argv[1], sys.argv[2])
