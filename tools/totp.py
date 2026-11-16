"""Print the current 6-digit TOTP code for a Base32 secret (RFC 6238: SHA-1, 30 s, 6 digits).

Local demo helper for staff two-step verification when no authenticator app is at hand:

    python tools/totp.py JBSWY3DPEHPK3PXP...         # the secret shown on "Two-step verification"
    python tools/totp.py "otpauth://totp/...?secret=..."  # or the whole otpauth URI

Standard library only. Never use it for real accounts: the secret is a password.
"""
from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import struct
import sys
import time
from urllib.parse import parse_qs, urlparse


def secret_from(argument: str) -> str:
    text = argument.strip()
    if text.lower().startswith("otpauth://"):
        values = parse_qs(urlparse(text).query).get("secret", [""])
        text = values[0]
    return text.replace(" ", "").upper()


def totp(secret: str, at: float | None = None, step: int = 30, digits: int = 6) -> str:
    key = base64.b32decode(secret + "=" * (-len(secret) % 8))
    counter = int((time.time() if at is None else at) // step)
    digest = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    value = (struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF) % (10 ** digits)
    return str(value).zfill(digits)


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    secret = secret_from(sys.argv[1])
    try:
        code = totp(secret)
    except (binascii.Error, ValueError):
        print("Not a Base32 secret (letters A-Z and digits 2-7). Copy the setup key or the whole otpauth:// link.")
        return 2
    remaining = 30 - int(time.time()) % 30
    print(f"{code}  (valid for about {remaining} more seconds)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
