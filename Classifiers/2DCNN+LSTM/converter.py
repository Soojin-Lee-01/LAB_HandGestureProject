#  without select ops
import tensorflow as tf
from keras.models import load_model
from tcn import TCN

MODEL_TO_LOAD = "Classifiers/TCN/cnn_tcn_6gesture_lrspread.h5" # 읽어올 훈련 모델
LITE_MODEL_NAME = "cnn_tcn_6gesture_lrspread.tflite" # 저장할 라이트 모델

model = load_model(MODEL_TO_LOAD)

converter = tf.lite.TFLiteConverter.from_keras_model(model)
tfmodel = converter.convert()
open(LITE_MODEL_NAME, "wb") .write(tfmodel)
