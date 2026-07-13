import XCTest
@testable import SideScreen

final class AmbientSyncProtocolTests: XCTestCase {
    func testLocalSyncPortDerivation() {
        XCTAssertEqual(AmbientSyncProtocol.localSyncPort(streamPort: 54321), 54322)
        XCTAssertEqual(AmbientSyncProtocol.localSyncPort(streamPort: 1), 2)
        XCTAssertEqual(AmbientSyncProtocol.localSyncPort(streamPort: 65534), 65535)
        // Must stay distinct from stream port 65535 (cannot use 65535 for both)
        XCTAssertEqual(AmbientSyncProtocol.localSyncPort(streamPort: 65535), 65534)
    }

    func testSanitizeDisplayName_stripsPathsAndUnsafeChars() {
        XCTAssertEqual(AmbientSyncProtocol.sanitizeDisplayName("photo.jpg"), "photo.jpg")
        XCTAssertEqual(AmbientSyncProtocol.sanitizeDisplayName("/tmp/evil/../photo.jpg"), "photo.jpg")
        XCTAssertEqual(AmbientSyncProtocol.sanitizeDisplayName("C:\\Users\\x\\pic.png"), "pic.png")
        XCTAssertEqual(AmbientSyncProtocol.sanitizeDisplayName("../../etc/passwd"), "passwd")
        XCTAssertEqual(AmbientSyncProtocol.sanitizeDisplayName(""), "file")
        XCTAssertEqual(AmbientSyncProtocol.sanitizeDisplayName(nil), "file")
        XCTAssertEqual(AmbientSyncProtocol.sanitizeDisplayName("a/b/c.webp"), "c.webp")
        let weird = AmbientSyncProtocol.sanitizeDisplayName("my photo (1).jpg")
        XCTAssertTrue(weird.hasSuffix(".jpg"))
        XCTAssertFalse(weird.contains("("))
    }

    func testFileExtension() {
        XCTAssertEqual(AmbientSyncProtocol.fileExtension("a.JPG"), "jpg")
        XCTAssertEqual(AmbientSyncProtocol.fileExtension("noext"), "")
        XCTAssertEqual(AmbientSyncProtocol.fileExtension(".hidden"), "")
    }

    func testIsHexSHA256() {
        let good = String(repeating: "ab", count: 32)
        XCTAssertTrue(AmbientSyncProtocol.isHexSHA256(good))
        XCTAssertFalse(AmbientSyncProtocol.isHexSHA256("short"))
        XCTAssertFalse(AmbientSyncProtocol.isHexSHA256(String(repeating: "g", count: 64)))
    }

    func testAckRoundTrip() throws {
        var data = Data()
        data.append(contentsOf: AmbientSyncProtocol.magic)
        data.append(AmbientSyncProtocol.version)
        data.append(1) // ok
        let msg = "3 photos, 1 video (12.0 MB)"
        let msgBytes = Array(msg.utf8)
        AmbientSyncProtocol.appendUInt16BE(UInt16(msgBytes.count), to: &data)
        data.append(contentsOf: msgBytes)

        let ack = try AmbientSyncProtocol.parseAck(data)
        XCTAssertTrue(ack.ok)
        XCTAssertEqual(ack.message, msg)
    }

    func testAckFailure() throws {
        var data = Data()
        data.append(contentsOf: AmbientSyncProtocol.magic)
        data.append(AmbientSyncProtocol.version)
        data.append(0)
        let msg = "sha256 mismatch"
        let msgBytes = Array(msg.utf8)
        AmbientSyncProtocol.appendUInt16BE(UInt16(msgBytes.count), to: &data)
        data.append(contentsOf: msgBytes)

        let ack = try AmbientSyncProtocol.parseAck(data)
        XCTAssertFalse(ack.ok)
        XCTAssertEqual(ack.message, msg)
    }

