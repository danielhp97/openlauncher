#!/usr/bin/env python3
"""Fake GPS2IP (socket mode): serves RMC sentences like GPS2IP Lite on an iPhone.

Each fix is written twice, as GPS2IP does. Rides north at SPEED m/s for RIDE_S
seconds, then stands still with small jitter. Used to test PhoneGpsReceiver:
an emulator reaches the Mac's port as 10.0.2.2, which is its Wi-Fi gateway.

    python3 tools/fake_gps2ip.py [port] [ride_seconds] [speed_mps]
"""
import datetime, math, random, socket, sys, threading, time

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 11123
RIDE_S = int(sys.argv[2]) if len(sys.argv) > 2 else 60
SPEED = float(sys.argv[3]) if len(sys.argv) > 3 else 20.0
LAT0, LON0 = 41.529932, -8.426080   # Braga
M_PER_DEG = 111_195.0


def nmea(body):
    cs = 0
    for c in body:
        cs ^= ord(c)
    return f"${body}*{cs:02X}\r\n"


def rmc(t, lat, lon, speed_mps, course):
    def dm(v, deg_digits):
        a = abs(v)
        d = int(a)
        return f"{d:0{deg_digits}d}{(a - d) * 60:08.5f}"
    utc = datetime.datetime.now(datetime.timezone.utc)
    return nmea("GPRMC,{},A,{},{},{},{},{:.2f},{:.2f},{},003.1,W".format(
        utc.strftime("%H%M%S"), dm(lat, 2), "N" if lat >= 0 else "S",
        dm(lon, 3), "E" if lon >= 0 else "W", speed_mps / 0.514444, course,
        utc.strftime("%d%m%y")))


start = time.monotonic()


def position(elapsed):
    if elapsed < RIDE_S:
        north = elapsed * SPEED
        return LAT0 + north / M_PER_DEG, LON0, SPEED, 0.0
    north = RIDE_S * SPEED + random.uniform(-3, 3)
    east = random.uniform(-3, 3)
    lon = LON0 + east / (M_PER_DEG * math.cos(math.radians(LAT0)))
    return LAT0 + north / M_PER_DEG, lon, random.uniform(0, 0.4), 0.0


def serve(conn, addr):
    print(f"client {addr}", flush=True)
    try:
        while True:
            lat, lon, v, c = position(time.monotonic() - start)
            line = rmc(0, lat, lon, v, c).encode()
            conn.sendall(line + line)
            time.sleep(1.0 - (time.monotonic() - start) % 1.0)
    except OSError:
        print(f"client {addr} gone", flush=True)


srv = socket.socket()
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("0.0.0.0", PORT))
srv.listen()
print(f"fake GPS2IP on :{PORT}, riding {RIDE_S}s at {SPEED} m/s", flush=True)
while True:
    c, a = srv.accept()
    threading.Thread(target=serve, args=(c, a), daemon=True).start()
