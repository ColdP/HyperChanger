"""Execute the packaged ARM64 epilogue with Unicorn (no Android device writes)."""
import struct
import sys
from pathlib import Path

from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM
from unicorn.arm64_const import (
    UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X15, UC_ARM64_REG_X22,
    UC_ARM64_REG_X29, UC_ARM64_REG_X30, UC_ARM64_REG_Q0, UC_ARM64_REG_Q1,
    UC_ARM64_REG_NZCV, UC_ARM64_REG_CPACR_EL1,
)


def main():
    data = Path(sys.argv[1]).read_bytes()
    phoff, shoff = struct.unpack_from('<QQ', data, 32)
    phsize, phcount, shsize, shcount = struct.unpack_from('<HHHH', data, 54)
    segments = []
    for i in range(phcount):
        kind, flags, offset, va, _, filesz, memsz, _ = struct.unpack_from(
            '<IIQQQQQQ', data, phoff + i * phsize)
        if kind == 1:
            segments.append((offset, va, filesz, memsz))
    sections = [struct.unpack_from('<IIQQQQIIQQ', data, shoff + i * shsize)
                for i in range(shcount)]
    symbols = {}
    for section in sections:
        if section[1] != 2:  # full symbol table
            continue
        strings_section = sections[section[6]]
        strings = data[strings_section[4]:strings_section[4] + strings_section[5]]
        for at in range(section[4], section[4] + section[5], section[9]):
            name, _, _, _, va, size = struct.unpack_from('<IBBHQQ', data, at)
            text = strings[name:strings.find(b'\0', name)].decode()
            symbols[text] = (va, size)
    base, object_base, stack_base, finish = 0x1000000, 0x4000000, 0x5000000, 0x6000000
    uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    image_size = max(va + size for _, va, _, size in segments)
    uc.mem_map(base, (image_size + 4095) & ~4095)
    for offset, va, size, _ in segments:
        uc.mem_write(base + va, data[offset:offset + size])
    uc.mem_map(object_base, 4096)
    uc.mem_map(stack_base, 65536)
    uc.mem_map(finish, 4096)
    uc.reg_write(UC_ARM64_REG_CPACR_EL1, 3 << 20)
    entry = base + symbols['folder_spacing_return'][0]
    config = base + symbols['folder_spacing_value'][0]
    receiver, fp, old_fp = object_base + 1, stack_base + 32768, stack_base + 49152
    q0 = 0x123456789abcdef00123456789abcdef
    q1 = 0xfedcba9876543210fedcba9876543210
    cases = 0
    # Numeric zero is an enabled absolute gap; disabled uses a separate sentinel.
    for stock in (0.0, 8.0, 12.0, 24.0):
        for value in range(-20, 81):
            for enabled in (False, True):
                uc.mem_write(config, struct.pack('<i', value if enabled else 1000))
                uc.mem_write(receiver + 0x3b, struct.pack('<d', stock))
                uc.mem_write(receiver + 0x43, struct.pack('<d', 8.0))
                uc.mem_write(fp - 8, struct.pack('<Q', receiver))
                uc.mem_write(fp, struct.pack('<QQ', old_fp, finish))
                for register, contents in (
                    (UC_ARM64_REG_X0, 111), (UC_ARM64_REG_X1, 222),
                    (UC_ARM64_REG_X15, fp - 16), (UC_ARM64_REG_X22, 333),
                    (UC_ARM64_REG_X29, fp), (UC_ARM64_REG_X30, 444),
                    (UC_ARM64_REG_Q0, q0), (UC_ARM64_REG_Q1, q1),
                    (UC_ARM64_REG_NZCV, 0xa0000000),
                ):
                    uc.reg_write(register, contents)
                uc.emu_start(entry, finish, count=100)
                gap = struct.unpack('<d', uc.mem_read(receiver + 0x3b, 8))[0]
                assert gap == (value if enabled else stock), (stock, value, enabled, gap)
                assert struct.unpack('<d', uc.mem_read(receiver + 0x43, 8))[0] == 8.0
                for register, expected in (
                    (UC_ARM64_REG_X0, 333), (UC_ARM64_REG_X1, 222),
                    (UC_ARM64_REG_X15, fp + 16), (UC_ARM64_REG_X22, 333),
                    (UC_ARM64_REG_X29, old_fp), (UC_ARM64_REG_X30, finish),
                    (UC_ARM64_REG_Q0, q0), (UC_ARM64_REG_Q1, q1),
                    (UC_ARM64_REG_NZCV, 0xa0000000),
                ):
                    assert uc.reg_read(register) == expected, (register, expected)
                cases += 1
    print(f'ARM64 epilogue passed {cases} cases: absolute -20..80, zero, disabled, frame/FP/NZCV preserved')


if __name__ == '__main__':
    main()
