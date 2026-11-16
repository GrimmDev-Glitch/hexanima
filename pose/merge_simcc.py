# RTMW whole-body pose (rtmw-dw-x-l 256x192, Apache-2.0, OpenMMLab) has two outputs: simcc_x [1,133,384] and simcc_y [1,133,512].
# The app's runner takes one output, so they are joined: "simcc" [1,133,896] = x then y. usage: python merge_simcc.py IN.onnx OUT.onnx
import sys, onnx
from onnx import helper, TensorProto
m = onnx.load(sys.argv[1]); g = m.graph
g.node.append(helper.make_node('Concat', ['simcc_x', 'simcc_y'], ['simcc'], axis=2, name='join_simcc'))
del g.output[:]
g.output.append(helper.make_tensor_value_info('simcc', TensorProto.FLOAT, [1, 133, 896]))
g.input[0].type.tensor_type.shape.dim[0].dim_value = 1
onnx.save(m, sys.argv[2]); print('ok')
