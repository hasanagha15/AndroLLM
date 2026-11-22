"""Simulates the phone app over Tailscale: online status + LWT, ack + result. Runs for N seconds."""
import json, subprocess, sys, time
import paho.mqtt.client as mqtt
from paho.mqtt.enums import CallbackAPIVersion

pw = subprocess.check_output(["ssh", "-o", "BatchMode=yes", "pi-brain",
                              "cat ~/.config/jarvis-phone-mqtt/phone.pass"], text=True).strip()
seen = set()
c = mqtt.Client(CallbackAPIVersion.VERSION2, client_id="androllm-phone-sim", protocol=mqtt.MQTTv5)
c.username_pw_set("androllm-phone", pw)
c.will_set("jarvis/phone/status", json.dumps({"state": "offline"}), 1, True)

def on_connect(cl, ud, flags, rc, props):
    cl.subscribe("jarvis/phone/task", qos=1)
    cl.publish("jarvis/phone/status", json.dumps({"state": "online", "ts": int(time.time()*1000)}), 1, True)
    print("sim connected", rc, flush=True)

def on_message(cl, ud, m):
    t = json.loads(m.payload)
    tid = t["id"]
    if tid in seen:
        cl.publish("jarvis/phone/result", json.dumps({"v": 1, "id": tid, "status": "duplicate", "text": ""}), 1)
        return
    seen.add(tid)
    cl.publish("jarvis/phone/result", json.dumps({"v": 1, "id": tid, "status": "accepted"}), 1)
    time.sleep(1)
    cl.publish("jarvis/phone/result", json.dumps({"v": 1, "id": tid, "status": "ok",
                                                  "text": "SIM echo: " + t["text"]}, ensure_ascii=False), 1)
    print("sim answered", tid, flush=True)

c.on_connect, c.on_message = on_connect, on_message
c.connect("100.64.0.10", 1883, 30)
c.loop_start()
time.sleep(float(sys.argv[1]) if len(sys.argv) > 1 else 25)
# graceful: publish offline ourselves, then clear retained so no stale state is left
c.publish("jarvis/phone/status", b"", 1, True).wait_for_publish(5)
c.loop_stop(); c.disconnect()
