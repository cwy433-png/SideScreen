import Foundation

/// Mac-side ambient media transport. Connects to 127.0.0.1:<localSyncPort>
/// (ADB-forwarded to the tablet's AmbientSyncServer). Not part of StreamingServer.
enum AmbientSyncClient {
    struct Result: Equatable {
        let ok: Bool
        let message: String
    }

    /// Max time to wait for streams to become `.open`.
    private static let openTimeout: TimeInterval = 5
    /// Max wall time for header + all file body writes (ACK has its own budget).
    private static let overallWriteTimeout: TimeInterval = 600
    /// Fail if a write makes no progress for this long (zero-return spin guard).
    private static let writeProgressTimeout: TimeInterval = 30
    /// ACK read budget (prefix + body).
    private static let ackTimeout: TimeInterval = 120

    /// Push media to the tablet. Heavy IO runs off the caller's actor; returns ACK text.
    /// - Parameters:
    ///   - localPort: Mac loopback port (streamPort+1) already forwarded by AppDelegate.
    ///   - photoFolder: Optional non-recursive photo directory (Photos mode only).
    ///   - videoFile: Optional single video file (Video mode only).
    ///   - clearOnly: When true, send an empty manifest to clear tablet media (no mode change).
    ///   - preferredMode: Tablet ambient mode to apply after successful commit; nil on clear.
    ///   - onProgress: Invoked on the cooperative thread with short status strings.
    static func send(
        localPort: UInt16,
        photoFolder: URL?,
        videoFile: URL?,
        clearOnly: Bool,
        preferredMode: AmbientSyncProtocol.PreferredMode? = nil,
        onProgress: ((String) -> Void)? = nil
    ) throws -> Result {
        onProgress?("Preparing…")
        let built = try AmbientSyncProtocol.buildManifest(
            photoFolder: photoFolder,
            videoFile: videoFile,
            clearOnly: clearOnly,
            preferredMode: preferredMode
        )
        let manifestJSON = try AmbientSyncProtocol.encodeManifestJSON(built.manifest)
        if manifestJSON.count > AmbientSyncProtocol.maxManifestBytes {
            throw AmbientSyncProtocol.ProtocolError.limitExceeded("manifest too large")
        }

        onProgress?("Connecting…")
        let host = "127.0.0.1"
        var input: InputStream?
        var output: OutputStream?
        Stream.getStreamsToHost(withName: host, port: Int(localPort), inputStream: &input, outputStream: &output)
        guard let inStream = input, let outStream = output else {
            throw AmbientSyncProtocol.ProtocolError.io("cannot open stream to \(host):\(localPort)")
        }

        inStream.open()
        outStream.open()
        defer {
            inStream.close()
            outStream.close()
        }

        // Wait briefly for streams to open, then require usable `.open` states.
        let openDeadline = Date().addingTimeInterval(openTimeout)
        while (outStream.streamStatus == .opening || inStream.streamStatus == .opening)
                && Date() < openDeadline {
            Thread.sleep(forTimeInterval: 0.02)
        }
        if outStream.streamStatus == .error || inStream.streamStatus == .error {
            let err = outStream.streamError?.localizedDescription
                ?? inStream.streamError?.localizedDescription
                ?? "stream error"
            throw AmbientSyncProtocol.ProtocolError.io("connect failed: \(err)")
        }
        guard outStream.streamStatus == .open, inStream.streamStatus == .open else {
            throw AmbientSyncProtocol.ProtocolError.io(
                "connect timeout: streams not open (in=\(inStream.streamStatus.rawValue) out=\(outStream.streamStatus.rawValue))"
            )
        }

        let writeDeadline = Date().addingTimeInterval(overallWriteTimeout)

        let header = AmbientSyncProtocol.buildRequestHeader(manifestJSON: manifestJSON)
        try writeAll(outStream, header, deadline: writeDeadline)

        // Stream file bodies in manifest order
        for (index, entry) in built.manifest.entries.enumerated() {
            let url = built.files[index]
            onProgress?("Sending \(entry.name)…")
            try streamFile(url, size: entry.size, to: outStream, deadline: writeDeadline)
        }

        onProgress?("Waiting for tablet…")
        let ackData = try readAck(from: inStream)
        let ack = try AmbientSyncProtocol.parseAck(ackData)
        if !ack.ok {
            return Result(ok: false, message: ack.message.isEmpty ? "tablet rejected transfer" : ack.message)
        }
        return Result(ok: true, message: ack.message.isEmpty ? "ok" : ack.message)
    }

