#!/usr/bin/env python3
"""Independent APK Signature Scheme v2 verifier.

The digest algorithm was calibrated against an APK signed by the real
`apksigner` (see the repo build notes), so a MATCH here means the APK content,
the signing block and the RSA signature are all internally consistent.

  chunk digest   H(0xa5 || uint32le(chunkLength) || chunk)      (1 MB chunks)
  section digest H(0x5a || uint32le(chunkCount) || chunkDigests)
  segments       [entries][central directory][EOCD with cd-offset = block start]

Layout of the v2 block (apksig "length-prefixed sequence" has no count field):
  block value:   [u32 len][u32 signerLen][signer...]
  signer:        [u32 len][signedData] [u32 len][signatures] [u32 len][publicKey]
  signed data:   [u32 len][digests] [u32 len][certificates] [u32 len][attributes]
"""
import hashlib
import os
import struct
import subprocess
import sys
import tempfile

MAGIC = b"APK Sig Block 42"
SHA256_ALGOS = (0x0103, 0x0201, 0x0301)
V2_BLOCK_ID = 0x7109871A


def u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def u64(b, o):
    return struct.unpack_from("<Q", b, o)[0]


def seq(buf, off):
    n = u32(buf, off)
    return buf[off + 4:off + 4 + n], off + 4 + n


def items(buf):
    out, p = [], 0
    while p + 4 <= len(buf):
        content, p = seq(buf, p)
        out.append(content)
    return out


def find_eocd(data):
    i = data.rfind(b"PK\x05\x06")
    if i < 0:
        raise SystemExit("no EOCD")
    return i


def find_signing_block(data, cd_off):
    footer = cd_off - 24
    if footer < 0 or data[footer + 8:footer + 24] != MAGIC:
        return None
    size = u64(data, footer)
    start = footer + 24 - size - 8
    if start < 0 or u64(data, start) != size:
        return None
    return start, footer


def pairs_of(data, start, footer):
    p, out = start + 8, {}
    while p + 12 <= footer - 8:
        ln = u64(data, p)
        out[u32(data, p + 8)] = data[p + 12:p + 8 + ln]
        p += 8 + ln
    return out


def chunked_digest(segments, hash_name):
    H = getattr(hashlib, hash_name)
    chunks = []
    for seg in segments:
        for i in range(0, len(seg), 1024 * 1024):
            chunks.append(seg[i:i + 1024 * 1024])
    digests = [H(b"\xa5" + struct.pack("<I", len(c)) + c).digest() for c in chunks]
    return H(b"\x5a" + struct.pack("<I", len(digests)) + b"".join(digests)).digest()


def verify_signature(signed, sig, cert_der, algo):
    flag = "-sha256" if algo in SHA256_ALGOS else "-sha512"
    with tempfile.TemporaryDirectory() as tmp:
        cp, dp, sp, pp = (os.path.join(tmp, x) for x in ("c.der", "d.bin", "s.bin", "p.pem"))
        open(cp, "wb").write(cert_der)
        open(dp, "wb").write(signed)
        open(sp, "wb").write(sig)
        r = subprocess.run(["openssl", "x509", "-inform", "DER", "-in", cp, "-pubkey",
                            "-noout", "-out", pp], capture_output=True)
        if r.returncode != 0:
            return False, r.stderr.decode()[:200]
        r = subprocess.run(["openssl", "dgst", flag, "-verify", pp, "-signature", sp, dp],
                           capture_output=True)
        return r.returncode == 0, (r.stdout + r.stderr).decode().strip()[:200]


def verify(path, verbose=True):
    data = open(path, "rb").read()
    eocd = find_eocd(data)
    cd_size, cd_off = u32(data, eocd + 12), u32(data, eocd + 16)
    block = find_signing_block(data, cd_off)
    if not block:
        return False, "no APK signing block"
    start, footer = block
    pairs = pairs_of(data, start, footer)
    if V2_BLOCK_ID not in pairs:
        return False, "no v2 block (found %s)" % [hex(k) for k in pairs]

    signers, _ = seq(pairs[V2_BLOCK_ID], 0)
    signer, _ = seq(signers, 0)
    signed_data, off = seq(signer, 0)
    sigs, off = seq(signer, off)
    _pubkey, _ = seq(signer, off)

    sig_item = items(sigs)[0]
    sig_algo = u32(sig_item, 0)
    sig, _ = seq(sig_item, 4)
    digests_raw, off2 = seq(signed_data, 0)
    certs_raw, off2 = seq(signed_data, off2)
    digests = {}
    for item in items(digests_raw):
        d, _ = seq(item, 4)
        digests[u32(item, 0)] = d
    certs = items(certs_raw)

    if verbose:
        print("  signature algorithm 0x%04x, %d cert(s)" % (sig_algo, len(certs)))
    ok_sig, msg = verify_signature(signed_data, sig, certs[0], sig_algo)
    if verbose:
        print("  RSA signature over signed data:", "VALID" if ok_sig else "INVALID " + msg)

    hash_name = "sha256" if sig_algo in SHA256_ALGOS else "sha512"
    comment_len = struct.unpack_from("<H", data, eocd + 20)[0]
    eocd_copy = bytearray(data[eocd:eocd + 22 + comment_len])
    struct.pack_into("<I", eocd_copy, 16, start)
    segments = [data[0:start], data[cd_off:cd_off + cd_size], bytes(eocd_copy)]
    actual = chunked_digest(segments, hash_name)

    content = next((d for algo, d in digests.items()
                    if algo in (0x0103, 0x0104, 0x0201, 0x0202, 0x0301)), None)
    match = content == actual
    if verbose:
        print("  content digest (%s):" % hash_name, "MATCH" if match else "MISMATCH")
        if not match:
            print("    expected", content.hex())
            print("    actual  ", actual.hex())
    return ok_sig and match, "ok" if (ok_sig and match) else "failed"


if __name__ == "__main__":
    bad = False
    for p in sys.argv[1:]:
        print("==", p, "(%d bytes)" % os.path.getsize(p))
        ok, msg = verify(p)
        print("  =>", "VERIFIED" if ok else "FAILED (%s)" % msg)
        bad = bad or not ok
    sys.exit(1 if bad else 0)
