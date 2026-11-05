/**
 * ZipUtils - Utilities for ZIP/APK structure parsing and manipulation
 * Designed for browser-based operations
 */
import zlib from 'node:zlib';
const ZIP_END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50;
const ZIP_LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50;
const ZIP_CENTRAL_DIRECTORY_FILE_HEADER_SIGNATURE = 0x02014b50;
/**
 * Parse ZIP file and extract central directory information
 */
export function parseZipCentralDirectory(data) {
    const eocd = findEndOfCentralDirectory(data);
    if (!eocd) {
        throw new Error('Invalid ZIP file: End of Central Directory not found');
    }
    const entries = [];
    let cdOffset = eocd.centralDirectoryOffset;
    for (let i = 0; i < eocd.entryCount; i++) {
        const entry = parseCentralDirectoryEntry(data, cdOffset);
        entries.push(entry);
        cdOffset += entry.offset;
    }
    return {
        entries,
        offset: eocd.centralDirectoryOffset,
        size: eocd.centralDirectorySize
    };
}
/**
 * Find End of Central Directory record
 */
export function findEndOfCentralDirectory(data) {
    // The EOCD is at least 22 bytes. The comment can be up to 65535 bytes long.
    const minEocdOffset = Math.max(0, data.length - 22 - 65535);
    for (let offset = data.length - 22; offset >= minEocdOffset; offset--) {
        const signature = readUint32LE(data, offset);
        if (signature === ZIP_END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
            const commentLength = readUint16LE(data, offset + 20);
            if (offset + 22 + commentLength === data.length) {
                return {
                    recordOffset: offset,
                    centralDirectoryOffset: readUint32LE(data, offset + 16),
                    centralDirectorySize: readUint32LE(data, offset + 12),
                    entryCount: readUint16LE(data, offset + 8),
                    commentLength
                };
            }
        }
    }
    return null;
}
/**
 * Parse a Central Directory entry
 */
function parseCentralDirectoryEntry(data, offset) {
    const signature = readUint32LE(data, offset);
    if (signature !== ZIP_CENTRAL_DIRECTORY_FILE_HEADER_SIGNATURE) {
        throw new Error('Invalid Central Directory entry signature');
    }
    const nameLength = readUint16LE(data, offset + 28);
    const extraLength = readUint16LE(data, offset + 30);
    const commentLength = readUint16LE(data, offset + 32);
    const nameBytes = data.slice(offset + 46, offset + 46 + nameLength);
    const name = new TextDecoder().decode(nameBytes);
    const size = 46 + nameLength + extraLength + commentLength;
    return {
        name,
        offset: size,
        compressedSize: readUint32LE(data, offset + 20),
        uncompressedSize: readUint32LE(data, offset + 24),
        compressionMethod: readUint16LE(data, offset + 10),
        crc32: readUint32LE(data, offset + 16),
        localHeaderOffset: readUint32LE(data, offset + 42),
        nameLength,
        extraLength,
        commentLength,
        cdHeaderOffset: offset
    };
}
/**
 * Find Local File Header for an entry
 */
export function findLocalFileHeader(data, offset) {
    const signature = readUint32LE(data, offset);
    if (signature !== ZIP_LOCAL_FILE_HEADER_SIGNATURE) {
        throw new Error('Invalid Local File Header signature');
    }
    return offset;
}
/**
 * Get the data offset for a ZIP entry
 */
export function getEntryDataOffset(data, localHeaderOffset) {
    const nameLength = readUint16LE(data, localHeaderOffset + 26);
    const extraLength = readUint16LE(data, localHeaderOffset + 28);
    return localHeaderOffset + 30 + nameLength + extraLength;
}
/**
 * Read unsigned 32-bit little-endian integer
 */
export function readUint32LE(data, offset) {
    return (data[offset] & 0xff) |
        ((data[offset + 1] & 0xff) << 8) |
        ((data[offset + 2] & 0xff) << 16) |
        ((data[offset + 3] & 0xff) << 24);
}
/**
 * Read unsigned 16-bit little-endian integer
 */
export function readUint16LE(data, offset) {
    return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
}
/**
 * Read unsigned 64-bit little-endian integer
 */
