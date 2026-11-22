"""Phone simulator for end-to-end tests of jarvis-phone-task (does nothing real).

  JARVIS_BROKER_HOST=<broker tailscale ip/name> JARVIS_BROKER_SSH=<ssh alias> python -I fake_phone.py [seconds]

Behaves like the app's protocol layer (v2): decrypts every task with the
HKDF-derived AES-256-GCM key (drops anything that does not decrypt —
plaintext, forged, wrong key), checks ts/ttl/from, dedupes ids (replays the
stored final result), answers accepted + an ENCRYPTED echo result. Never
executes anything. Secrets are read over ssh into memory and never printed.
"""
import base64, json, os, re, subprocess, sys, time
import paho.mqtt.client as mqtt
from paho.mqtt.enums import CallbackAPIVersion
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.exceptions import InvalidTag

HOST = os.environ.get("JARVIS_BROKER_HOST") or sys.exit("set JARVIS_BROKER_HOST")
SSH = os.environ.get("JARVIS_BROKER_SSH") or sys.exit("set JARVIS_BROKER_SSH")
PORT = int(os.environ.get("JARVIS_BROKER_PORT", "1883"))
USER = os.environ.get("JARVIS_PHONE_USER", "androllm-phone")


def secret(name):
    return subprocess.check_output(["ssh", "-o", "BatchMode=yes", SSH,
                                    f"cat ~/.config/jarvis-phone-mqtt/{name}"], text=True).strip()


def hkdf(master, info):
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=b"androllm-jarvislink", info=info.encode()).derive(master)


PW = secret("phone.pass")
_master = bytes.fromhex(secret("task.key"))
K_TASK, K_RESULT = hkdf(_master, "task-v2"), hkdf(_master, "result-v2")
del _master
finals, dropped = {}, 0


def result(tid, status, text=""):
    nonce = os.urandom(12)
    body = json.dumps({"id": tid, "status": status, "text": text, "ts": int(time.time() * 1000)}, ensure_ascii=False)
    ct = AESGCM(K_RESULT).encrypt(nonce, body.encode(), b"androllm/v2/result")
    return json.dumps({"v": 2, "kind": "result", "n": base64.b64encode(nonce).decode(), "c": base64.b64encode(ct).decode()})


c = mqtt.Client(CallbackAPIVersion.VERSION2, client_id="androllm-phone-sim", protocol=mqtt.MQTTv5)
c.username_pw_set(USER, PW)
c.will_set("jarvis/phone/status", json.dumps({"v": 2, "state": "offline"}), 1, True)


def on_connect(cl, ud, flags, rc, props):
    cl.subscribe("jarvis/phone/task", qos=1)
    cl.publish("jarvis/phone/status", json.dumps({"v": 2, "state": "online", "ts": int(time.time() * 1000)}), 1, True)
    print("sim connected", rc, flush=True)


def on_message(cl, ud, m):
    global dropped
    if m.retain:
        return
    try:
        env = json.loads(m.payload)
        assert env.get("v") == 2 and env.get("kind") == "task"
        t = json.loads(AESGCM(K_TASK).decrypt(base64.b64decode(env["n"]), base64.b64decode(env["c"]), b"androllm/v2/task"))
        tid, ts, ttl, frm, text = t["id"], int(t["ts"]), int(t.get("ttl_s", 600)), t.get("from", "jarvis"), t["text"]
        assert re.fullmatch(r"[A-Za-z0-9._-]{1,32}", frm)
    except (ValueError, KeyError, TypeError, AssertionError, InvalidTag):
        dropped += 1
        print("sim dropped a task that did not decrypt/validate", flush=True)
        return
    now = int(time.time() * 1000)
    if now > ts + ttl * 1000 + 120_000 or ts - now > 120_000:
        cl.publish("jarvis/phone/result", result(tid, "expired", "outside time window"), 1)
        return
    if tid in finals:
        cl.publish("jarvis/phone/result", finals[tid], 1)
        return
    cl.publish("jarvis/phone/result", result(tid, "accepted"), 1)
    time.sleep(1)
    finals[tid] = result(tid, "ok", "SIM echo: " + text)
    cl.publish("jarvis/phone/result", finals[tid], 1)
    print("sim answered", tid, flush=True)


c.on_connect, c.on_message = on_connect, on_message
c.connect(HOST, PORT, 60)
c.loop_start()
time.sleep(float(sys.argv[1]) if len(sys.argv) > 1 else 25)
c.publish("jarvis/phone/status", b"", 1, True).wait_for_publish(5)  # clear retained status
c.loop_stop()
c.disconnect()
print(f"sim done, dropped={dropped}", flush=True)
