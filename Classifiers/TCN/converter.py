#  without select ops
import tensorflow as tf
from keras.models import load_model
from tcn import TCN

MODEL_TO_LOAD = "model.h5" # 읽어올 훈련 모델
LITE_MODEL_NAME = "spread.tflite" # 저장할 라이트 모델

model = load_model(MODEL_TO_LOAD, custom_objects={'TCN': TCN})

converter = tf.lite.TFLiteConverter.from_keras_model(model)
tfmodel = converter.convert()
open(LITE_MODEL_NAME, "wb") .write(tfmodel)