export function readUint64LE(data, offset) {
    const view = new DataView(data.buffer, data.byteOffset + offset, 8);
    return view.getBigUint64(0, true);
}
/**
 * Write unsigned 32-bit little-endian integer
 */
export function writeUint32LE(data, offset, value) {
    const view = new DataView(data.buffer, data.byteOffset + offset, 4);
    view.setUint32(0, value, true);
}
/**
 * Write unsigned 16-bit little-endian integer
 */
export function writeUint16LE(data, offset, value) {
    const view = new DataView(data.buffer, data.byteOffset + offset, 2);
    view.setUint16(0, value, true);
}
/**
 * Write unsigned 64-bit little-endian integer
 */
export function writeUint64LE(data, offset, value) {
    const view = new DataView(data.buffer, data.byteOffset + offset, 8);
    view.setBigUint64(0, value, true);
}
/**
 * Concatenate several Uint8Arrays into one
 */
export function concatBytes(arrays) {
    const total = arrays.reduce((sum, a) => sum + a.length, 0);
    const result = new Uint8Array(total);
    let offset = 0;
    for (const a of arrays) {
        result.set(a, offset);
        offset += a.length;
    }
    return result;
}
const CRC32_TABLE = new Int32Array(256);
for (let i = 0; i < 256; i++) {
    let c = i;
    for (let k = 0; k < 8; k++) {
        c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
    }
    CRC32_TABLE[i] = c;
}
export function computeCrc32(data) {
    let c = 0xffffffff;
    for (let i = 0; i < data.length; i++) {
        c = (c >>> 8) ^ CRC32_TABLE[(c ^ data[i]) & 0xff];
    }
    return (c ^ 0xffffffff) >>> 0;
}
export function createLocalFileHeader(name, data, crc, compressionMethod = 0, extra) {
    const extraLen = extra ? extra.length : 0;
    const header = new Uint8Array(30 + name.length + extraLen);
    writeUint32LE(header, 0, ZIP_LOCAL_FILE_HEADER_SIGNATURE);
    writeUint16LE(header, 4, 20); // version needed
    writeUint16LE(header, 6, 0); // flags
    writeUint16LE(header, 8, compressionMethod);
    writeUint16LE(header, 10, 0); // mod time
    writeUint16LE(header, 12, 0); // mod date
    writeUint32LE(header, 14, crc);
    writeUint32LE(header, 18, data.length); // compressed size
    writeUint32LE(header, 22, compressionMethod === 0 ? data.length : 0); // uncompressed size (caller sets for compressed)
    writeUint16LE(header, 26, name.length);
    writeUint16LE(header, 28, extraLen); // extra length
    header.set(name, 30);
    if (extra) {
        header.set(extra, 30 + name.length);
    }
    return header;
}
export function createCentralDirectoryHeader(name, data, crc, localHeaderOffset, compressionMethod = 0, uncompressedSize, extra, comment) {
    const extraLen = extra ? extra.length : 0;
    const commentLen = comment ? comment.length : 0;
    const header = new Uint8Array(46 + name.length + extraLen + commentLen);
    writeUint32LE(header, 0, ZIP_CENTRAL_DIRECTORY_FILE_HEADER_SIGNATURE);
    writeUint16LE(header, 4, 20); // version made by
    writeUint16LE(header, 6, 20); // version needed
    writeUint16LE(header, 8, 0); // flags
    writeUint16LE(header, 10, compressionMethod);
    writeUint16LE(header, 12, 0); // mod time
    writeUint16LE(header, 14, 0); // mod date
    writeUint32LE(header, 16, crc);
    writeUint32LE(header, 20, data.length); // compressed size
    writeUint32LE(header, 24, uncompressedSize ?? data.length); // uncompressed size
    writeUint16LE(header, 28, name.length);
    writeUint16LE(header, 30, extraLen); // extra length
    writeUint16LE(header, 32, commentLen); // comment length
    writeUint16LE(header, 34, 0); // disk number start
    writeUint16LE(header, 36, 0); // internal file attrs
    writeUint32LE(header, 38, 0); // external file attrs
    writeUint32LE(header, 42, localHeaderOffset);
    header.set(name, 46);
    if (extra) {
        header.set(extra, 46 + name.length);
    }
    if (comment) {
        header.set(comment, 46 + name.length + extraLen);
    }
    return header;
}
export function createEndOfCentralDirectory(entryCount, centralDirectorySize, centralDirectoryOffset, commentLength = 0) {
    const record = new Uint8Array(22 + commentLength);
    writeUint32LE(record, 0, ZIP_END_OF_CENTRAL_DIRECTORY_SIGNATURE);
    writeUint16LE(record, 4, 0); // disk number
    writeUint16LE(record, 6, 0); // disk with cd
    writeUint16LE(record, 8, entryCount);
    writeUint16LE(record, 10, entryCount);
    writeUint32LE(record, 12, centralDirectorySize);
    writeUint32LE(record, 16, centralDirectoryOffset);
    writeUint16LE(record, 20, commentLength);
    return record;
}
function alignmentPadding(size, alignment) {
    const mod = size % alignment;
    return mod === 0 ? 0 : alignment - mod;
}
/**
 * Align required files in a ZIP/APK so it installs on Android R+.
 * resources.arsc must be stored and 4-byte aligned.
 * Native libraries in lib/ are stored and 4096-byte aligned.
 */
