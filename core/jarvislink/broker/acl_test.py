"""Broker auth/ACL test for the androllm-phone link, run from the Acer over Tailscale.
Passwords are fetched over ssh into memory and never printed."""
import json, subprocess, sys, time, threading
import paho.mqtt.client as mqtt
from paho.mqtt.enums import CallbackAPIVersion
from paho.mqtt.properties import Properties
from paho.mqtt.packettypes import PacketTypes

HOST = sys.argv[1] if len(sys.argv) > 1 else "100.64.0.10"
PORT = 1883

def secret(name):
    return subprocess.check_output(
        ["ssh", "-o", "BatchMode=yes", "pi-brain", f"cat ~/.config/jarvis-phone-mqtt/{name}"],
        text=True).strip()

PHONE_PW = secret("phone.pass")
CTL_PW = secret("ctl.pass")
results = []

def check(name, ok, detail=""):
    results.append((name, ok))
    print(("PASS " if ok else "FAIL ") + name + (f"  [{detail}]" if detail else ""))

def mk(cid, user, pw, will=None):
    c = mqtt.Client(CallbackAPIVersion.VERSION2, client_id=cid, protocol=mqtt.MQTTv5)
    if user is not None:
        c.username_pw_set(user, pw)
    if will:
        c.will_set(*will)
    ev = {"conn": threading.Event(), "rc": None, "msgs": [], "suback": {}, "puback": {}}
    def on_connect(cl, ud, flags, rc, props):
        ev["rc"] = rc; ev["conn"].set()
    def on_message(cl, ud, m):
        ev["msgs"].append((m.topic, m.payload.decode(errors="replace"), m.retain))
    def on_subscribe(cl, ud, mid, rcs, props):
        ev["suback"][mid] = rcs
    def on_publish(cl, ud, mid, rc, props):
        ev["puback"][mid] = rc
    c.on_connect, c.on_message, c.on_subscribe, c.on_publish = on_connect, on_message, on_subscribe, on_publish
    return c, ev

def connect(c, ev, timeout=8):
    try:
        c.connect(HOST, PORT, keepalive=30)
    except Exception as e:
        return f"exc:{e}"
    c.loop_start()
    ev["conn"].wait(timeout)
    return ev["rc"]

# 1. anonymous refused
c, ev = mk("acltest-anon", None, None)
rc = connect(c, ev); c.loop_stop(); c.disconnect()
check("anonymous refused", rc is not None and not str(rc).startswith("exc") and rc.is_failure, str(rc))

# 2. wrong password refused
c, ev = mk("acltest-bad", "androllm-phone", "wrong-password")
rc = connect(c, ev); c.loop_stop(); c.disconnect()
check("wrong password refused", rc is not None and rc.is_failure, str(rc))

# 3. phone connects (with LWT) and ctl connects
phone, pev = mk("androllm-phone-acltest", "androllm-phone", PHONE_PW,
                will=("jarvis/phone/status", json.dumps({"state": "offline"}), 1, True))
rc = connect(phone, pev)
check("phone connects via Tailscale", rc is not None and not rc.is_failure, str(rc))
ctl, cev = mk("jarvis-ctl-acltest", "jarvis-phone-ctl", CTL_PW)
rc = connect(ctl, cev)
check("ctl connects", rc is not None and not rc.is_failure, str(rc))

_, m1 = phone.subscribe("jarvis/phone/task", qos=1)
_, m2 = phone.subscribe("homeassistant/#", qos=1)
_, m3 = phone.subscribe("#", qos=1)
_, m4 = ctl.subscribe("jarvis/phone/result", qos=1)
_, m5 = ctl.subscribe("jarvis/phone/status", qos=1)
_, m6 = ctl.subscribe("homeassistant/#", qos=1)
time.sleep(2)
def granted(ev, mid):
    rcs = ev["suback"].get(mid)
    return rcs is not None and not rcs[0].is_failure, rcs
ok, r = granted(pev, m1); check("phone may subscribe jarvis/phone/task", ok, str(r))
ok, r = granted(pev, m2); print("info: mosquitto grants wildcard SUBACK, filtering happens per message:", r)
ok, r = granted(cev, m4); check("ctl may subscribe jarvis/phone/result", ok, str(r))
# probe published by piassist outside jarvis/phone/ must never reach phone or ctl
subprocess.run(["ssh", "-o", "BatchMode=yes", "pi-brain", "docker exec mosquitto sh /tmp/probe.sh"], check=True)
time.sleep(2)
check("phone/ctl never receive foreign topic jarvis/acl_probe",
      not any(t == "jarvis/acl_probe" for t, _, _ in pev["msgs"] + cev["msgs"]))

# 4. task flow ctl -> phone
task = json.dumps({"id": "acltest-1", "text": "ping"})
info = ctl.publish("jarvis/phone/task", task, qos=1); info.wait_for_publish(5)
time.sleep(2)
got = [m for m in pev["msgs"] if m[0] == "jarvis/phone/task" and "acltest-1" in m[1]]
check("phone receives task", len(got) >= 1, str(len(got)) + " (2 = overlapping subs task + #)")
check("phone '#' sees nothing outside its ACL",
      all(t.startswith("jarvis/phone/") for t, _, _ in pev["msgs"]), str({t for t, _, _ in pev["msgs"]}))

# 5. result flow phone -> ctl
info = phone.publish("jarvis/phone/result", json.dumps({"id": "acltest-1", "status": "ok", "text": "pong"}), qos=1)
info.wait_for_publish(5); time.sleep(2)
got = [m for m in cev["msgs"] if m[0] == "jarvis/phone/result" and "acltest-1" in m[1]]
check("ctl receives result", len(got) == 1, str(len(got)))

# 6. phone publish to forbidden topics -> PUBACK not authorized
for topic in ("homeassistant/sensor/androllm_acl_test/config", "jarvis/phone/task"):
    info = phone.publish(topic, "x", qos=1); info.wait_for_publish(5); time.sleep(1)
    rc = pev["puback"].get(info.mid)
    check(f"phone publish to {topic} rejected", rc is not None and rc.is_failure, str(rc))
# ctl may not publish to HA either
info = ctl.publish("homeassistant/sensor/androllm_acl_test/config", "x", qos=1); info.wait_for_publish(5); time.sleep(1)
rc = cev["puback"].get(info.mid)
check("ctl publish to homeassistant/# rejected", rc is not None and rc.is_failure, str(rc))

# 7. status online retained + LWT on ungraceful drop
phone.publish("jarvis/phone/status", json.dumps({"state": "online"}), qos=1, retain=True).wait_for_publish(5)
time.sleep(1)
phone.loop_stop()
phone._sock.close()  # simulate ungraceful drop -> broker fires LWT after keepalive*1.5
deadline = time.time() + 60
while time.time() < deadline:
    if any(t == "jarvis/phone/status" and "offline" in p for t, p, _ in cev["msgs"]):
        break
    time.sleep(1)
check("LWT offline delivered on drop", any(t == "jarvis/phone/status" and "offline" in p for t, p, _ in cev["msgs"]))
ctl.loop_stop(); ctl.disconnect()

# clear retained status left by the test
c, ev = mk("androllm-phone-acltest2", "androllm-phone", PHONE_PW)
connect(c, ev); c.publish("jarvis/phone/status", b"", qos=1, retain=True).wait_for_publish(5); time.sleep(1)
c.loop_stop(); c.disconnect()

failed = [n for n, ok in results if not ok]
print(f"\n{len(results) - len(failed)}/{len(results)} passed")
sys.exit(1 if failed else 0)
