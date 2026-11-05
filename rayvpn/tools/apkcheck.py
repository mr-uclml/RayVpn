#!/usr/bin/env python3
"""Checks that an APK will actually install, not just that it is signed.

`apkverify.py` proves the signature; this proves the container. It catches the
rules the platform enforces silently - the phone only says "App not installed":

  * `resources.arsc` must be present at the root, stored uncompressed and
    aligned on a 4-byte boundary when targetSdk >= 30 (Android 11+);
  * every stored entry should be 4-byte aligned (what `zipalign 4` does) and an
    uncompressed native library must start on a page boundary;
  * the zip must be intact: CRCs, local headers, central directory and EOCD;
  * `AndroidManifest.xml` must be at the root and parse as binary XML;
  * an APK Signature Scheme v2 block must be present.

usage: python3 tools/apkcheck.py RayVPN.apk
exit code 0 when everything holds, 1 otherwise.
"""

import struct
import sys
import zipfile
import zlib

PROBLEMS = []
NOTES = []


def problem(text):
    PROBLEMS.append(text)


def note(text):
    NOTES.append(text)


# ── zip container ────────────────────────────────────────────────────────
def entry_offsets(data, info):
    """Returns (data_offset, method, compressed_size, uncompressed_size, crc)."""
    lf = info.header_offset
    if data[lf:lf + 4] != b'PK\x03\x04':
        return None
    (_ver, _flag, method, _t, _d, crc, csize, usize,
     name_len, extra_len) = struct.unpack('<HHHHHIIIHH', data[lf + 4:lf + 30])
    return lf + 30 + name_len + extra_len, method, csize, usize, crc


def check_zip(path):
    with open(path, 'rb') as fh:
        data = fh.read()
    if data[:2] != b'PK':
        problem('not a zip/APK file')
        return None, data
    eocd = data.rfind(b'PK\x05\x06')
    if eocd < 0:
        problem('no end-of-central-directory record')
        return None, data
    if eocd + 22 + struct.unpack('<H', data[eocd + 20:eocd + 22])[0] != len(data):
        problem('trailing bytes after the EOCD record (broken download?)')

    try:
        zf = zipfile.ZipFile(path)
    except Exception as exc:                                  # noqa: BLE001
        problem('zipfile cannot open the APK: %s' % exc)
        return None, data
    if zf.testzip() is not None:
        problem('CRC mismatch in %s' % zf.testzip())

    names = zf.namelist()
    if len(names) != len(set(names)):
        problem('duplicate entry names')
    if 'AndroidManifest.xml' not in names:
        problem('AndroidManifest.xml is missing from the root')
    if 'resources.arsc' not in names:
        problem('resources.arsc is missing from the root')

    for info in zf.infolist():
        geo = entry_offsets(data, info)
        if geo is None:
            problem('bad local header for %s' % info.filename)
            continue
        offset, method, csize, usize, crc = geo
        payload = data[offset:offset + csize]
        if method == 0:
            if len(payload) != usize:
                problem('stored entry %s has a wrong size' % info.filename)
            if zlib.crc32(payload) & 0xFFFFFFFF != crc:
                problem('stored entry %s failed its CRC check' % info.filename)
            if info.filename != 'resources.arsc' and offset % 4:
                note('%s is stored but not 4-byte aligned' % info.filename)
            if info.filename.endswith('.so') and offset % 4096:
                note('%s is uncompressed but not page aligned' % info.filename)
    return zf, data


# ── binary manifest (only what we need: uses-sdk) ────────────────────────
def read_string_pool(buf, start):
    (_t, _hs, _size, count, _styles, flags, strings_start, _ss) = struct.unpack(
        '<HHIIIIII', buf[start:start + 28])
    utf8 = bool(flags & 0x100)
    offsets = struct.unpack('<%dI' % count, buf[start + 28:start + 28 + 4 * count])
    base = start + strings_start
    out = []
    for off in offsets:
        pos = base + off
        if utf8:
            n = buf[pos]
            pos += 2 if n & 0x80 else 1
            length = buf[pos]
            pos += 2 if length & 0x80 else 1
            out.append(buf[pos:pos + length].decode('utf-8', 'replace'))
        else:
            length = struct.unpack('<H', buf[pos:pos + 2])[0]
            pos += 2
            out.append(buf[pos:pos + length * 2].decode('utf-16-le', 'replace'))
    return out


