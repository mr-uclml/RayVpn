#!/usr/bin/env python3
"""Unpacks the release signing material without `keytool`.

`keytool` ships with the JDK; this project builds with nothing but `java`,
`node`, `openssl` and `python3`, so the key is extracted here instead:

    python3 tools/jks.py app/rayvpn-release.jks rayvpn rayvpn-local out/

writes `out/key.pem` (PKCS#8 private key), `out/cert.pem` (leaf certificate)
and `out/chain.pem` (the whole chain) - exactly what tools/v2sign.mjs reads.

Both container formats in the wild are handled:
  * PKCS#12 (`.p12`/`.pfx`, and the PKCS#12 files people often name `.jks`),
    delegated to `openssl pkcs12`;
  * the real Sun JKS container, parsed and decrypted here. JKS protects the key
    with SHA-1 of the UTF-16BE password salted with a random 20-byte salt,
    XOR-folded over the PKCS#8 bytes with a SHA-1 checksum appended.
"""

import base64
import hashlib
import os
import re
import struct
import subprocess
import sys
import tempfile

MAGIC = 0xFEEDFEED
INTEGRITY_SALT = b'Mighty Aphrodite'   # literal string used by Sun's JKS


def _sha1(data):
    return hashlib.sha1(data).digest()


def _read_modified_utf(fh):
    length = struct.unpack('>H', fh.read(2))[0]
    return fh.read(length).decode('utf-8', 'replace')


def _decrypt_key(protected, password):
    pwd = password.encode('utf-16-be')
    salt, body, checksum = protected[:20], protected[20:-20], protected[-20:]
    plain = bytearray()
    digest = _sha1(pwd + salt)
    for i in range(0, len(body), 20):
        chunk = body[i:i + 20]
        plain += bytes(a ^ b for a, b in zip(chunk, digest))
        digest = _sha1(pwd + digest)
    if _sha1(pwd + bytes(plain)) != checksum:
        raise SystemExit('wrong key password (integrity check of the key failed)')
    return bytes(plain)


def _load_pkcs12(path, store_password):
    """PKCS#12 has no stdlib parser; openssl does the heavy lifting."""
    with tempfile.NamedTemporaryFile('w+', suffix='.pem', delete=False) as tmp:
        bundle_path = tmp.name
    try:
        subprocess.run(['openssl', 'pkcs12', '-in', path, '-passin',
                        'pass:' + store_password, '-nodes', '-out', bundle_path],
                       check=True, stderr=subprocess.PIPE)
        bundle = open(bundle_path).read()
    finally:
        os.unlink(bundle_path)
    key = re.search(r'-----BEGIN (?:RSA )?PRIVATE KEY-----.*?-----END (?:RSA )?PRIVATE KEY-----',
                    bundle, re.S)
    certs = re.findall(r'-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----', bundle, re.S)
    if not key or not certs:
        raise SystemExit('no key or certificate found in %s' % path)
    return key.group(0) + '\n', [c.strip() for c in certs]


def load(path, store_password, alias, key_password=None):
    """Returns (key PEM or PKCS#8 DER, [certificate PEM or DER, ...])."""
    key_password = key_password or store_password
    raw = open(path, 'rb').read()
    if raw[:1] == b'\x30':                     # DER SEQUENCE => PKCS#12
        return _load_pkcs12(path, store_password)
    magic, version, count = struct.unpack('>III', raw[:12])
    if magic != MAGIC:
        raise SystemExit('%s is not a JKS keystore' % path)
    fh = open(path, 'rb')
    fh.seek(12)
    found = None
    for _ in range(count):
        tag = struct.unpack('>I', fh.read(4))[0]
        name = _read_modified_utf(fh)
        fh.read(8)                                  # creation timestamp
        if tag == 1:                                # private key entry
            key_len = struct.unpack('>I', fh.read(4))[0]
            protected = fh.read(key_len)
            chain_len = struct.unpack('>I', fh.read(4))[0]
            certs = []
            for _ in range(chain_len):
                if version == 2:
                    _read_modified_utf(fh)          # certificate type
                cert_len = struct.unpack('>I', fh.read(4))[0]
                certs.append(fh.read(cert_len))
            if name == alias:
                found = (protected, certs)
        elif tag == 2:                              # trusted certificate entry
            if version == 2:
                _read_modified_utf(fh)
            cert_len = struct.unpack('>I', fh.read(4))[0]
            fh.read(cert_len)
        else:
            raise SystemExit('unknown JKS entry tag %d' % tag)
    fh.close()
    if found is None:
        raise SystemExit('alias "%s" not found in %s' % (alias, path))

    protected, certs = found
    # store integrity: SHA1(password || "Mighty Aphrodite" || keystore bytes)
    expected = raw[-20:]
    actual = _sha1(store_password.encode('utf-16-be') + INTEGRITY_SALT + raw[:-20])
    if actual != expected:
        raise SystemExit('wrong store password')
    return _decrypt_key(protected, key_password), certs


def _pem(der, label):
    body = base64.b64encode(der).decode('ascii')
    lines = [body[i:i + 64] for i in range(0, len(body), 64)]
    return '-----BEGIN %s-----\n%s\n-----END %s-----\n' % (label, '\n'.join(lines), label)


def main(argv):
    if len(argv) < 5:
        print(__doc__)
        return 2
    keystore, alias, store_pass = argv[1:4]
    outdir = argv[4] if len(argv) > 4 else '.'
    key_pass = argv[5] if len(argv) > 5 else store_pass
    key, certs = load(keystore, store_pass, alias, key_pass)
    if not certs:
        raise SystemExit('alias "%s" has no certificate' % alias)
    if isinstance(key, bytes):
        key = _pem(key, 'PRIVATE KEY')
    certs = [c if isinstance(c, str) else _pem(c, 'CERTIFICATE') for c in certs]

    os.makedirs(outdir, exist_ok=True)
    for name, text in (('key.pem', key), ('cert.pem', certs[0]),
                       ('chain.pem', ''.join(certs))):
        with open(os.path.join(outdir, name), 'w') as fh:
            fh.write(text)
    der = base64.b64decode(''.join(certs[0].splitlines()[1:-1]))
    print('signing key ready: %d certificate(s), cert sha256 %s'
          % (len(certs), hashlib.sha256(der).hexdigest()))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
