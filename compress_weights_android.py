import collections
import os

import numpy as np
import onnx
from onnx import helper, numpy_helper


INPUT_MODEL = "vision_model.onnx"
OUTPUT_MODEL = "vision_model_android_w8.onnx"


def mb(path: str) -> float:
    return os.path.getsize(path) / (1024 * 1024)


def quantize_int8(w: np.ndarray, axis: int | None) -> tuple[np.ndarray, np.ndarray, int]:
    w = w.astype(np.float32, copy=False)
    if axis is None:
        max_abs = float(np.max(np.abs(w))) if w.size else 0.0
        if max_abs == 0.0:
            return np.zeros_like(w, dtype=np.int8), np.array(1.0, dtype=np.float32), 0
        scale = np.array(max_abs / 127.0, dtype=np.float32)
        qw = np.round(w / scale).clip(-128, 127).astype(np.int8)
        return qw, scale, 0

    reduce_axes = tuple(i for i in range(w.ndim) if i != axis)
    max_abs = np.max(np.abs(w), axis=reduce_axes)
    scale_1d = (max_abs / 127.0).astype(np.float32)
    scale_1d = np.where(scale_1d == 0.0, 1.0, scale_1d).astype(np.float32)

    broadcast_shape = [1] * w.ndim
    broadcast_shape[axis] = scale_1d.shape[0]
    scale_b = scale_1d.reshape(broadcast_shape)
    qw = np.round(w / scale_b).clip(-128, 127).astype(np.int8)
    return qw, scale_1d, axis


def scan_ops(model: onnx.ModelProto) -> collections.Counter:
    return collections.Counter(node.op_type for node in model.graph.node)


def main() -> None:
    if not os.path.exists(INPUT_MODEL):
        raise SystemExit(f"Missing {INPUT_MODEL}")

    model = onnx.load(INPUT_MODEL)
    graph = model.graph

    initializer_by_name = {init.name: init for init in graph.initializer}
    value_info_names = {vi.name for vi in graph.value_info}
    existing_names = (
        set(initializer_by_name.keys())
        | {i.name for i in graph.input}
        | {o.name for o in graph.output}
        | value_info_names
        | {n.name for n in graph.node if n.name}
    )

    new_initializers: list[onnx.TensorProto] = []
    new_nodes: list[onnx.NodeProto] = []
    replaced_inits: set[str] = set()

    def unique(name: str) -> str:
        base = name
        i = 0
        while name in existing_names:
            i += 1
            name = f"{base}_{i}"
        existing_names.add(name)
        return name

    target_ops = {"Conv", "MatMul", "Gemm"}
    weight_input_index_by_op = {"Conv": 1, "MatMul": 1, "Gemm": 1}

    for node in graph.node:
        if node.op_type not in target_ops:
            continue
        w_idx = weight_input_index_by_op[node.op_type]
        if len(node.input) <= w_idx:
            continue
        w_name = node.input[w_idx]
        init = initializer_by_name.get(w_name)
        if init is None:
            continue
        if w_name in replaced_inits:
            node.input[w_idx] = f"{w_name}_deq"
            continue

        w = numpy_helper.to_array(init)
        if node.op_type == "Conv" and w.ndim == 4:
            qw, scale_arr, dq_axis = quantize_int8(w, axis=0)
        elif node.op_type in {"MatMul", "Gemm"} and w.ndim == 2:
            qw, scale_arr, dq_axis = quantize_int8(w, axis=1)
        else:
            qw, scale_arr, dq_axis = quantize_int8(w, axis=None)

        qw_name = unique(f"{w_name}_int8")
        scale_name = unique(f"{w_name}_scale")
        deq_out = unique(f"{w_name}_deq")

        new_initializers.append(numpy_helper.from_array(qw, name=qw_name))
        new_initializers.append(numpy_helper.from_array(scale_arr, name=scale_name))

        cast_out = unique(f"{w_name}_cast_f32")
        cast_node = helper.make_node(
            "Cast",
            inputs=[qw_name],
            outputs=[cast_out],
            name=unique(f"{w_name}_Cast"),
            to=1,
        )
        new_nodes.append(cast_node)

        if scale_arr.ndim == 0:
            mul_node = helper.make_node(
                "Mul",
                inputs=[cast_out, scale_name],
                outputs=[deq_out],
                name=unique(f"{w_name}_MulScale"),
            )
            new_nodes.append(mul_node)
        else:
            if node.op_type == "Conv" and w.ndim == 4:
                target_shape = np.array([scale_arr.shape[0], 1, 1, 1], dtype=np.int64)
            elif node.op_type in {"MatMul", "Gemm"} and w.ndim == 2:
                target_shape = np.array([1, scale_arr.shape[0]], dtype=np.int64)
            else:
                target_shape = np.array([scale_arr.shape[0]], dtype=np.int64)

            shape_name = unique(f"{w_name}_scale_shape")
            scale_b = unique(f"{w_name}_scale_b")
            new_initializers.append(numpy_helper.from_array(target_shape, name=shape_name))
            reshape_node = helper.make_node(
                "Reshape",
                inputs=[scale_name, shape_name],
                outputs=[scale_b],
                name=unique(f"{w_name}_ReshapeScale"),
            )
            new_nodes.append(reshape_node)

            mul_node = helper.make_node(
                "Mul",
                inputs=[cast_out, scale_b],
                outputs=[deq_out],
                name=unique(f"{w_name}_MulScale"),
            )
            new_nodes.append(mul_node)

        node.input[w_idx] = deq_out
        replaced_inits.add(w_name)

    kept_initializers = [init for init in graph.initializer if init.name not in replaced_inits]
    graph.ClearField("initializer")
    graph.initializer.extend(kept_initializers)
    graph.initializer.extend(new_initializers)

    graph_nodes = list(graph.node)
    graph.ClearField("node")
    graph.node.extend(new_nodes + graph_nodes)

    out_ops = scan_ops(model)
    print(
        "Operator summary:",
        {k: out_ops.get(k, 0) for k in ["Conv", "MatMul", "Gemm", "Cast", "Mul", "Reshape", "ConvInteger"]},
    )

    onnx.checker.check_model(model)
    onnx.save(model, OUTPUT_MODEL)
    print(f"Wrote: {OUTPUT_MODEL} ({mb(OUTPUT_MODEL):.1f} MB)")


if __name__ == "__main__":
    main()