export function zipalign(data) {
    const eocd = findEndOfCentralDirectory(data);
    if (!eocd) {
        throw new Error('Invalid APK: End of Central Directory not found');
    }
    const cd = parseZipCentralDirectory(data);
    const entries = [...cd.entries].sort((a, b) => a.localHeaderOffset - b.localHeaderOffset);
    const localEnd = cd.offset;
    const localBuffers = [];
    const cdBuffers = [];
    let localOffset = 0;
    const nameCache = new Map();
    for (let i = 0; i < entries.length; i++) {
        const entry = entries[i];
        const nextLocal = i + 1 < entries.length ? entries[i + 1].localHeaderOffset : localEnd;
        if (!nameCache.has(entry.name)) {
            nameCache.set(entry.name, new TextEncoder().encode(entry.name));
        }
        const name = nameCache.get(entry.name);
        const mustStoreAndAlign = entry.name === 'resources.arsc' ||
            (entry.name.startsWith('lib/') && entry.name.endsWith('.so'));
        if (mustStoreAndAlign) {
            const dataOffset = getEntryDataOffset(data, entry.localHeaderOffset);
            const compressed = data.subarray(dataOffset, dataOffset + entry.compressedSize);
            const content = entry.compressionMethod === 0
                ? compressed
                : new Uint8Array(zlib.inflateRawSync(Buffer.from(compressed)));
            const alignment = entry.name === 'resources.arsc' ? 4 : 4096;
            const extraLen = alignmentPadding(localOffset + 30 + name.length, alignment);
            const extra = new Uint8Array(extraLen);
            const crc = computeCrc32(content);
            const localHeader = createLocalFileHeader(name, content, crc, 0, extra);
            const cdHeader = createCentralDirectoryHeader(name, content, crc, localOffset, 0, content.length, extra);
            localBuffers.push(localHeader, content);
            cdBuffers.push(cdHeader);
            localOffset += localHeader.length + content.length;
        }
        else {
            const localChunk = data.subarray(entry.localHeaderOffset, nextLocal);
            localBuffers.push(localChunk);
            const cdSize = 46 + entry.nameLength + entry.extraLength + entry.commentLength;
            const cdHeader = new Uint8Array(cdSize);
            cdHeader.set(data.subarray(entry.cdHeaderOffset, entry.cdHeaderOffset + cdSize), 0);
            writeUint32LE(cdHeader, 42, localOffset);
            cdBuffers.push(cdHeader);
            localOffset += localChunk.length;
        }
    }
    const newLocal = concatBytes(localBuffers);
    const newCd = concatBytes(cdBuffers);
    const newEocdRecord = new Uint8Array(data.subarray(eocd.recordOffset));
    writeUint16LE(newEocdRecord, 8, cdBuffers.length);
    writeUint16LE(newEocdRecord, 10, cdBuffers.length);
    writeUint32LE(newEocdRecord, 12, newCd.length);
    writeUint32LE(newEocdRecord, 16, newLocal.length);
    return concatBytes([newLocal, newCd, newEocdRecord]);
}
//# sourceMappingURL=ZipUtils.js.map