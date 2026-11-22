"""Phone simulator for end-to-end tests of jarvis-phone-task (does nothing real).

  JARVIS_BROKER_HOST=<broker tailscale ip/name> JARVIS_BROKER_SSH=<ssh alias> python -I fake_phone.py [seconds]

Behaves like the app's protocol layer: verifies the HMAC on every task (drops
unsigned/forged ones silently), checks ts/ttl, dedupes ids (replays the stored
final result), answers accepted + a SIGNED echo result. Never executes anything.
Secrets are read over ssh into memory and never printed.
"""
import hashlib, hmac, json, os, subprocess, sys, time
import paho.mqtt.client as mqtt
from paho.mqtt.enums import CallbackAPIVersion

HOST = os.environ.get("JARVIS_BROKER_HOST") or sys.exit("set JARVIS_BROKER_HOST")
SSH = os.environ.get("JARVIS_BROKER_SSH") or sys.exit("set JARVIS_BROKER_SSH")
PORT = int(os.environ.get("JARVIS_BROKER_PORT", "1883"))
USER = os.environ.get("JARVIS_PHONE_USER", "androllm-phone")


def secret(name):
    return subprocess.check_output(["ssh", "-o", "BatchMode=yes", SSH,
                                    f"cat ~/.config/jarvis-phone-mqtt/{name}"], text=True).strip()


PW, KEY = secret("phone.pass"), bytes.fromhex(secret("task.key"))
finals, dropped = {}, 0


def sign(*parts):
    return hmac.new(KEY, "\n".join(str(p) for p in parts).encode(), hashlib.sha256).hexdigest()


def result(tid, status, text=""):
    ts = int(time.time() * 1000)
    return json.dumps({"v": 1, "id": tid, "status": status, "text": text, "ts": ts,
                       "sig": sign("androllm-result-v1", tid, status, ts, text)}, ensure_ascii=False)


c = mqtt.Client(CallbackAPIVersion.VERSION2, client_id="androllm-phone-sim", protocol=mqtt.MQTTv5)
c.username_pw_set(USER, PW)
c.will_set("jarvis/phone/status", json.dumps({"state": "offline"}), 1, True)


def on_connect(cl, ud, flags, rc, props):
    cl.subscribe("jarvis/phone/task", qos=1)
    cl.publish("jarvis/phone/status", json.dumps({"state": "online", "ts": int(time.time() * 1000)}), 1, True)
    print("sim connected", rc, flush=True)


def on_message(cl, ud, m):
    global dropped
    try:
        t = json.loads(m.payload)
        tid, ts, ttl, frm, text = t["id"], int(t["ts"]), int(t.get("ttl_s", 600)), t.get("from", "jarvis"), t["text"]
        ok = hmac.compare_digest(sign("androllm-task-v1", tid, ts, ttl, frm, text), str(t.get("sig", "")).lower())
    except (ValueError, KeyError, TypeError):
        ok = False
    if not ok:
        dropped += 1
        print("sim dropped unsigned/forged task", flush=True)
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
