import numpy as np
import struct, os

np.random.seed(42)
N = 8000

def generate_sample():
    scenario = np.random.choice(['normal','hr_low','hr_high','spo2_low','active','critical'], 
                                  p=[0.45, 0.10, 0.15, 0.10, 0.15, 0.05])
    if scenario == 'normal':
        hr    = np.random.randint(60, 100)
        spo2  = np.random.randint(97, 101)
        accel = np.random.uniform(9.5, 11.5)
        gyro  = np.random.uniform(0.0, 0.5)
    elif scenario == 'hr_low':
        hr    = np.random.randint(40, 60)
        spo2  = np.random.randint(95, 100)
        accel = np.random.uniform(9.5, 10.5)
        gyro  = np.random.uniform(0.0, 0.3)
    elif scenario == 'hr_high':
        hr    = np.random.randint(100, 130)
        spo2  = np.random.randint(95, 100)
        accel = np.random.uniform(12.0, 20.0)
        gyro  = np.random.uniform(0.5, 2.5)
    elif scenario == 'spo2_low':
        hr    = np.random.randint(65, 110)
        spo2  = np.random.randint(88, 95)
        accel = np.random.uniform(9.5, 12.0)
        gyro  = np.random.uniform(0.0, 1.0)
    elif scenario == 'active':
        hr    = np.random.randint(80, 120)
        spo2  = np.random.randint(96, 100)
        accel = np.random.uniform(14.0, 25.0)
        gyro  = np.random.uniform(1.0, 3.0)
    else:  # critical
        hr    = np.random.choice([np.random.randint(30,50), np.random.randint(120,150)])
        spo2  = np.random.randint(85, 92)
        accel = np.random.uniform(9.5, 15.0)
        gyro  = np.random.uniform(0.0, 2.0)

    spo2 = min(int(spo2), 100)

    hr_risk   = 0 if 60<=hr<=100 else (1 if (55<=hr<60 or 100<hr<=110) else (2 if (50<=hr<55 or 110<hr<=120) else 3))
    spo2_risk = 0 if spo2>=97 else (1 if spo2>=95 else (2 if spo2>=90 else 3))
    activity  = 0 if accel<10.5 else (1 if accel<13.0 else (2 if accel<18.0 else 3))
    overall   = min(3, max(hr_risk, spo2_risk))

    return [float(hr), float(spo2), float(accel), float(gyro)], [hr_risk, spo2_risk, activity, overall]

X, Y = zip(*[generate_sample() for _ in range(N)])
X = np.array(X, dtype=np.float32)
Y = np.array(Y, dtype=np.float32)

# Normalize
X_mean = X.mean(axis=0)
X_std  = X.std(axis=0) + 1e-8
X_norm = (X - X_mean) / X_std
Y_norm = Y / 3.0

# Network: 4 → 32 → 16 → 4
IN,H1,H2,OUT = 4,32,16,4
np.random.seed(1)
W1 = np.random.randn(IN,H1)*0.1; b1 = np.zeros(H1)
W2 = np.random.randn(H1,H2)*0.1; b2 = np.zeros(H2)
W3 = np.random.randn(H2,OUT)*0.1; b3 = np.zeros(OUT)

def relu(x): return np.maximum(0,x)
def sigmoid(x): return 1/(1+np.exp(-np.clip(x,-10,10)))

lr, bs = 0.005, 256
for epoch in range(500):
    idx = np.random.permutation(N)
    for i in range(0, N, bs):
        Xb = X_norm[idx[i:i+bs]]; Yb = Y_norm[idx[i:i+bs]]
        h1 = relu(Xb@W1+b1); h2 = relu(h1@W2+b2); out = sigmoid(h2@W3+b3)
        d  = 2*(out-Yb)*out*(1-out)/len(Xb)
        dW3=h2.T@d; db3=d.sum(0)
        dh2=d@W3.T*(h2>0); dW2=h1.T@dh2; db2=dh2.sum(0)
        dh1=dh2@W2.T*(h1>0); dW1=Xb.T@dh1; db1=dh1.sum(0)
        W3-=lr*dW3; b3-=lr*db3
        W2-=lr*dW2; b2-=lr*db2
        W1-=lr*dW1; b1-=lr*db1
    if epoch%100==0:
        h1=relu(X_norm@W1+b1); h2=relu(h1@W2+b2); out=sigmoid(h2@W3+b3)
        loss=((out-Y_norm)**2).mean()
        pred=np.round(out*3).astype(int)
        acc=(pred==Y.astype(int)).mean()
        print(f"Epoch {epoch}: loss={loss:.4f} acc={acc:.3f}")

# Final accuracy
h1=relu(X_norm@W1+b1); h2=relu(h1@W2+b2); out=sigmoid(h2@W3+b3)
pred=np.round(out*3).astype(int)
print(f"\nFinal accuracy: {(pred==Y.astype(int)).mean():.3f}")
print(f"Per-output acc: HR={( pred[:,0]==Y[:,0].astype(int)).mean():.3f}  SpO2={(pred[:,1]==Y[:,1].astype(int)).mean():.3f}  Act={(pred[:,2]==Y[:,2].astype(int)).mean():.3f}  Overall={(pred[:,3]==Y[:,3].astype(int)).mean():.3f}")

# Save binary model
def pack_array(arr):
    flat = arr.flatten().astype(np.float32)
    return struct.pack(f'{len(flat)}f', *flat)

with open('/home/halovie/AndroidStudioProjects/watchapp/app/src/main/assetshealth_model.bin', 'wb') as f:
    # Magic + version
    f.write(b'WAPP')
    f.write(struct.pack('I', 1))
    # Normalization params
    f.write(pack_array(X_mean))
    f.write(pack_array(X_std))
    # Layers
    for arr in [W1,b1,W2,b2,W3,b3]:
        sh = np.array(arr.shape, dtype=np.int32)
        f.write(struct.pack('I', len(sh)))
        f.write(sh.tobytes())
        f.write(pack_array(arr))

size = os.path.getsize('/home/halovie/AndroidStudioProjects/watchapp/app/src/main/assets/health_model.bin')
print(f"\nSaved: health_model.bin ({size} bytes = {size//1024} KB)")