    // MARK: - Stream IO (blocking, chunked, deadline-bounded)

    private static func writeAll(_ stream: OutputStream, _ data: Data, deadline: Date) throws {
        var offset = 0
        var lastProgress = Date()
        while offset < data.count {
            if Date() > deadline {
                throw AmbientSyncProtocol.ProtocolError.io("write deadline exceeded")
            }
            if stream.streamStatus == .error || stream.streamStatus == .closed {
                throw AmbientSyncProtocol.ProtocolError.io("write stream closed")
            }
            let n: Int = data.withUnsafeBytes { raw in
                guard let base = raw.bindMemory(to: UInt8.self).baseAddress else { return -1 }
                return stream.write(base + offset, maxLength: data.count - offset)
            }
            if n < 0 {
                throw AmbientSyncProtocol.ProtocolError.io(
                    stream.streamError?.localizedDescription ?? "write failed"
                )
            }
            if n == 0 {
                if Date().timeIntervalSince(lastProgress) > writeProgressTimeout {
                    throw AmbientSyncProtocol.ProtocolError.io("write stalled (no progress)")
                }
                Thread.sleep(forTimeInterval: 0.005)
                continue
            }
            lastProgress = Date()
            offset += n
        }
    }

    private static func streamFile(
        _ url: URL,
        size: UInt64,
        to stream: OutputStream,
        deadline: Date
    ) throws {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var remaining = size
        while remaining > 0 {
            if Date() > deadline {
                throw AmbientSyncProtocol.ProtocolError.io("write deadline exceeded")
            }
            let toRead = Int(min(UInt64(AmbientSyncProtocol.ioChunkBytes), remaining))
            let chunk = try handle.read(upToCount: toRead) ?? Data()
            if chunk.isEmpty {
                throw AmbientSyncProtocol.ProtocolError.truncated
            }
            try writeAll(stream, chunk, deadline: deadline)
            remaining -= UInt64(chunk.count)
        }
    }

    private static func readAck(from stream: InputStream) throws -> Data {
        // Fixed prefix: 8 bytes, then msgLen more
        var prefix = Data()
        let deadline = Date().addingTimeInterval(ackTimeout)
        while prefix.count < 8 {
            if Date() > deadline {
                throw AmbientSyncProtocol.ProtocolError.io("ACK timeout")
            }
            if !stream.hasBytesAvailable {
                if stream.streamStatus == .error || stream.streamStatus == .atEnd {
                    throw AmbientSyncProtocol.ProtocolError.truncated
                }
                Thread.sleep(forTimeInterval: 0.01)
                continue
            }
            var buf = [UInt8](repeating: 0, count: 8 - prefix.count)
            let n = stream.read(&buf, maxLength: buf.count)
            if n < 0 {
                throw AmbientSyncProtocol.ProtocolError.io(
                    stream.streamError?.localizedDescription ?? "read failed"
                )
            }
            if n == 0 {
                Thread.sleep(forTimeInterval: 0.01)
                continue
            }
            prefix.append(contentsOf: buf[0..<n])
        }
        guard let msgLen = AmbientSyncProtocol.readUInt16BE(prefix, at: 6) else {
            throw AmbientSyncProtocol.ProtocolError.truncated
        }
        if Int(msgLen) > AmbientSyncProtocol.maxAckMessageBytes {
            throw AmbientSyncProtocol.ProtocolError.limitExceeded("ACK message too large")
        }
        var body = Data()
        while body.count < Int(msgLen) {
            if Date() > deadline {
                throw AmbientSyncProtocol.ProtocolError.io("ACK timeout")
            }
            if !stream.hasBytesAvailable {
                if stream.streamStatus == .atEnd && body.count < Int(msgLen) {
                    throw AmbientSyncProtocol.ProtocolError.truncated
                }
                Thread.sleep(forTimeInterval: 0.01)
                continue
            }
            var buf = [UInt8](repeating: 0, count: Int(msgLen) - body.count)
            let n = stream.read(&buf, maxLength: buf.count)
            if n < 0 {
                throw AmbientSyncProtocol.ProtocolError.io(
                    stream.streamError?.localizedDescription ?? "read failed"
                )
            }
            if n == 0 {
                Thread.sleep(forTimeInterval: 0.01)
                continue
            }
            body.append(contentsOf: buf[0..<n])
        }
        var all = prefix
        all.append(body)
        return all
    }
}
