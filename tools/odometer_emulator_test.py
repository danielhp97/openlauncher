#!/usr/bin/env python3
"""End-to-end check of OdometerService on an Android emulator or device.

Feeds GPS through a `gps` test provider (like a mock-location bridge app),
then checks DataStore after riding with another app in front, parking,
a force-stop (hard power cut) and a reboot. Needs a debug build installed
with location permission granted. Wipes nothing; reads the odometer delta.

Usage: python3 tools/odometer_emulator_test.py   (ADB env var overrides adb path)
"""
import math, os, random, struct, subprocess, sys, time

ADB = os.environ.get("ADB", "adb")
PKG = "com.openlauncher.app"
LAT0, LON0 = 41.15, -8.61
M_PER_DEG = 111_195.0


def adb(*args, check=True):
    r = subprocess.run([ADB, *args], capture_output=True)
    if check and r.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {r.stderr.decode()}")
    return r.stdout


def setup_provider():
    # Same path as a mock-location bridge app (GPS Connector) on the real unit
    adb("shell", "appops", "set", "com.android.shell", "android:mock_location", "allow")
    adb("shell", "cmd", "location", "providers", "add-test-provider", "gps",
        "--supportsSpeed", "--supportsBearing", "--supportsAltitude", "--requiresSatellite", check=False)
    adb("shell", "cmd", "location", "providers", "set-test-provider-enabled", "gps", "true")


def geo(north_m, east_m=0.0):
    lat = LAT0 + north_m / M_PER_DEG
    lon = LON0 + east_m / (M_PER_DEG * math.cos(math.radians(LAT0)))
    adb("shell", "cmd", "location", "providers", "set-test-provider-location", "gps",
        "--location", f"{lat:.7f},{lon:.7f}", "--accuracy", "5")


def stored_meters():
    """Parse the odometer_meters double out of the Preferences DataStore protobuf."""
    raw = adb("exec-out", "run-as", PKG, "cat", "files/datastore/odometer.preferences_pb", check=False)
    key = b"odometer_meters"
    i = raw.find(key)
    if i < 0:
        return 0.0
    j = raw.find(b"\x39", i + len(key))  # Value.double = field 7, wire type 1
    return struct.unpack("<d", raw[j + 1:j + 9])[0]


def service_running():
    out = adb("shell", "dumpsys", "activity", "services", PKG).decode()
    return "OdometerService" in out and "isForeground=true" in out


def ride(seconds, speed_mps, start_m):
    """One fix per second heading north; returns where we ended."""
    pos = start_m
    t0 = time.monotonic()
    for k in range(seconds):
        pos = start_m + k * speed_mps
        geo(pos)
        # keep a 1 Hz cadence regardless of adb latency
        time.sleep(max(0.0, t0 + k + 1 - time.monotonic()))
    return pos


results = []
def check(name, ok, detail):
    results.append(ok)
    print(f"{'PASS' if ok else 'FAIL'}  {name}: {detail}")


setup_provider()

# 0. Service up, put another app in front (like OsmAnd) so MainActivity is stopped
check("service runs in foreground", service_running(), "dumpsys")
adb("shell", "am", "start", "-a", "android.settings.SETTINGS")
time.sleep(2)
base = stored_meters()
print(f"      stored at start: {base:.1f} m")

# 1. Ride 90 s at 20 m/s (72 km/h) with Settings in front → expect ~1780 m (89 segments)
end = ride(90, 20.0, 0.0)
time.sleep(2)
after_ride = stored_meters()
gained = after_ride - base
# saves happen every 200 m, so up to ~200 m may still be unsaved
check("counts while another app is in front", 1500 <= gained <= 1800, f"+{gained:.0f} m stored (expected ~1780, minus ≤200 unsaved)")

# 2. Parked for 60 s with ±4 m jitter → no gain (wait out a 30 s save first)
random.seed(1)
for k in range(60):
    geo(end + random.uniform(-4, 4), random.uniform(-4, 4))
    time.sleep(1)
parked = stored_meters()
check("no phantom km while parked, ride flushed while parked", 1775 <= parked - base <= 1781,
      f"stored {parked - base:.0f} m total (Δ {parked - after_ride:+.0f} m while parked, only flushes of the earlier ride allowed)")

# Ride 15 s more, then a 30 s+ wait so the time-based save flushes
end = ride(15, 20.0, end + 0.0)
time.sleep(32)
geo(end)  # one fix to trigger the time-based save check
time.sleep(2)
before_kill = stored_meters()
print(f"      stored before power cut: {before_kill - base:.0f} m")

# 3. Hard power cut: force-stop gives no onDestroy/shutdown callbacks
adb("shell", "am", "force-stop", PKG)
time.sleep(2)
after_kill = stored_meters()
check("value survives hard kill", abs(after_kill - before_kill) < 0.01, f"{after_kill - base:.0f} m after force-stop")

# 4. Relaunch (clears the stopped state so BOOT_COMPLETED gets delivered), then reboot
adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
time.sleep(4)
adb("shell", "am", "start", "-a", "android.settings.SETTINGS")
adb("reboot")
adb("wait-for-device")
for _ in range(100):
    if adb("shell", "getprop", "sys.boot_completed", check=False).strip() == b"1":
        break
    time.sleep(3)
time.sleep(15)
setup_provider()
check("service starts after reboot", service_running(), "dumpsys after boot")
after_boot = stored_meters()
check("value survives reboot", after_boot >= after_kill - 0.01, f"{after_boot - base:.0f} m after reboot")

# 4b. With "Allow all the time", the boot-started service counts before the launcher
#     has ever been in front (e.g. going straight to CarPlay after power-on).
bg = b"ACCESS_BACKGROUND_LOCATION: granted=true" in adb("shell", "dumpsys", "package", PKG)
adb("shell", "am", "start", "-a", "android.settings.SETTINGS")
time.sleep(2)
geo(-1000.0)  # anchor
time.sleep(1)
ride(20, 20.0, -980.0)
time.sleep(32)
geo(-980.0 + 19 * 20.0)
time.sleep(2)
pre_launcher = stored_meters() - after_boot
if bg:
    check("counts after boot before the launcher opens", 380 <= pre_launcher <= 402,
          f"+{pre_launcher:.0f} m (expected ~400, background location granted)")
else:
    print(f"INFO  background location not granted: +{pre_launcher:.0f} m before the launcher opened (expected 0)")
after_boot += pre_launcher

# 5. On the unit OpenLauncher is home and opens at boot, which gives the service
#    while-in-use location. Emulate that, then ride with another app in front.
adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
time.sleep(4)
adb("shell", "am", "start", "-a", "android.settings.SETTINGS")
time.sleep(2)
geo(0.0)  # first fix after boot only anchors
time.sleep(1)
ride(30, 20.0, 20.0)
time.sleep(32)
geo(20.0 + 29 * 20.0)
time.sleep(2)
final = stored_meters()
check("continues counting from stored value after boot", 560 <= final - after_boot <= 600,
      f"+{final - after_boot:.0f} m after boot (expected ~600)")

print(f"\n{sum(results)}/{len(results)} checks passed; odometer now {final / 1000:.3f} km")
sys.exit(0 if all(results) else 1)
