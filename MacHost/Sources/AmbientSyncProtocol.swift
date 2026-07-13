import Foundation
import CryptoKit

/// Wire protocol v1 for Mac → tablet ambient media push (dedicated socket).
/// Separate from StreamingServer frame types 0–12.
///
/// Request:  magic(4) + version(1) + manifestLen(u32 BE) + manifest UTF-8 JSON
///           + raw file bodies in order (each declared UInt64 size).
/// Response: magic(4) + version(1) + ok(u8) + msgLen(u16 BE) + message UTF-8.
enum AmbientSyncProtocol {
    static let magic: [UInt8] = [0x53, 0x53, 0x41, 0x4D] // "SSAM"
    static let version: UInt8 = 1
    /// Fixed port on the Android device; Mac reaches it via `adb forward`.
    static let devicePort: UInt16 = 57322

    static let maxManifestBytes = 256 * 1024
    static let maxEntries = 201
    static let maxPhotoBytes: UInt64 = 50 * 1024 * 1024
    static let maxVideoBytes: UInt64 = 500 * 1024 * 1024
    static let maxTotalBytes: UInt64 = 1024 * 1024 * 1024
    static let maxAckMessageBytes = 512
    static let ioChunkBytes = 64 * 1024

    static let photoExtensions: Set<String> = ["jpg", "jpeg", "png", "webp"]
    static let videoExtensions: Set<String> = ["mp4", "webm", "mkv", "mov"]

    enum Kind: String, Codable {
        case photo
        case video
    }

    /// Optional ambient mode the tablet should apply after a successful commit.
    /// Wire values: `"photos"` / `"video"`. Omitted on clear and by legacy senders.
    enum PreferredMode: String, Codable, Equatable {
        case photos
        case video
    }

    struct ManifestEntry: Codable, Equatable {
        let id: String
        let kind: Kind
        let name: String
        let size: UInt64
        let sha256: String
        /// Local source path — not serialized on the wire.
        var localPath: String? = nil

        enum CodingKeys: String, CodingKey {
            case id, kind, name, size, sha256
        }
    }

    struct Manifest: Codable, Equatable {
        /// When set, tablet switches ambient mode only after successful commit.
        /// Must be omitted for empty (clear) manifests.
        let preferredMode: PreferredMode?
        let entries: [ManifestEntry]

        init(preferredMode: PreferredMode? = nil, entries: [ManifestEntry]) {
            self.preferredMode = preferredMode
            self.entries = entries
        }
    }

    struct Ack: Equatable {
        let ok: Bool
        let message: String
    }

    enum ProtocolError: Error, Equatable {
        case invalidMagic
        case unsupportedVersion(UInt8)
        case truncated
        case ackFailed(String)
        case limitExceeded(String)
        case io(String)
    }

    /// Local Mac port for `adb forward tcp:<local> tcp:57322`.
    /// Always distinct from the stream port: normally `streamPort + 1`.
    /// When `streamPort` is already 65535, fall back to 65534 so `adb forward`
    /// never collides with the streaming listener.
    static func localSyncPort(streamPort: UInt16) -> UInt16 {
        if streamPort == 65535 {
            return 65534
        }
        return streamPort &+ 1
    }

    /// Sanitize display name to a single path segment (no directories).
    static func sanitizeDisplayName(_ raw: String?) -> String {
        guard var name = raw, !name.isEmpty else { return "file" }
        if let slash = name.lastIndex(of: "/") {
            name = String(name[name.index(after: slash)...])
        }
        if let slash = name.lastIndex(of: "\\") {
            name = String(name[name.index(after: slash)...])
        }
        name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        while name.hasPrefix(".") {
            name.removeFirst()
        }
        if name.isEmpty { return "file" }
        var cleaned = ""
        cleaned.reserveCapacity(min(name.count, 180))
        for ch in name {
            if cleaned.count >= 180 { break }
            if ch.isLetter || ch.isNumber || ch == "." || ch == "-" || ch == "_" || ch == " " {
                cleaned.append(ch)
            } else {
                cleaned.append("_")
            }
        }
        cleaned = cleaned.trimmingCharacters(in: .whitespacesAndNewlines)
        while cleaned.hasPrefix(".") {
            cleaned.removeFirst()
        }
        return cleaned.isEmpty ? "file" : cleaned
    }

