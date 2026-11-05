#!/usr/bin/env python3
"""Minimal, alignment-aware APK/zip writer.

`zipfile` cannot control where an entry starts, and the platform is picky about
that: from Android 11 on, an APK with `targetSdkVersion >= 30` must store
`resources.arsc` uncompressed **and** on a 4-byte boundary, otherwise the
install fails with

    -124: Failed parse during installPackageLI: Targeting R+ (version 30 and
    above) requires the resources.arsc of installed APKs to be stored
    uncompressed and aligned on a 4-byte boundary

(the phone only shows "App not installed"). Uncompressed native libraries must
additionally start on a page boundary. This writer pads the local extra field
with a proper 0xD935 padding record so every entry lands where it should, and
writes the central directory by hand so the offsets stay truthful.
"""

import struct
import sys
import zlib

PADDING_ID = 0xD935
FLAG_UTF8 = 0x800


def _dos_time(year=2024, month=1, day=1):
    return 0, ((year - 1980) << 9) | (month << 5) | day


class ApkWriter:
    """Writes entries in insertion order with per-entry alignment."""

    def __init__(self, path):
        self.path = path
        self.fh = open(path, 'wb')
        self.offset = 0
        self.entries = []
        self.central = []
        self.date, self.time = _dos_time()

    # ── helpers ──────────────────────────────────────────────────────────
    @staticmethod
    def alignment_for(name, method):
        if method != 0:              # deflated data needs no alignment
            return 0
        if name == 'resources.arsc':
            return 4
        if name.endswith('.so'):
            return 4096
        return 4

    def _extra_for(self, name_len, align):
        if not align:
            return b''
        base = self.offset + 30 + name_len
        # 0xD935 record needs at least its own 4-byte header
        pad = 4 + ((align - (base + 4) % align) % align)
        return struct.pack('<HH', PADDING_ID, pad - 4) + b'\0' * (pad - 4)

    def _write(self, data):
        self.fh.write(data)
        self.offset += len(data)

    # ── public api ───────────────────────────────────────────────────────
    def add(self, name, data, method=8):
        if isinstance(data, str):
            data = data.encode('utf-8')
        raw = data
        crc = zlib.crc32(raw) & 0xFFFFFFFF
        if method == 8:
            compressor = zlib.compressobj(9, zlib.DEFLATED, -15)
            payload = compressor.compress(raw) + compressor.flush()
        else:
            payload = raw
            method = 0

        name_b = name.encode('utf-8')
        extra = self._extra_for(len(name_b), self.alignment_for(name, method))
        flags = FLAG_UTF8

        header_offset = self.offset
        self._write(struct.pack('<IHHHHHIIIHH', 0x04034B50, 20, flags, method,
                                self.time, self.date, crc, len(payload), len(raw),
                                len(name_b), len(extra)))
        self._write(name_b)
        self._write(extra)
        assert self.alignment_for(name, method) in (0,) or self.offset % self.alignment_for(name, method) == 0, name
        self._write(payload)

        self.central.append(struct.pack('<IHHHHHHIIIHHHHHII', 0x02014B50, 20, 20,
                                        flags, method, self.time, self.date, crc,
                                        len(payload), len(raw), len(name_b), len(extra),
                                        0, 0, 0, 0, header_offset)
                              + name_b + extra)

    def add_file(self, name, path, method=8):
        with open(path, 'rb') as fh:
            self.add(name, fh.read(), method)

    def add_zip(self, path, keep_order=True):
        """Copies every entry of an existing zip, keeping its compression."""
        import zipfile
        with zipfile.ZipFile(path) as z:
            for info in z.infolist():
                if info.filename.endswith('/'):
                    continue
                self.add(info.filename, z.read(info.filename), info.compress_type)

    def close(self):
        cd_start = self.offset
        for record in self.central:
            self._write(record)
        cd_size = self.offset - cd_start
        self._write(struct.pack('<IHHHHIIH', 0x06054B50, 0, 0,
                                len(self.central), len(self.central),
                                cd_size, cd_start, 0))
        self.fh.close()
        return self.offset


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        print('usage: apkpack.py out.apk IN...   (IN = file.zip or name=path)')
        return 2
    writer = ApkWriter(argv[1])
    for item in argv[2:]:
        if '=' in item and not item.startswith('/'):
            name, path = item.split('=', 1)
            writer.add_file(name, path)
        else:
            writer.add_zip(item)
    size = writer.close()
    print('wrote %s (%d bytes, %d entries)' % (argv[1], size, len(writer.central)))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
