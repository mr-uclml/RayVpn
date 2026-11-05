/**
 * V2Signer - Implements APK Signature Scheme v2
 */
import { rsaSign, publicKeyDerFromCertificate } from './Crypto.js';
import { computeContentDigest } from './DigestUtils.js';
import { writeUint32LE, concatBytes } from './ZipUtils.js';
const APK_SIG_V2_BLOCK_ID = 0x7109871a;
export class V2Signer {
    signingKey;
    digestAlgorithm;
    additionalAttributes;
    constructor(options) {
        this.signingKey = options.signingKey;
        this.digestAlgorithm = options.digestAlgorithm ?? 'SHA-256';
        this.additionalAttributes = options.additionalAttributes ?? new Map();
    }
    async sign(apkData, centralDirectoryOffset, eocdOffset) {
        const { algorithmId, digest } = await computeContentDigest(apkData, centralDirectoryOffset, eocdOffset, this.digestAlgorithm);
        const certDer = certPemToDer(this.signingKey.getCertificate());
        const privateKey = this.signingKey.getPrivateKey();
        const publicKeyDer = publicKeyDerFromCertificate(this.signingKey.getCertificate());
        const digestRecord = concatBytes([
            writeU32(algorithmId),
            buildLengthPrefixed(digest)
        ]);
        const digestsField = buildLengthPrefixed(buildLengthPrefixed(digestRecord));
        const certRecord = buildLengthPrefixed(certDer);
        const certsField = buildLengthPrefixed(certRecord);
        const attrsContent = new Uint8Array(0);
        const attrsField = buildLengthPrefixed(attrsContent);
        const signedData = concatBytes([digestsField, certsField, attrsField]);
        const signature = await rsaSign(signedData, privateKey, this.digestAlgorithm);
        const sigRecord = concatBytes([
            writeU32(algorithmId),
            buildLengthPrefixed(signature)
        ]);
        const signaturesField = buildLengthPrefixed(buildLengthPrefixed(sigRecord));
        const publicKeyField = buildLengthPrefixed(publicKeyDer);
        const signer = concatBytes([
            buildLengthPrefixed(signedData),
            signaturesField,
            publicKeyField
        ]);
        const v2Value = concatBytes([
            writeU32(4 + signer.length),
            buildLengthPrefixed(signer)
        ]);
        return { v2Value };
    }
}
function certPemToDer(pem) {
    const b64 = pem.replace(/-----(BEGIN|END) CERTIFICATE-----|\s/g, '');
    const binary = atob(b64);
    const der = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
        der[i] = binary.charCodeAt(i);
    }
    return der;
}
function buildLengthPrefixed(data) {
    const result = new Uint8Array(4 + data.length);
    writeUint32LE(result, 0, data.length);
    result.set(data, 4);
    return result;
}
function writeU32(value) {
    const result = new Uint8Array(4);
    writeUint32LE(result, 0, value);
    return result;
}
//# sourceMappingURL=V2Signer.js.map