    func testManifestJSONEncodeHasEntries() throws {
        let entry = AmbientSyncProtocol.ManifestEntry(
            id: String(repeating: "a", count: 64),
            kind: .photo,
            name: "x.jpg",
            size: 10,
            sha256: String(repeating: "b", count: 64)
        )
        let manifest = AmbientSyncProtocol.Manifest(entries: [entry])
        let json = try AmbientSyncProtocol.encodeManifestJSON(manifest)
        let obj = try JSONSerialization.jsonObject(with: json) as? [String: Any]
        let entries = obj?["entries"] as? [[String: Any]]
        XCTAssertEqual(entries?.count, 1)
        XCTAssertEqual(entries?.first?["kind"] as? String, "photo")
        XCTAssertNil(entries?.first?["localPath"])
        // Legacy-style encode without preferredMode omits the key (nil optional).
        XCTAssertNil(obj?["preferredMode"])
    }

    func testManifestJSONEncode_withPreferredModePhotos() throws {
        let entry = AmbientSyncProtocol.ManifestEntry(
            id: String(repeating: "a", count: 64),
            kind: .photo,
            name: "x.jpg",
            size: 10,
            sha256: String(repeating: "b", count: 64)
        )
        let manifest = AmbientSyncProtocol.Manifest(preferredMode: .photos, entries: [entry])
        let json = try AmbientSyncProtocol.encodeManifestJSON(manifest)
        let obj = try JSONSerialization.jsonObject(with: json) as? [String: Any]
        XCTAssertEqual(obj?["preferredMode"] as? String, "photos")
        XCTAssertEqual((obj?["entries"] as? [Any])?.count, 1)
    }

    func testManifestJSONEncode_withPreferredModeVideo() throws {
        let entry = AmbientSyncProtocol.ManifestEntry(
            id: String(repeating: "c", count: 64),
            kind: .video,
            name: "v.mp4",
            size: 20,
            sha256: String(repeating: "d", count: 64)
        )
        let manifest = AmbientSyncProtocol.Manifest(preferredMode: .video, entries: [entry])
        let json = try AmbientSyncProtocol.encodeManifestJSON(manifest)
        let obj = try JSONSerialization.jsonObject(with: json) as? [String: Any]
        XCTAssertEqual(obj?["preferredMode"] as? String, "video")
    }

    func testManifestJSONEncode_clearOmitsPreferredMode() throws {
        let manifest = AmbientSyncProtocol.Manifest(preferredMode: nil, entries: [])
        let json = try AmbientSyncProtocol.encodeManifestJSON(manifest)
        let obj = try JSONSerialization.jsonObject(with: json) as? [String: Any]
        XCTAssertEqual((obj?["entries"] as? [Any])?.count, 0)
        XCTAssertNil(obj?["preferredMode"])
    }

    func testBuildManifest_clearOnly_noPreferredMode() throws {
        let built = try AmbientSyncProtocol.buildManifest(
            photoFolder: nil,
            videoFile: nil,
            clearOnly: true,
            preferredMode: .photos // ignored on clear
        )
        XCTAssertTrue(built.manifest.entries.isEmpty)
        XCTAssertNil(built.manifest.preferredMode)
        XCTAssertTrue(built.files.isEmpty)
    }