def manifest_info(zf):
    buf = zf.read('AndroidManifest.xml')
    if struct.unpack('<H', buf[0:2])[0] != 0x0003:
        problem('AndroidManifest.xml is not binary XML')
        return None
    info = {}
    strings = []
    pos = 8
    while pos + 8 <= len(buf):
        ctype, header_size, size = struct.unpack('<HHI', buf[pos:pos + 8])
        if size <= 0:
            break
        if ctype == 0x0001:                                # string pool
            strings = read_string_pool(buf, pos)
            pos += size
            continue
        if ctype == 0x0180:                                # resource map
            pos += size
            continue
        if ctype == 0x0102:                                # start element
            name_idx = struct.unpack('<I', buf[pos + 20:pos + 24])[0]
            if strings[name_idx] == 'uses-sdk':
                attr_start = struct.unpack('<H', buf[pos + 24:pos + 26])[0]
                attr_size = struct.unpack('<H', buf[pos + 26:pos + 28])[0]
                count = struct.unpack('<H', buf[pos + 28:pos + 30])[0]
                for i in range(count):
                    a = pos + 16 + attr_start + i * attr_size
                    idx = struct.unpack('<I', buf[a + 4:a + 8])[0]
                    raw = struct.unpack('<I', buf[a + 8:a + 12])[0]
                    data_type = buf[a + 15]
                    value = struct.unpack('<I', buf[a + 16:a + 20])[0]
                    if strings[idx] in ('minSdkVersion', 'targetSdkVersion'):
                        if data_type == 0x10:              # int
                            info[strings[idx]] = value
                        elif data_type == 0x03 and raw:    # string
                            info[strings[idx]] = strings[raw]
        pos += size
    return info


# ── signature block ─────────────────────────────────────────────────────
def check_signature(data, zf):
    cd = zf.start_dir
    if data[cd - 16:cd] != b'APK Sig Block 42':
        problem('no APK Signing Block (v2/v3 signature missing)')
        return
    size = struct.unpack('<Q', data[cd - 24:cd - 16])[0]
    start = cd - 8 - size
    if start < 0 or struct.unpack('<Q', data[start:start + 8])[0] != size:
        problem('APK Signing Block has an inconsistent size field')
        return
    pos = start + 8
    ids = []
    while pos < cd - 24:
        length = struct.unpack('<Q', data[pos:pos + 8])[0]
        ids.append(struct.unpack('<I', data[pos + 8:pos + 12])[0])
        pos += 8 + length
    if 0x7109871A not in ids:
        problem('no v2 signature in the signing block')
    if 0xF05368C0 in ids or 0x1B93AD61 in ids:
        note('APK also carries a v3 signature')


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    path = argv[1]
    zf, data = check_zip(path)
    if zf is None:
        for text in PROBLEMS:
            print('  !', text)
        return 1

    sdk = manifest_info(zf) or {}
    target = sdk.get('targetSdkVersion')
    arsc = zf.getinfo('resources.arsc')
    geo = entry_offsets(data, arsc)
    if geo:
        offset, method, _c, _u, _crc = geo
        if method != 0:
            problem('resources.arsc is compressed (Android 11+ requires it stored)')
        elif target is not None and int(target) >= 30 and offset % 4:
            problem('resources.arsc is stored but not 4-byte aligned '
                    '(the -124 install failure on Android 11+)')

    check_signature(data, zf)

    print('%s' % path)
    print('  entries            : %d' % len(zf.infolist()))
    print('  min/target sdk      : %s / %s' % (sdk.get('minSdkVersion', '?'),
                                                sdk.get('targetSdkVersion', '?')))
    print('  resources.arsc      : %s, offset %% 4 = %d'
          % ('stored' if geo and geo[1] == 0 else 'compressed',
             geo[0] % 4 if geo else -1))
    for text in NOTES:
        print('  note:', text)
    if PROBLEMS:
        for text in PROBLEMS:
            print('  FAIL:', text)
        print('  => NOT INSTALLABLE')
        return 1
    print('  => INSTALLABLE')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