    static func fileExtension(_ name: String) -> String {
        guard let dot = name.lastIndex(of: "."),
              dot != name.startIndex,
              name.index(after: dot) < name.endIndex else {
            return ""
        }
        return String(name[name.index(after: dot)...]).lowercased()
    }

    static func isHexSHA256(_ value: String) -> Bool {
        guard value.count == 64 else { return false }
        return value.unicodeScalars.allSatisfy { sc in
            (sc >= "0" && sc <= "9") || (sc >= "a" && sc <= "f") || (sc >= "A" && sc <= "F")
        }
    }

    // MARK: - Big-endian helpers

    static func appendUInt32BE(_ value: UInt32, to data: inout Data) {
        var be = value.bigEndian
        withUnsafeBytes(of: &be) { data.append(contentsOf: $0) }
    }

    static func appendUInt16BE(_ value: UInt16, to data: inout Data) {
        var be = value.bigEndian
        withUnsafeBytes(of: &be) { data.append(contentsOf: $0) }
    }

    static func readUInt32BE(_ data: Data, at offset: Int) -> UInt32? {
        guard offset + 4 <= data.count else { return nil }
        return (UInt32(data[offset]) << 24)
            | (UInt32(data[offset + 1]) << 16)
            | (UInt32(data[offset + 2]) << 8)
            | UInt32(data[offset + 3])
    }

    static func readUInt16BE(_ data: Data, at offset: Int) -> UInt16? {
        guard offset + 2 <= data.count else { return nil }
        return (UInt16(data[offset]) << 8) | UInt16(data[offset + 1])
    }

    // MARK: - Encode / decode