    func testBuildManifest_photosOnly_sendsPreferredModePhotos() throws {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("ambient-photos-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let photo = tmp.appendingPathComponent("a.jpg")
        try Data([0xFF, 0xD8, 0xFF, 0xD9]).write(to: photo) // minimal jpeg-ish bytes

        let built = try AmbientSyncProtocol.buildManifest(
            photoFolder: tmp,
            videoFile: nil,
            clearOnly: false,
            preferredMode: .photos
        )
        XCTAssertEqual(built.manifest.preferredMode, .photos)
        XCTAssertEqual(built.manifest.entries.count, 1)
        XCTAssertEqual(built.manifest.entries[0].kind, .photo)
        XCTAssertEqual(built.files.count, 1)
    }

    func testBuildManifest_videoOnly_sendsPreferredModeVideo() throws {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("ambient-video-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let video = tmp.appendingPathComponent("clip.mp4")
        try Data([0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70]).write(to: video)

        let built = try AmbientSyncProtocol.buildManifest(
            photoFolder: nil,
            videoFile: video,
            clearOnly: false,
            preferredMode: .video
        )
        XCTAssertEqual(built.manifest.preferredMode, .video)
        XCTAssertEqual(built.manifest.entries.count, 1)
        XCTAssertEqual(built.manifest.entries[0].kind, .video)
    }

    func testBuildManifest_rejectsPhotosModeWithoutFolder() {
        XCTAssertThrowsError(
            try AmbientSyncProtocol.buildManifest(
                photoFolder: nil,
                videoFile: nil,
                clearOnly: false,
                preferredMode: .photos
            )
        )
    }

    func testBuildManifest_rejectsMixedSourcesWhenModeUnset() {
        // Both sources without an exclusive preferredMode is illegal for new send path.
        let folder = URL(fileURLWithPath: "/tmp")
        let video = URL(fileURLWithPath: "/tmp/x.mp4")
        XCTAssertThrowsError(
            try AmbientSyncProtocol.buildManifest(
                photoFolder: folder,
                videoFile: video,
                clearOnly: false,
                preferredMode: nil
            )
        )
    }

    func testBuildManifest_rejectsPhotosModeWithExtraneousVideoFile() {
        // Mismatched source URLs must not be silently ignored.
        let folder = URL(fileURLWithPath: "/tmp/photos")
        let video = URL(fileURLWithPath: "/tmp/clip.mp4")
        XCTAssertThrowsError(
            try AmbientSyncProtocol.buildManifest(
                photoFolder: folder,
                videoFile: video,
                clearOnly: false,
                preferredMode: .photos
            )
        ) { error in
            guard case AmbientSyncProtocol.ProtocolError.io(let msg) = error else {
                return XCTFail("expected ProtocolError.io, got \(error)")
            }
            XCTAssertTrue(
                msg.localizedCaseInsensitiveContains("video") ||
                    msg.localizedCaseInsensitiveContains("nil") ||
                    msg.localizedCaseInsensitiveContains("not both"),
                "unexpected message: \(msg)"
            )
        }
    }

    func testBuildManifest_rejectsVideoModeWithExtraneousPhotoFolder() {
        let folder = URL(fileURLWithPath: "/tmp/photos")
        let video = URL(fileURLWithPath: "/tmp/clip.mp4")
        XCTAssertThrowsError(
            try AmbientSyncProtocol.buildManifest(
                photoFolder: folder,
                videoFile: video,
                clearOnly: false,
                preferredMode: .video
            )
        ) { error in
            guard case AmbientSyncProtocol.ProtocolError.io(let msg) = error else {
                return XCTFail("expected ProtocolError.io, got \(error)")
            }
            XCTAssertTrue(
                msg.localizedCaseInsensitiveContains("photo") ||
                    msg.localizedCaseInsensitiveContains("nil") ||
                    msg.localizedCaseInsensitiveContains("not both"),
                "unexpected message: \(msg)"
            )
        }
    }

    func testBuildManifest_rejectsPhotosModeWithoutFolderEvenIfVideoPresent() {
        // photos mode requires photoFolder non-nil (and videoFile nil).
        let video = URL(fileURLWithPath: "/tmp/clip.mp4")
        XCTAssertThrowsError(
            try AmbientSyncProtocol.buildManifest(
                photoFolder: nil,
                videoFile: video,
                clearOnly: false,
                preferredMode: .photos
            )
        )
    }

    func testBuildManifest_rejectsVideoModeWithoutVideoEvenIfFolderPresent() {
        let folder = URL(fileURLWithPath: "/tmp/photos")
        XCTAssertThrowsError(
            try AmbientSyncProtocol.buildManifest(
                photoFolder: folder,
                videoFile: nil,
                clearOnly: false,
                preferredMode: .video
            )
        )
    }

    func testBuildRequestHeaderBigEndianLength() throws {
        let json = Data(#"{"entries":[]}"#.utf8)
        let header = AmbientSyncProtocol.buildRequestHeader(manifestJSON: json)
        XCTAssertEqual(Array(header[0..<4]), AmbientSyncProtocol.magic)
        XCTAssertEqual(header[4], AmbientSyncProtocol.version)
        let len = AmbientSyncProtocol.readUInt32BE(header, at: 5)
        XCTAssertEqual(len, UInt32(json.count))
        XCTAssertEqual(header.subdata(in: 9..<header.count), json)
    }
}
