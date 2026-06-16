"""Ad-hoc mDNS probe: browse for SyncStream's _syncstream._tcp service on the LAN.

Run from a machine on the same Wi-Fi as the master. Prints any service found,
with resolved addresses, port, and TXT records. Used to verify whether the
master's advertisement is visible LAN-wide, independent of the Android client.
"""
import sys
import time

from zeroconf import ServiceBrowser, ServiceListener, Zeroconf

SERVICE_TYPE = "_syncstream._tcp.local."


class Listener(ServiceListener):
    def add_service(self, zc: Zeroconf, type_: str, name: str) -> None:
        info = zc.get_service_info(type_, name)
        print(f"[ADD] {name}")
        if info:
            addrs = [a for a in info.parsed_addresses()]
            print(f"      addresses={addrs} port={info.port}")
            txt = {
                k.decode(errors="replace"): (v.decode(errors="replace") if v else None)
                for k, v in (info.properties or {}).items()
            }
            print(f"      txt={txt} server={info.server}")
        else:
            print("      (resolve returned no info)")
        sys.stdout.flush()

    def update_service(self, zc: Zeroconf, type_: str, name: str) -> None:
        print(f"[UPD] {name}")
        sys.stdout.flush()

    def remove_service(self, zc: Zeroconf, type_: str, name: str) -> None:
        print(f"[DEL] {name}")
        sys.stdout.flush()


def main() -> None:
    duration = int(sys.argv[1]) if len(sys.argv) > 1 else 30
    zc = Zeroconf()
    print(f"Browsing for {SERVICE_TYPE} for {duration}s ...")
    sys.stdout.flush()
    ServiceBrowser(zc, SERVICE_TYPE, Listener())
    try:
        time.sleep(duration)
    finally:
        zc.close()
    print("done.")


if __name__ == "__main__":
    main()
