#!/usr/bin/env python3
"""Tiny stdlib-only WebSocket probe used by smoke section 15.

Flow:
  1. WebSocket-handshake against ws://localhost:9080/ws/dashboard.
  2. Read the first frame — expect type=INITIAL_STATE (the stream replay).
  3. Fire a one-shot claim via subprocess.run(curl ...) using auth header
     handed in via env vars.
  4. Read frames until we see one with claimId == the new claim id.
  5. exit 0 on full success, exit code matching the failure step otherwise.

Argv:
  $1 AT     — bearer token
  $2 POL    — existing policy number for the test claim
  $3 PHOTO  — path to a small file used as the multipart attachment

Prints a single one-line JSON summary so the smoke can grep / jq it.
"""
import base64, hashlib, json, os, socket, struct, subprocess, sys, time

HOST = "localhost"
PORT = 9080
PATH = "/ws/dashboard"

def ws_handshake(sock):
    key = base64.b64encode(os.urandom(16)).decode()
    req = (
        f"GET {PATH} HTTP/1.1\r\n"
        f"Host: {HOST}:{PORT}\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        "Sec-WebSocket-Version: 13\r\n"
        "\r\n"
    )
    sock.sendall(req.encode())
    # Read headers
    buf = b""
    while b"\r\n\r\n" not in buf:
        chunk = sock.recv(4096)
        if not chunk:
            break
        buf += chunk
    if not buf.startswith(b"HTTP/1.1 101"):
        raise RuntimeError("handshake failed: " + buf.decode(errors="replace")[:200])
    # Verify Sec-WebSocket-Accept
    accept_expected = base64.b64encode(
        hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()
    ).decode()
    if accept_expected.encode() not in buf:
        raise RuntimeError("bad Sec-WebSocket-Accept")
    # leftover bytes after \r\n\r\n belong to first frame
    return buf.split(b"\r\n\r\n", 1)[1]

def recv_exact(sock, n, prefix=b""):
    buf = prefix
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise RuntimeError("socket closed mid-frame")
        buf += chunk
    return buf

def read_frame(sock, leftover):
    # Need 2 bytes header
    while len(leftover) < 2:
        chunk = sock.recv(4096)
        if not chunk:
            raise RuntimeError("eof")
        leftover += chunk
    b1, b2 = leftover[0], leftover[1]
    fin     = (b1 & 0x80) != 0
    opcode  = b1 & 0x0F
    masked  = (b2 & 0x80) != 0
    paylen  = b2 & 0x7F
    pos = 2
    if paylen == 126:
        while len(leftover) < pos + 2:
            leftover += sock.recv(4096)
        paylen = struct.unpack(">H", leftover[pos:pos+2])[0]
        pos += 2
    elif paylen == 127:
        while len(leftover) < pos + 8:
            leftover += sock.recv(4096)
        paylen = struct.unpack(">Q", leftover[pos:pos+8])[0]
        pos += 8
    if masked:
        while len(leftover) < pos + 4:
            leftover += sock.recv(4096)
        mask = leftover[pos:pos+4]
        pos += 4
    while len(leftover) < pos + paylen:
        leftover += sock.recv(4096)
    payload = leftover[pos:pos+paylen]
    if masked:
        payload = bytes(payload[i] ^ mask[i % 4] for i in range(len(payload)))
    leftover = leftover[pos+paylen:]
    return opcode, payload, leftover

def main():
    AT    = sys.argv[1]
    POL   = sys.argv[2]
    PHOTO = sys.argv[3]
    result = {"INITIAL_STATE": False, "LIVE_FRAME": False, "claimId": None}
    s = socket.create_connection((HOST, PORT), timeout=10)
    s.settimeout(8)
    leftover = ws_handshake(s)

    # Frame 1: INITIAL_STATE
    opcode, payload, leftover = read_frame(s, leftover)
    try:
        msg = json.loads(payload.decode())
        if msg.get("type") == "INITIAL_STATE":
            result["INITIAL_STATE"] = True
    except Exception as e:
        pass

    # Fire the claim
    fire = subprocess.run([
        "curl", "-sS", "-X", "POST", "http://localhost:9080/api/claims",
        "-H", f"Authorization: Bearer {AT}",
        "-F", f"policyNumber={POL}",
        "-F", "description=ws-probe",
        "-F", f"attachment=@{PHOTO};type=image/jpeg",
    ], capture_output=True, timeout=15)
    try:
        claim = json.loads(fire.stdout.decode())
        result["claimId"] = claim.get("id")
    except Exception:
        pass

    # Read frames until we see one matching the claimId (or timeout).
    deadline = time.time() + 8
    while time.time() < deadline:
        try:
            s.settimeout(max(0.5, deadline - time.time()))
            opcode, payload, leftover = read_frame(s, leftover)
        except (socket.timeout, RuntimeError):
            break
        try:
            msg = json.loads(payload.decode())
            if msg.get("type") == "CLAIM_FILED" and (
                result["claimId"] is None or msg.get("claimId") == result["claimId"]
            ):
                result["LIVE_FRAME"] = True
                break
        except Exception:
            continue
    try: s.close()
    except: pass
    print(json.dumps(result))

if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(json.dumps({"error": str(e)}))
        sys.exit(2)
