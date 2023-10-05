
import numpy as np
import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers
from matplotlib import pyplot as plt
import gc

from sklearn.model_selection import train_test_split
from Classifiers.utils.datasetLoader import dataset_load
from keras_flops import get_flops

extract_path = '../../NewData/forPointNet/TBO_ROInorm_4ch_aug0.01_sample64_tw1_p0.3/Test_'
model_save_subfix = extract_path.replace('../../NewData/forPointNet/', '')
model_save_subfix = model_save_subfix.replace('/Test_', '')

sub_dirs = ['downToUp', 'leftToRight', 'rightToLeft', 'Spread', 'upToDown']

NUM_POINTS = 64
NUM_CHANNELS = 4
NUM_CLASSES = len(sub_dirs)

# Dataset Load =====================================================================================================

test_points, test_labels = dataset_load(extract_path, sub_dirs, onehot=False)

print('Test Data Shape is:')
print(test_points.shape,test_labels.shape)

# test_dataset = tf.data.Dataset.from_tensor_slices((test_points, test_labels))
# test_dataset = test_dataset.shuffle(len(test_points)).batch(BATCH_SIZE)

# Evaluate =========================================================================================================
model_path = model_save_subfix+'.h5'

print("Load Model:", model_save_subfix)

new_model = keras.models.load_model(model_path)
new_model.summary()
flops = get_flops(new_model, batch_size=1)
print(f"FLOPS: {flops / 10 ** 9: .03} G")


tf.keras.backend.clear_session()  # 학습한 keras model session 종료
gc.collect()  # model 학습에 할당된 memory release

print("-- Evaluate --")
test_loss, test_acc = new_model.evaluate(test_points, test_labels,  verbose=2)
print(test_loss, test_acc)

# Confusion Metrix =================================================================================================
print("-- Confusion Metrix --")

print(test_points.shape[0])
y_pred = new_model.predict(test_points, 1)

from sklearn.metrics import confusion_matrix, ConfusionMatrixDisplay
import matplotlib.pyplot as plt

# 한글 폰트 사용을 위해서 세팅
from matplotlib import font_manager, rc
font_path = "C:/Windows/Fonts/NGULIM.TTF"
font = font_manager.FontProperties(fname=font_path).get_name()
rc('font', family=font)

y_true = test_labels.squeeze()
y_pred = np.argmax(y_pred, axis=1)
print(y_true.shape, y_pred.shape)

cm = confusion_matrix(y_true, y_pred, normalize='true')
disp = ConfusionMatrixDisplay(confusion_matrix=cm, display_labels=sub_dirs)
disp.plot(cmap=plt.cm.Blues)
plt.xticks(rotation=45)
plt.title(model_save_subfix)
plt.tight_layout()
plt.show()