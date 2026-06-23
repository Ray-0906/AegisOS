import sys
import time
import os

start = 1
chk = os.environ.get("AEGIS_CHECKPOINT_DIR", ".") + "/checkpoint.dat"

if os.path.exists(chk):
    with open(chk, "r") as f:
        content = f.read().strip()
        if content:
            start = int(content) + 1

for i in range(start, 5000):
    print(f"SEQ_{i}")
    sys.stdout.flush()
    with open(chk, "w") as f:
        f.write(str(i))
    time.sleep(0.5)
