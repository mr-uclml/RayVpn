/**
 * DigestUtils - Utilities for computing APK v2 content digests
 */
import { getSubtle } from './Crypto.js';
import { writeUint32LE } from './ZipUtils.js';
const ONE_MB = 1024 * 1024;
const CONTENT_DIGEST_CHUNKED_SHA256 = 0x0101;
const CONTENT_DIGEST_CHUNKED_SHA512 = 0x0102;
const SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256 = 0x0103;
const SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA512 = 0x0104;
/**
 * Compute the content digest for APK Signature Scheme v2
 */
export async function computeContentDigest(apkData, centralDirectoryOffset, eocdOffset, algorithm = 'SHA-256') {
    const subtle = await getSubtle();
    const digestSize = algorithm === 'SHA-256' ? 32 : 64;
    const contentDigestId = algorithm === 'SHA-256' ? CONTENT_DIGEST_CHUNKED_SHA256 : CONTENT_DIGEST_CHUNKED_SHA512;
    const signatureAlgorithmId = algorithm === 'SHA-256' ? SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256 : SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA512;
    const a = apkData.subarray(0, centralDirectoryOffset);
    const b = apkData.subarray(centralDirectoryOffset, eocdOffset);
    // EOCD for digest must have the original central directory offset
    const c = new Uint8Array(apkData.subarray(eocdOffset));
    writeUint32LE(c, 16, centralDirectoryOffset);
    const sectionHashes = [];
    for (const section of [a, b, c]) {
        for (let i = 0; i < section.length; i += ONE_MB) {
            const chunk = section.subarray(i, i + ONE_MB);
            const chunkLength = chunk.length;
            const prefixed = new Uint8Array(5 + chunkLength);
            prefixed[0] = 0xa5;
            writeUint32LE(prefixed, 1, chunkLength);
            prefixed.set(chunk, 5);
            const hash = await subtle.digest(algorithm, prefixed);
            sectionHashes.push(new Uint8Array(hash));
        }
    }
    const top = new Uint8Array(5 + sectionHashes.length * digestSize);
    top[0] = 0x5a;
    writeUint32LE(top, 1, sectionHashes.length);
    let offset = 5;
    for (const hash of sectionHashes) {
        top.set(hash, offset);
        offset += digestSize;
    }
    const digest = new Uint8Array(await subtle.digest(algorithm, top));
    return { algorithm, algorithmId: signatureAlgorithmId, digest };
}
//# sourceMappingURL=DigestUtils.js.map