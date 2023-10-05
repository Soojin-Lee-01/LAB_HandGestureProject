import serial
import os
import time

SERIAL_COMPORT_CTRL = 'COM13'  ## Enhanced Com Port
SERIAL_COMPORT_DATA = 'COM12' ## Standard Com port

GESTURE_LABEL = "Spread" # 수집할 제스처의 레이블명
path_to_save = "../Data/raw/" # 데이터 저장 경로

import traceback

def every(delay, task):
  next_time = time.time() + delay
  while True:
    time.sleep(max(0, next_time - time.time()))
    try:
      task()
      return
    except Exception:
      traceback.print_exc()
      # in production code you might want to have this instead of course:
      # logger.exception("Problem while executing repetitive task.")
    # skip tasks if we are behind schedule:
    next_time += (time.time() - next_time) // delay * delay + delay
stop = False
def foo():
    global stop
    stop = True
    print("stop")

import threading
th = threading.Thread(target=lambda: every(60, foo))
th.start()

ser = serial.Serial(SERIAL_COMPORT_DATA, 921600)
recvMsg_list = []

print('Ready')
for i in range(3):
    print(i+1)
    time.sleep(1)
print('Start')


tstamp = time.time()
file_name = GESTURE_LABEL+"_"+str(tstamp)+".txt"
# 현재는 제스처 레이블명 뒤에 수집 시점의 timestamp를 찍어 파일명을 설정
# 파일 이름 변경 시에도 파일이름은 무조건 “(제스처 레이블명)_"으로 시작해야함.

f = open(os.path.join(path_to_save, file_name), 'w')
while not stop:
    if ser.readable():
        for c in ser.read():
            recvMsg_list.append(c)
            print(c)
            f.write('%d ' % c)
f.close()
exit()