    static func encodeManifestJSON(_ manifest: Manifest) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(manifest)
    }

    static func buildRequestHeader(manifestJSON: Data) -> Data {
        var data = Data()
        data.append(contentsOf: magic)
        data.append(version)
        appendUInt32BE(UInt32(manifestJSON.count), to: &data)
        data.append(manifestJSON)
        return data
    }

    static func parseAck(_ data: Data) throws -> Ack {
        guard data.count >= 8 else { throw ProtocolError.truncated }
        guard Array(data[0..<4]) == magic else { throw ProtocolError.invalidMagic }
        let ver = data[4]
        guard ver == version else { throw ProtocolError.unsupportedVersion(ver) }
        let ok = data[5] != 0
        guard let msgLen = readUInt16BE(data, at: 6) else { throw ProtocolError.truncated }
        guard data.count >= 8 + Int(msgLen) else { throw ProtocolError.truncated }
        let msgData = data.subdata(in: 8..<(8 + Int(msgLen)))
        let message = String(data: msgData, encoding: .utf8) ?? ""
        return Ack(ok: ok, message: message)
    }

    /// SHA-256 hex of a file, streaming in bounded chunks.
    static func sha256Hex(ofFile url: URL) throws -> (hash: String, size: UInt64) {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        var size: UInt64 = 0
        while true {
            let chunk = try handle.read(upToCount: ioChunkBytes) ?? Data()
            if chunk.isEmpty { break }
            hasher.update(data: chunk)
            size += UInt64(chunk.count)
        }
        let digest = hasher.finalize()
        let hex = digest.map { String(format: "%02x", $0) }.joined()
        return (hex, size)
    }

    /// Enumerate non-recursive photos in a folder (sorted by name).
    static func enumeratePhotos(inFolder folder: URL) -> [URL] {
        let fm = FileManager.default
        guard let items = try? fm.contentsOfDirectory(
            at: folder,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        ) else {
            return []
        }
        return items
            .filter { url in
                var isDir: ObjCBool = false
                guard fm.fileExists(atPath: url.path, isDirectory: &isDir), !isDir.boolValue else {
                    return false
                }
                let ext = url.pathExtension.lowercased()
                return photoExtensions.contains(ext)
            }
            .sorted { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending }
    }

    /// Build a wire manifest from local paths; deduplicates photos by SHA-256.
    /// Empty list is only valid for an explicit clear action (no `preferredMode`).
    /// Non-clear sends include exactly one source type and a matching `preferredMode`.
    static func buildManifest(
        photoFolder: URL?,
        videoFile: URL?,
        clearOnly: Bool,
        preferredMode: PreferredMode? = nil
    ) throws -> (manifest: Manifest, files: [URL]) {
        if clearOnly {
            // Clear must never carry preferredMode (mode stays on tablet).
            return (Manifest(preferredMode: nil, entries: []), [])
        }

        // Send path always declares a mode; default only for callers that still
        // pass a single source without an explicit mode.
        // Extraneous mismatched source URLs are rejected (never silently ignored).
        let mode: PreferredMode
        if let preferredMode {
            mode = preferredMode
        } else if photoFolder != nil && videoFile == nil {
            mode = .photos
        } else if videoFile != nil && photoFolder == nil {
            mode = .video
        } else if photoFolder != nil && videoFile != nil {
            throw ProtocolError.io("send only Photos or Video, not both")
        } else {
            throw ProtocolError.io("nothing to send — choose Photos or Video, or use Clear Tablet Media")
        }

        var entries: [ManifestEntry] = []
        var files: [URL] = []
        var seenPhotoHashes = Set<String>()
        var total: UInt64 = 0

        // Exclusive source URLs: photos requires photoFolder and nil videoFile;
        // video requires videoFile and nil photoFolder. Never silently ignore extras.
        switch mode {
        case .photos:
            guard let folder = photoFolder else {
                throw ProtocolError.io("choose a photo folder for Photos mode")
            }
            guard videoFile == nil else {
                throw ProtocolError.io("photos mode requires videoFile to be nil")
            }
            let photos = enumeratePhotos(inFolder: folder)
            for url in photos {
                let (hash, size) = try sha256Hex(ofFile: url)
                if seenPhotoHashes.contains(hash) { continue }
                if size > maxPhotoBytes {
                    throw ProtocolError.limitExceeded("photo \(url.lastPathComponent) too large")
                }
                total += size
                if total > maxTotalBytes {
                    throw ProtocolError.limitExceeded("total size")
                }
                seenPhotoHashes.insert(hash)
                let name = sanitizeDisplayName(url.lastPathComponent)
                let entry = ManifestEntry(
                    id: hash,
                    kind: .photo,
                    name: name,
                    size: size,
                    sha256: hash,
                    localPath: url.path
                )
                entries.append(entry)
                files.append(url)
                if entries.count >= maxEntries {
                    throw ProtocolError.limitExceeded("too many entries")
                }
            }
            if entries.isEmpty {
                throw ProtocolError.io("no photos found in folder (jpg/png/webp)")
            }
        case .video:
            guard let video = videoFile else {
                throw ProtocolError.io("choose a video file for Video mode")
            }
            guard photoFolder == nil else {
                throw ProtocolError.io("video mode requires photoFolder to be nil")
            }
            let ext = video.pathExtension.lowercased()
            guard videoExtensions.contains(ext) else {
                throw ProtocolError.limitExceeded("unsupported video extension .\(ext)")
            }
            let (hash, size) = try sha256Hex(ofFile: video)
            if size > maxVideoBytes {
                throw ProtocolError.limitExceeded("video too large")
            }
            total += size
            if total > maxTotalBytes {
                throw ProtocolError.limitExceeded("total size")
            }
            let name = sanitizeDisplayName(video.lastPathComponent)
            entries.append(
                ManifestEntry(
                    id: hash,
                    kind: .video,
                    name: name,
                    size: size,
                    sha256: hash,
                    localPath: video.path
                )
            )
            files.append(video)
        }

        return (Manifest(preferredMode: mode, entries: entries), files)
    }
}
