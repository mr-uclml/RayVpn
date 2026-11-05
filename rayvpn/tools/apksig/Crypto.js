import crypto from 'node:crypto';
/**
 * Crypto - Cross-environment crypto helpers
 */
// vendored: node-forge import removed (only used by the v1/v3 signers we dropped)
let cachedSubtle;
function getGlobalCrypto() {
    if (typeof globalThis !== 'undefined' && globalThis.crypto) {
        return globalThis.crypto;
    }
    return undefined;
}
export async function getSubtle() {
    if (cachedSubtle)
        return cachedSubtle;
    const globalCrypto = getGlobalCrypto();
    if (globalCrypto?.subtle) {
        cachedSubtle = globalCrypto.subtle;
        return cachedSubtle;
    }
    throw new Error('Web Crypto API is not available in this environment');
}
function pemToDer(pem) {
    const b64 = pem.replace(/-----(BEGIN|END) (RSA )?PRIVATE KEY-----|\s/g, '');
    const binary = atob(b64);
    const len = binary.length;
    const bytes = new Uint8Array(len);
    for (let i = 0; i < len; i++) {
        bytes[i] = binary.charCodeAt(i);
    }
    return bytes.buffer;
}
export async function importRsaPrivateKey(privateKeyPem, hash = 'SHA-512') {
    const subtle = await getSubtle();
    return subtle.importKey('pkcs8', pemToDer(privateKeyPem), { name: 'RSASSA-PKCS1-v1_5', hash }, false, ['sign']);
}
export async function rsaSign(data, privateKeyPem, hash = 'SHA-512') {
    const subtle = await getSubtle();
    const key = await importRsaPrivateKey(privateKeyPem, hash);
    const sig = await subtle.sign('RSASSA-PKCS1-v1_5', key, data);
    return new Uint8Array(sig);
}
export function arrayBufferToBase64(buffer) {
    const bytes = buffer instanceof ArrayBuffer ? new Uint8Array(buffer) : buffer;
    const len = bytes.length;
    let binary = '';
    for (let i = 0; i < len; i++) {
        binary += String.fromCharCode(bytes[i]);
    }
    return btoa(binary);
}
export function base64ToArrayBuffer(base64) {
    const binary = atob(base64);
    const len = binary.length;
    const bytes = new Uint8Array(len);
    for (let i = 0; i < len; i++) {
        bytes[i] = binary.charCodeAt(i);
    }
    return bytes.buffer;
}
export function publicKeyDerFromCertificate(certPem) {
    // vendored: implemented with the node:crypto builtin instead of node-forge
    const der = crypto.createPublicKey(certPem).export({ type: 'spki', format: 'der' });
    return new Uint8Array(der);
}
//# sourceMappingURL=Crypto.js.map