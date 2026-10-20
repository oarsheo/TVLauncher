#!/usr/bin/env python3
"""Pair with a Philips Android TV's JointSpace API (v6) and print the digest credentials.

  scripts/philips-pair.py <tv-ip> request      # the TV shows a PIN
  scripts/philips-pair.py <tv-ip> grant <PIN>  # prints user/password for local.properties
"""
import base64, hashlib, hmac, json, random, ssl, string, sys, urllib.request
from pathlib import Path

# Shared secret every JointSpace client uses to sign the PIN (public, from the TV's pairing protocol).
SECRET = base64.b64decode("ZmVay1EQVFOaZhwQ4Kv81ypLAZNczV9sG4KkseXWn1NEk6cXmPKO/MCa9sryslvLCFMnNe4Z4CPXzToowvhHvA==")
STATE = Path(__file__).with_name(".philips-pair.json")
CTX = ssl._create_unverified_context()


def device(dev_id):
    return {"device_name": "tvos", "device_os": "Android", "app_name": "TVOS Launcher",
            "type": "native", "app_id": "com.arshe.tvos", "id": dev_id}


def post(url, body, auth=None):
    handlers = [urllib.request.HTTPSHandler(context=CTX)]
    if auth:
        mgr = urllib.request.HTTPPasswordMgrWithDefaultRealm()
        mgr.add_password(None, url, *auth)
        handlers.append(urllib.request.HTTPDigestAuthHandler(mgr))
    req = urllib.request.Request(url, json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.build_opener(*handlers).open(req, timeout=10) as r:
        return json.loads(r.read() or b"{}")


def main():
    ip, step = sys.argv[1], sys.argv[2]
    base = f"https://{ip}:1926/6"
    if step == "request":
        dev_id = "".join(random.choices(string.ascii_letters + string.digits, k=16))
        res = post(f"{base}/pair/request", {"scope": ["read", "write", "control"], "device": device(dev_id)})
        print(res)
        STATE.write_text(json.dumps({"id": dev_id, **res}))
    elif step == "grant":
        st = json.loads(STATE.read_text())
        pin = sys.argv[3]
        sig = hmac.new(SECRET, (str(st["timestamp"]) + pin).encode(), hashlib.sha1).hexdigest()
        body = {"auth": {"auth_AppId": "1", "pin": pin, "auth_timestamp": st["timestamp"],
                         "auth_signature": base64.b64encode(sig.encode()).decode()},
                "device": device(st["id"])}
        print(post(f"{base}/pair/grant", body, auth=(st["id"], st["auth_key"])))
        print(f"jointspace.user={st['id']}\njointspace.pass={st['auth_key']}")
        STATE.unlink()


if __name__ == "__main__":
    main()
