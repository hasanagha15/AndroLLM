"""Broker auth/ACL test for the AndroLLM phone link (reproducible, no side effects on the phone).

Run from any tailnet machine with paho-mqtt>=2:
  JARVIS_BROKER_HOST=<broker tailscale ip or name> JARVIS_BROKER_SSH=<ssh alias of broker host> \
      python -I acl_test.py

Passwords are read over ssh from ~/.config/jarvis-phone-mqtt/{phone,ctl}.pass into
memory and never printed. Only UNSIGNED probe payloads are published to the task
topic: a real phone drops them without executing or answering. The test refuses
to run while a real phone reports online (its retained status would be touched).
"""
import json, os, subprocess, sys, threading, time
import paho.mqtt.client as mqtt
from paho.mqtt.enums import CallbackAPIVersion

HOST = os.environ.get("JARVIS_BROKER_HOST") or sys.exit("set JARVIS_BROKER_HOST")
SSH = os.environ.get("JARVIS_BROKER_SSH") or sys.exit("set JARVIS_BROKER_SSH")
PORT = int(os.environ.get("JARVIS_BROKER_PORT", "1883"))
PHONE_USER = os.environ.get("JARVIS_PHONE_USER", "androllm-phone")
CTL_USER = os.environ.get("JARVIS_CTL_USER", "jarvis-phone-ctl")
T_TASK, T_RESULT, T_STATUS = "jarvis/phone/task", "jarvis/phone/result", "jarvis/phone/status"


def secret(name):
    return subprocess.check_output(["ssh", "-o", "BatchMode=yes", SSH,
                                    f"cat ~/.config/jarvis-phone-mqtt/{name}"], text=True).strip()


PHONE_PW, CTL_PW = secret("phone.pass"), secret("ctl.pass")
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
    c.on_connect = lambda cl, ud, fl, rc, pr: (ev.__setitem__("rc", rc), ev["conn"].set())
    c.on_message = lambda cl, ud, m: ev["msgs"].append((m.topic, m.payload.decode(errors="replace"), m.retain))
    c.on_subscribe = lambda cl, ud, mid, rcs, pr: ev["suback"].__setitem__(mid, rcs)
    c.on_publish = lambda cl, ud, mid, rc, pr: ev["puback"].__setitem__(mid, rc)
    return c, ev


def connect(c, ev, timeout=8):
    c.connect(HOST, PORT, keepalive=30)
    c.loop_start()
    ev["conn"].wait(timeout)
    return ev["rc"]


def stop(c):
    c.loop_stop()
    c.disconnect()


# 0. never interfere with a real phone
ctl, cev = mk("acltest-ctl", CTL_USER, CTL_PW)
rc = connect(ctl, cev)
check("ctl connects", rc is not None and not rc.is_failure, str(rc))
ctl.subscribe([(T_RESULT, 1), (T_STATUS, 1)])
time.sleep(2)
status = [json.loads(p) for t, p, _ in cev["msgs"] if t == T_STATUS and p]
if status and status[-1].get("state") == "online":
    stop(ctl)
    sys.exit("a phone reports online — not running the ACL test (it would touch the phone's retained status)")

# 1-2. anonymous / wrong password
for name, user, pw in (("anonymous refused", None, None), ("wrong password refused", PHONE_USER, "wrong-password")):
    c, ev = mk("acltest-neg", user, pw)
    rc = connect(c, ev)
    stop(c)
    check(name, rc is not None and rc.is_failure, str(rc))

# 3. phone with LWT
phone, pev = mk("acltest-phone", PHONE_USER, PHONE_PW, will=(T_STATUS, json.dumps({"state": "offline"}), 1, True))
rc = connect(phone, pev)
check("phone connects", rc is not None and not rc.is_failure, str(rc))
_, mid = phone.subscribe(T_TASK, qos=1)
phone.subscribe("#", qos=1)  # mosquitto grants wildcard SUBACKs; filtering is per message
time.sleep(2)
rcs = pev["suback"].get(mid)
check("phone may subscribe to the task topic", rcs is not None and not rcs[0].is_failure, str(rcs))

# 4. task topic delivery (UNSIGNED probe: a real phone would drop it silently)
ctl.publish(T_TASK, json.dumps({"id": "acl-probe", "text": "acl probe — unsigned, never executed"}), qos=1).wait_for_publish(5)
time.sleep(2)
check("ctl -> phone delivery on the task topic", any(t == T_TASK and "acl-probe" in p for t, p, _ in pev["msgs"]))
check("phone sees nothing outside jarvis/phone/",
      all(t.startswith("jarvis/phone/") for t, _, _ in pev["msgs"]), str({t for t, _, _ in pev["msgs"]}))

# 5. result topic delivery (unsigned: jarvis-phone-task would ignore it)
phone.publish(T_RESULT, json.dumps({"id": "acl-probe", "status": "ok", "text": "probe"}), qos=1).wait_for_publish(5)
time.sleep(2)
check("phone -> ctl delivery on the result topic", any(t == T_RESULT and "acl-probe" in p for t, p, _ in cev["msgs"]))

# 6. forbidden publishes -> PUBACK Not authorized
for who, client, ev, topic in (
    ("phone", phone, pev, "homeassistant/sensor/acl_test/config"),
    ("phone", phone, pev, T_TASK),
    ("ctl", ctl, cev, "homeassistant/sensor/acl_test/config"),
    ("ctl", ctl, cev, "jarvis/acl_probe"),
    ("ctl", ctl, cev, T_RESULT),
):
    info = client.publish(topic, "x", qos=1)
    info.wait_for_publish(5)
    time.sleep(1)
    rc = ev["puback"].get(info.mid)
    check(f"{who} publish to {topic} rejected", rc is not None and rc.is_failure, str(rc))

# 7. LWT on ungraceful drop
phone.loop_stop()
phone._sock.close()
deadline = time.time() + 60
while time.time() < deadline and not any(t == T_STATUS and "offline" in p for t, p, _ in cev["msgs"]):
    time.sleep(1)
check("LWT offline delivered on drop", any(t == T_STATUS and "offline" in p for t, p, _ in cev["msgs"]))
stop(ctl)

# cleanup: clear the retained status left by the LWT
c, ev = mk("acltest-phone", PHONE_USER, PHONE_PW)
connect(c, ev)
c.publish(T_STATUS, b"", qos=1, retain=True).wait_for_publish(5)
time.sleep(1)
stop(c)

failed = [n for n, ok in results if not ok]
print(f"\n{len(results) - len(failed)}/{len(results)} passed")
sys.exit(1 if failed else 0)
