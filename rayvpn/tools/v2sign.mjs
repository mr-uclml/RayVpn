// Signs an APK with APK Signature Scheme v2 only (no v1 JAR signature), which is
// all a minSdk 24 app needs, and keeps the APK byte size unchanged.
//
// usage: node v2sign.mjs in.apk out.apk key.pem cert.pem
import fs from 'fs';
import { V2Signer } from './apksig/V2Signer.js';
import { SigningKey } from './apksig/SigningKey.js';
import { createSigningBlock, createV2SignaturePair } from './apksig/ApkSigningBlock.js';
import { findEndOfCentralDirectory, writeUint32LE } from './apksig/ZipUtils.js';

const [, , inApk, outApk, keyFile, certFile] = process.argv;
if (!outApk) {
  console.error('usage: node v2sign.mjs in.apk out.apk key.pem cert.pem');
  process.exit(2);
}

const raw = new Uint8Array(fs.readFileSync(inApk));
const keyPem = fs.readFileSync(keyFile, 'utf8').replace(/\r\n/g, '\n').trim();
const certPem = fs.readFileSync(certFile, 'utf8').replace(/\r\n/g, '\n').trim();

const signingKey = SigningKey.fromPEM(keyPem, certPem);
const eocd = findEndOfCentralDirectory(raw);
if (!eocd) throw new Error('EOCD not found');
const cdOffset = eocd.centralDirectoryOffset;
const eocdOffset = eocd.recordOffset;

const signer = new V2Signer({ signingKey, digestAlgorithm: 'SHA-256' });
const { v2Value } = await signer.sign(raw, cdOffset, eocdOffset);
const block = createSigningBlock([createV2SignaturePair(v2Value)]);

const out = new Uint8Array(raw.length + block.length);
out.set(raw.subarray(0, cdOffset), 0);
out.set(block, cdOffset);
out.set(raw.subarray(cdOffset), cdOffset + block.length);

// the EOCD moved: point its central-directory offset at the new position
writeUint32LE(out, eocdOffset + block.length + 16, cdOffset + block.length);

fs.writeFileSync(outApk, Buffer.from(out));
console.log('signed', out.length, 'bytes (+' + block.length + ')');
