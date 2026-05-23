#!/usr/bin/env python3
# Offline activation code generator (device-hardware-code -> activation code)

import argparse
import hashlib


INTERNAL_SALT_PART1 = "c8c4b7c9"
INTERNAL_SALT_PART2 = "fa3d91e2"
INTERNAL_SALT_PART3 = "5a7f29bd"
INTERNAL_SALT = INTERNAL_SALT_PART1 + INTERNAL_SALT_PART2 + INTERNAL_SALT_PART3
ACTIVATION_KDF_ITERATIONS = 80_000
ACTIVATION_KDF_OUTPUT_BYTES = 32
ACTIVATION_GROUP_SIZE = 8


def normalize_device_code(code: str) -> str:
    return "".join(code.strip().upper().split())


def generate_activation_code(device_code: str) -> str:
    dc = normalize_device_code(device_code)
    # matches OfflineActivationRepository.buildActivationCode()
    password = f"MiRoot-Permanent|{dc}|{INTERNAL_SALT}".encode("utf-8")
    salt = f"MiRoot-Offline-Activation|{dc}|v2".encode("utf-8")
    derived = hashlib.pbkdf2_hmac(
        "sha256",
        password,
        salt,
        ACTIVATION_KDF_ITERATIONS,
        ACTIVATION_KDF_OUTPUT_BYTES,
    )
    base = derived.hex().upper()
    return "-".join(
        base[i : i + ACTIVATION_GROUP_SIZE]
        for i in range(0, len(base), ACTIVATION_GROUP_SIZE)
    )


def main():
    parser = argparse.ArgumentParser(description="Generate MiRoot offline activation code")
    parser.add_argument("device_code", help="Hardware device code shown in MiRoot activation dialog")
    args = parser.parse_args()

    print(generate_activation_code(args.device_code))


if __name__ == "__main__":
    main()

