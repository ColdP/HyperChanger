"""Execute actual generated islands; check Dart/CPU state and exact signed offsets."""
import math
import struct
import sys
from pathlib import Path
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM
from unicorn import arm64_const as r

blob = Path(sys.argv[1]).read_bytes()
programs = []
pos = 0
while pos < len(blob):
    length, = struct.unpack_from('<I', blob, pos)
    programs.append(blob[pos + 4:pos + 4 + length])
    pos += 4 + length
fields = [(21, 0xcb, False), (2, 0x17, False), (9, 0x5f, True), (13, 0x83, False), (14, 0x8b, False), (15, 0x93, False), (0, 0, False)]
cases = 0
for code, (reg, field, ior) in zip(programs, fields, strict=True):
    dock = field == 0
    for value in (range(-60, 31) if dock else (0, .01, .5, 1, 1.5, 2)):
        for enabled in (False, True):
            u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
            u.mem_map(0x100000, 0x10000)
            u.mem_map(0x200000, 0x10000)
            u.mem_map(0x300000, 0x10000)
            u.mem_write(0x100000, code)
            u.mem_write(0x104000, struct.pack('<Qd', enabled, value))
            stock = 1.4 if ior else 18.25
            u.mem_write(0x300001 + field, struct.pack('<d', stock))
            u.reg_write(r.UC_ARM64_REG_CPACR_EL1, 3 << 20)
            u.reg_write(r.UC_ARM64_REG_SP, 0x208000)
            u.reg_write(r.UC_ARM64_REG_NZCV, 0xa0000000)
            for x in range(31): u.reg_write(getattr(r, f'UC_ARM64_REG_X{x}'), 0x400000 + x)
            u.reg_write(r.UC_ARM64_REG_X0, 0x300001)
            for q in range(32): u.reg_write(getattr(r, f'UC_ARM64_REG_Q{q}'), (0x123456789abcdef0 + q) << 64 | (0x12345678 + q))
            if dock: u.reg_write(r.UC_ARM64_REG_Q2, int.from_bytes(struct.pack('<d', stock), 'little'))
            before_x = [u.reg_read(getattr(r, f'UC_ARM64_REG_X{x}')) for x in range(31)]
            before_q = [u.reg_read(getattr(r, f'UC_ARM64_REG_Q{q}')) for q in range(32)]
            u.emu_start(0x100000, 0x108000, count=50)
            assert u.reg_read(r.UC_ARM64_REG_PC) == 0x108000
            expected = stock if not enabled else stock + value if dock else 1 + (stock - 1) * value if ior else stock * value
            result = u.reg_read(getattr(r, f'UC_ARM64_REG_Q{reg}'))
            result = struct.unpack('<d', (result & ((1 << 64) - 1)).to_bytes(8, 'little'))[0]
            assert math.isclose(result, expected, abs_tol=1e-12), (reg, value, enabled, result, expected)
            assert u.reg_read(r.UC_ARM64_REG_SP) == 0x208000
            assert u.reg_read(r.UC_ARM64_REG_NZCV) == 0xa0000000
            assert before_x == [u.reg_read(getattr(r, f'UC_ARM64_REG_X{x}')) for x in range(31)]
            for q in range(32):
                if q != reg: assert before_q[q] == u.reg_read(getattr(r, f'UC_ARM64_REG_Q{q}'))
            cases += 1
print(f'{cases} ARM64 executions passed: values, disabled replay, SP, X0–X30, Q30/Q31, NZCV')
