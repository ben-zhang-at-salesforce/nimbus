//
// Copyright (c) 2020, Salesforce.com, inc.
// All rights reserved.
// SPDX-License-Identifier: BSD-3-Clause
// For full license text, see the LICENSE file in the repo
// root or https://opensource.org/licenses/BSD-3-Clause
//

import Foundation

/**
 A wrapper for `Encodable` value types since `JSONEncoder` does not
 currently support top-level fragments.

 Once `JSONEncoder` supports encoding top-level fragments this can
 be removed.
 */

public typealias EncodableError = Error & Encodable

public enum EncodableValue: Encodable {
    case void
    case value(Encodable)
    case error(EncodableError)

    enum Keys: String, CodingKey {
        case v // swiftlint:disable:this identifier_name
        case e // swiftlint:disable:this identifier_name
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: Keys.self)
        switch self {
        case .void:
            try container.encodeNil(forKey: .v)
        case let .value(value):
            let superContainer = container.superEncoder(forKey: .v)
            try value.encode(to: superContainer)
        case let .error(error):
            let superContainer = container.superEncoder(forKey: .e)
            try error.encode(to: superContainer)
        }
    }
}
