/**
 * ApkSigningBlock - Handles APK Signing Block format
 * The APK Signing Block is inserted before the Central Directory
 * and contains the signature scheme v2 data
 */
import { writeUint32LE, writeUint64LE, concatBytes, readUint32LE } from './ZipUtils.js';
export const APK_SIGNING_BLOCK_MAGIC = new Uint8Array([
    0x41, 0x50, 0x4b, 0x20, 0x53, 0x69, 0x67, 0x20,
    0x42, 0x6c, 0x6f, 0x63, 0x6b, 0x20, 0x34, 0x32
]); // "APK Sig Block 42"
/**
 * Create an APK Signing Block from signing block pairs
 */
export function createSigningBlock(pairs) {
    // Build each pair: uint64 pairSize, uint32 id, value
    const pairBuffers = [];
    let pairsSize = 0;
    for (const pair of pairs) {
        const pairSize = 4 + pair.value.length;
        const pairBuf = new Uint8Array(8 + pairSize);
        writeUint64LE(pairBuf, 0, BigInt(pairSize));
        writeUint32LE(pairBuf, 8, pair.id);
        pairBuf.set(pair.value, 12);
        pairBuffers.push(pairBuf);
        pairsSize += 8 + pairSize;
    }
    // Block size excludes the first uint64 size field
    const blockSize = pairsSize + 8 + APK_SIGNING_BLOCK_MAGIC.length;
    const block = new Uint8Array(8 + blockSize);
    // First size
    writeUint64LE(block, 0, BigInt(blockSize));
    // Pairs
    let offset = 8;
    for (const pairBuf of pairBuffers) {
        block.set(pairBuf, offset);
        offset += pairBuf.length;
    }
    // Second size
    writeUint64LE(block, offset, BigInt(blockSize));
    offset += 8;
    // Magic
    block.set(APK_SIGNING_BLOCK_MAGIC, offset);
    return block;
}
/**
 * Parse an APK Signing Block
 */
export function parseSigningBlock(data, offset) {
    const blockSize = Number(readUint64LE(data, offset));
    // Verify magic at the end
    const magicOffset = offset + 8 + blockSize - APK_SIGNING_BLOCK_MAGIC.length;
    const magic = data.slice(magicOffset, magicOffset + APK_SIGNING_BLOCK_MAGIC.length);
    if (!arraysEqual(magic, APK_SIGNING_BLOCK_MAGIC)) {
        throw new Error('Invalid APK Signing Block magic');
    }
    const pairs = [];
    let currentOffset = offset + 8;
    const endOffset = offset + 8 + blockSize - 8 - APK_SIGNING_BLOCK_MAGIC.length;
    while (currentOffset < endOffset) {
        const pairSize = Number(readUint64LE(data, currentOffset));
        currentOffset += 8;
        const id = readUint32LE(data, currentOffset);
        currentOffset += 4;
        const value = data.slice(currentOffset, currentOffset + pairSize - 4);
        currentOffset += pairSize - 4;
        pairs.push({ id, value });
    }
    return { pairs };
}
/**
 * Create a signature scheme v2 block pair
 */
export function createV2SignaturePair(signedData) {
    return {
        id: 0x7109871a, // APK Signature Scheme v2 ID
        value: signedData
    };
}
export function createV3SignaturePair(signedData) {
    return {
        id: 0xf05368c0, // APK Signature Scheme v3 ID
        value: signedData
    };
}
/**
 * Read unsigned 64-bit little-endian integer
 */
function readUint64LE(data, offset) {
    const view = new DataView(data.buffer, data.byteOffset + offset, 8);
    return Number(view.getBigUint64(0, true));
}
/**
 * Compare two Uint8Arrays for equality
 */
function arraysEqual(a, b) {
    if (a.length !== b.length)
        return false;
    for (let i = 0; i < a.length; i++) {
        if (a[i] !== b[i])
            return false;
    }
    return true;
}
//# sourceMappingURL=ApkSigningBlock.js.map