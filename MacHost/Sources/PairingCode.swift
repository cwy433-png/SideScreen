import Foundation
import Security

/// Short one-time pairing code for tablets without a camera (issue #35).
///
/// The code is an alternative front door to the same 32-byte wireless token
/// the QR embeds: the tablet sends the typed code over a pairing handshake,
/// and the Mac answers with the real token. Everything downstream (auth,
/// streaming) is unchanged.
///
/// Lifecycle: a fresh code is generated every time the server starts in
/// wireless mode, after every successful pairing (one-time use), and after
/// too many failed attempts (brute-force guard). It never persists.
enum PairingCode {
    /// 8 decimal digits: trivial to type on any keyboard, including e-ink
    /// tablets, and 10^8 combinations is plenty against a 5-attempt limit.
    static let length = 8

    static func generate() -> String {
        var digits = ""
        while digits.count < length {
            var byte: UInt8 = 0
            let status = SecRandomCopyBytes(kSecRandomDefault, 1, &byte)
            precondition(status == errSecSuccess, "SecRandomCopyBytes failed: \(status)")
            // Rejection-sample so every digit is uniform (256 % 10 != 0).
            if byte < 250 {
                digits.append(Character(UnicodeScalar(UInt8(0x30) + byte % 10)))
            }
        }
        return digits
    }

    /// "47293185" -> "4729-3185" for display next to the QR.
    static func display(_ code: String) -> String {
        guard code.count == length else { return code }
        let mid = code.index(code.startIndex, offsetBy: length / 2)
        return "\(code[code.startIndex..<mid])-\(code[mid...])"
    }

    /// Constant-time compare; ignores separators the user might type
    /// ("4729-3185", "4729 3185").
    static func validate(_ candidate: String, expected: String) -> Bool {
        let normalized = normalize(candidate)
        let expectedNorm = normalize(expected)
        guard normalized.count == expectedNorm.count, !expectedNorm.isEmpty else { return false }
        var diff: UInt8 = 0
        for (a, b) in zip(Array(normalized.utf8), Array(expectedNorm.utf8)) {
            diff |= a ^ b
        }
        return diff == 0
    }

    static func normalize(_ raw: String) -> String {
        String(raw.filter { $0.isNumber && $0.isASCII })
    }
}
