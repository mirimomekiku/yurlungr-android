"""Check 64-bit APK native libraries for 16 KB ELF, RELRO and ZIP alignment.

Usage: python tools/check-native-alignment.py path/to/app.apk [...]
Only the Python standard library is required. This does not replace device testing.
"""

import struct
import sys
import zipfile

PAGE = 16384


def check_apk(path):
    errors = []
    checked = 0
    with zipfile.ZipFile(path) as apk, open(path, "rb") as raw:
        for entry in apk.infolist():
            if not entry.filename.endswith(".so") or not entry.filename.startswith(
                ("lib/arm64-v8a/", "lib/x86_64/")
            ):
                continue
            checked += 1
            problems = []
            data = apk.read(entry)
            if data[:6] != b"\x7fELF\x02\x01":
                problems.append("expected little-endian ELF64")
            else:
                offset = struct.unpack_from("<Q", data, 32)[0]
                size, count = struct.unpack_from("<HH", data, 54)
                headers = [struct.unpack_from("<IIQQQQQQ", data, offset + i * size) for i in range(count)]
                loads = [h for h in headers if h[0] == 1]
                if not loads:
                    problems.append("no LOAD segments")
                for _, _, file_offset, address, _, _, _, alignment in loads:
                    if alignment < PAGE or alignment & (alignment - 1):
                        problems.append(f"LOAD alignment {alignment:#x}")
                    if (address - file_offset) % PAGE:
                        problems.append("LOAD address/offset mismatch")
                for h in headers:
                    if h[0] != 0x6474E552:  # PT_GNU_RELRO
                        continue
                    end = h[3] + h[6]
                    for load in loads:
                        if load[1] & 2 and load[3] <= h[3] < load[3] + load[6]:
                            if end % PAGE and end < load[3] + load[6]:
                                problems.append("RELRO shares its trailing page with writable data")
            if entry.compress_type == zipfile.ZIP_STORED:
                raw.seek(entry.header_offset)
                header = raw.read(30)
                name_size, extra_size = struct.unpack_from("<HH", header, 26)
                start = entry.header_offset + 30 + name_size + extra_size
                if start % PAGE:
                    problems.append(f"uncompressed ZIP offset {start:#x}")
            if problems:
                errors.append(f"{entry.filename}: {', '.join(problems)}")
            else:
                print(f"PASS {path}: {entry.filename}")
    if not checked:
        errors.append("no 64-bit native libraries found")
    for error in errors:
        print(f"FAIL {path}: {error}", file=sys.stderr)
    return not errors


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    results = [check_apk(path) for path in sys.argv[1:]]
    sys.exit(0 if all(results) else 1)
