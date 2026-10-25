import Foundation

enum HandshakeError: Error, Equatable {
    case invalidMagic
    case invalidName
    case truncated
}

enum HandshakeStatus: UInt8 {
    case ok = 0x00
    case invalidToken = 0x01
    case invalidMagic = 0x02
    case invalidName = 0x03
}

struct ParsedHandshake {
    let token: Data
    let deviceName: String
}

/// Status byte of the code-pairing response (issue #35).
enum PairingStatus: UInt8 {
    case ok = 0x00
    case invalidCode = 0x01
}

struct ParsedPairingRequest {
    let code: String
    let deviceName: String
}

enum HandshakeCodec {
    static let requestMagic: [UInt8] = [0x53, 0x53, 0x57, 0x41]   // "SSWA"
    static let responseMagic: [UInt8] = [0x53, 0x53, 0x57, 0x52]  // "SSWR"
    static let fixedPrefixLen = 4 + 32 + 1                         // magic + token + name_len

    /// Code-pairing request "SSPC": [magic 4][code 8 ASCII digits + zero-pad 24][name_len 1][name N].
    /// The 32-byte middle field deliberately mirrors the SSWA token field so the
    /// total prefix is the same 37 bytes: an OLD host reads its full fixed
    /// prefix, sees an unknown magic, and answers invalidMagic instead of
    /// stalling on a short read — the client can then say "update the Mac app".
    static let pairingRequestMagic: [UInt8] = [0x53, 0x53, 0x50, 0x43]   // "SSPC"
    /// Code-pairing response "SSPR": [magic 4][status 1] then, when status ==
    /// ok: [token 32][mac_name_len 1][mac_name M].
    static let pairingResponseMagic: [UInt8] = [0x53, 0x53, 0x50, 0x52]  // "SSPR"
    static let pairingCodeFieldLen = 32

    /// Parses the `[name_len 1][name N]` tail shared by SSWA and SSPC requests.
    private static func parseNameSuffix(_ bytes: [UInt8], totalCount: Int) throws -> String {
        let nameLen = Int(bytes[36])
        guard nameLen >= 1 && nameLen <= 64 else { throw HandshakeError.invalidName }
        guard totalCount >= fixedPrefixLen + nameLen else { throw HandshakeError.truncated }
        let nameBytes = Array(bytes[37..<(37 + nameLen)])
        guard let name = String(bytes: nameBytes, encoding: .utf8), !name.isEmpty else {
            throw HandshakeError.invalidName
        }
        return name
    }

    /// Parses the variable-length request `[magic 4][token 32][name_len 1][name N]`.
    static func parseRequest(_ data: Data) throws -> ParsedHandshake {
        guard data.count >= fixedPrefixLen else { throw HandshakeError.truncated }
        let bytes = Array(data)
        guard Array(bytes[0..<4]) == requestMagic else { throw HandshakeError.invalidMagic }
        let token = Data(bytes[4..<36])
        let name = try parseNameSuffix(bytes, totalCount: data.count)
        return ParsedHandshake(token: token, deviceName: name)
    }

    static func encodeResponse(status: HandshakeStatus) -> Data {
        Data(responseMagic + [status.rawValue])
    }

    /// Parses a full pairing request `[magic 4][code 32][name_len 1][name N]`.
    /// The code is the leading run of pairing-alphabet characters in the
    /// 32-byte field (the rest is zero padding).
    static func parsePairingRequest(_ data: Data) throws -> ParsedPairingRequest {
        guard data.count >= fixedPrefixLen else { throw HandshakeError.truncated }
        let bytes = Array(data)
        guard Array(bytes[0..<4]) == pairingRequestMagic else { throw HandshakeError.invalidMagic }
        let codeField = bytes[4..<(4 + pairingCodeFieldLen)].prefix { $0 != 0 }
        guard let code = String(bytes: codeField, encoding: .utf8), !code.isEmpty else {
            throw HandshakeError.invalidMagic
        }
        let name = try parseNameSuffix(bytes, totalCount: data.count)
        return ParsedPairingRequest(code: code, deviceName: name)
    }

    static func encodePairingResponse(status: PairingStatus, token: Data? = nil, macName: String? = nil) -> Data {
        var out = Data(pairingResponseMagic + [status.rawValue])
        guard status == .ok, let token = token else { return out }
        precondition(token.count == 32, "pairing token must be 32 bytes")
        out.append(token)
        var nameBytes = Array((macName ?? "Mac").utf8.prefix(64))
        if nameBytes.isEmpty { nameBytes = Array("Mac".utf8) }
        out.append(UInt8(nameBytes.count))
        out.append(contentsOf: nameBytes)
        return out
    }
}
