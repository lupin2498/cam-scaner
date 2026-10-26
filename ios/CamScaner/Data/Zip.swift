import Foundation
import Compression
import zlib

/// Minimal ZIP writer (entries stored uncompressed), enough for .docx files and backups.
final class ZipWriter {
    private var data = Data()
    private var central = Data()
    private var count: UInt16 = 0

    func add(_ name: String, _ content: Data) {
        let nameBytes = Array(name.utf8)
        let crc = content.withUnsafeBytes { buf -> UInt32 in
            UInt32(crc32(0, buf.bindMemory(to: Bytef.self).baseAddress, uInt(content.count)))
        }
        let offset = UInt32(data.count)
        // local file header
        data.append(le32(0x04034b50))
        data.append(le16(20)) // version needed
        data.append(le16(0x0800)) // utf-8 names
        data.append(le16(0)) // stored
        data.append(le16(0)); data.append(le16(0)) // time, date
        data.append(le32(crc))
        data.append(le32(UInt32(content.count)))
        data.append(le32(UInt32(content.count)))
        data.append(le16(UInt16(nameBytes.count)))
        data.append(le16(0))
        data.append(contentsOf: nameBytes)
        data.append(content)
        // central directory entry
        central.append(le32(0x02014b50))
        central.append(le16(20)); central.append(le16(20))
        central.append(le16(0x0800))
        central.append(le16(0))
        central.append(le16(0)); central.append(le16(0))
        central.append(le32(crc))
        central.append(le32(UInt32(content.count)))
        central.append(le32(UInt32(content.count)))
        central.append(le16(UInt16(nameBytes.count)))
        central.append(le16(0)); central.append(le16(0)); central.append(le16(0)); central.append(le16(0))
        central.append(le32(0))
        central.append(le32(offset))
        central.append(contentsOf: nameBytes)
        count += 1
    }

    func finish() -> Data {
        var out = data
        let centralOffset = UInt32(out.count)
        out.append(central)
        out.append(le32(0x06054b50))
        out.append(le16(0)); out.append(le16(0))
        out.append(le16(count)); out.append(le16(count))
        out.append(le32(UInt32(central.count)))
        out.append(le32(centralOffset))
        out.append(le16(0))
        return out
    }

    private func le16(_ v: UInt16) -> Data { withUnsafeBytes(of: v.littleEndian) { Data($0) } }
    private func le32(_ v: UInt32) -> Data { withUnsafeBytes(of: v.littleEndian) { Data($0) } }
}

/// Reads stored and deflated entries of a ZIP file through its central directory.
enum ZipReader {
    struct Entry {
        let name: String
        let data: Data
    }

    enum ZipError: Error { case invalid }

    static func entries(_ zip: Data) throws -> [Entry] {
        guard zip.count >= 22 else { throw ZipError.invalid }
        // find the end of central directory record (allowing for a comment)
        var eocd = -1
        var i = zip.count - 22
        while i >= 0 && i >= zip.count - 22 - 65_535 {
            if u32(zip, i) == 0x06054b50 { eocd = i; break }
            i -= 1
        }
        guard eocd >= 0 else { throw ZipError.invalid }
        let count = Int(u16(zip, eocd + 10))
        var pos = Int(u32(zip, eocd + 16))
        var out: [Entry] = []
        for _ in 0..<count {
            guard pos + 46 <= zip.count, u32(zip, pos) == 0x02014b50 else { throw ZipError.invalid }
            let method = u16(zip, pos + 10)
            let compressed = Int(u32(zip, pos + 20))
            let uncompressed = Int(u32(zip, pos + 24))
            let nameLen = Int(u16(zip, pos + 28))
            let extraLen = Int(u16(zip, pos + 30))
            let commentLen = Int(u16(zip, pos + 32))
            let localOffset = Int(u32(zip, pos + 42))
            let name = String(decoding: zip.subdata(in: (pos + 46)..<(pos + 46 + nameLen)), as: UTF8.self)
            pos += 46 + nameLen + extraLen + commentLen
            guard localOffset + 30 <= zip.count, u32(zip, localOffset) == 0x04034b50 else { throw ZipError.invalid }
            let localNameLen = Int(u16(zip, localOffset + 26))
            let localExtraLen = Int(u16(zip, localOffset + 28))
            let start = localOffset + 30 + localNameLen + localExtraLen
            guard start + compressed <= zip.count else { throw ZipError.invalid }
            let raw = zip.subdata(in: start..<(start + compressed))
            let content: Data
            switch method {
            case 0: content = raw
            case 8: content = inflate(raw, size: uncompressed)
            default: throw ZipError.invalid
            }
            if !name.hasSuffix("/") { out.append(Entry(name: name, data: content)) }
        }
        return out
    }

    private static func inflate(_ raw: Data, size: Int) -> Data {
        guard size > 0 else { return Data() }
        var out = Data(count: size)
        let written = out.withUnsafeMutableBytes { dst -> Int in
            raw.withUnsafeBytes { src -> Int in
                compression_decode_buffer(
                    dst.bindMemory(to: UInt8.self).baseAddress!, size,
                    src.bindMemory(to: UInt8.self).baseAddress!, raw.count,
                    nil, COMPRESSION_ZLIB
                )
            }
        }
        return out.prefix(written)
    }

    private static func u16(_ d: Data, _ i: Int) -> UInt16 { UInt16(d[d.startIndex + i]) | UInt16(d[d.startIndex + i + 1]) << 8 }
    private static func u32(_ d: Data, _ i: Int) -> UInt32 {
        UInt32(u16(d, i)) | UInt32(u16(d, i + 2)) << 16
    }
}
