"""Execute production ARM64 store islands and verify scoping and all CPU state."""
import math
import struct
import sys
from pathlib import Path
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM
from unicorn import arm64_const as r

minimum = [0, 0, 0, -1, 0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 0]
maximum = [500, 2, 3, 1, 2, 5, 120, 3, 1, 1, 100, 2, 1000, 2, 1]
blob = Path(sys.argv[1]).read_bytes()
pos = cases = 0
while pos < len(blob):
    length, parameter, reg, offset = struct.unpack_from('<IIII', blob, pos)
    pos += 16
    code = blob[pos:pos+length]
    pos += length
    for stock in (0., -0., .25, 1.4, -2., 500.):
        for delta in (-100, 0, 100, 200):
            for scope in ('desktop', 'dock_dark', 'dock_light', 'disabled', 'foreign_constructor'):
                u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
                for address in (0x100000, 0x200000, 0x300000): u.mem_map(address, 0x10000)
                u.mem_write(0x100000, code)
                def record(selected, enabled):
                    # Parameter zero is an absolute blur multiplier (100 = stock);
                    # the remaining parameters are signed deltas.
                    delta = selected - 100 if parameter == 0 else selected
                    return struct.pack('<Qdddd', enabled and delta != 0, 1+delta/100,
                                       0 if parameter == 0 else delta/100,
                                       minimum[parameter], maximum[parameter])
                # Different records prove that Dock cannot silently use desktop values.
                dock_delta = 50 if delta != 50 else -50
                u.mem_write(0x104000, record(delta, scope != 'disabled') + record(dock_delta, scope != 'disabled') +
                            struct.pack('<QQQ', 0x500004, 0x600004, 0x700004))
                u.reg_write(r.UC_ARM64_REG_CPACR_EL1, 3 << 20)
                u.reg_write(r.UC_ARM64_REG_SP, 0x208000)
                u.reg_write(r.UC_ARM64_REG_NZCV, 0xa0000000)
                for x in range(31): u.reg_write(getattr(r, f'UC_ARM64_REG_X{x}'), 0x400000+x)
                for q in range(32): u.reg_write(getattr(r, f'UC_ARM64_REG_Q{q}'), ((0x123456789abcdef0+q)<<64) | (0x12345678+q))
                u.reg_write(r.UC_ARM64_REG_X5, 0x300001)
                u.reg_write(r.UC_ARM64_REG_X29, 0x204000)
                u.mem_write(0x204000, struct.pack('<QQ', 0x205000, 0x900004 if scope == 'foreign_constructor' else 0x700004))
                caller = 0x500004 if scope == 'dock_dark' else 0x600004 if scope == 'dock_light' else 0x800004
                u.mem_write(0x205000, struct.pack('<QQ', 0, caller))
                u.reg_write(getattr(r, f'UC_ARM64_REG_Q{reg}'), (0x123456789abcdef0<<64) | struct.unpack('<Q', struct.pack('<d',stock))[0])
                before_x = [u.reg_read(getattr(r, f'UC_ARM64_REG_X{x}')) for x in range(31)]
                before_q = [u.reg_read(getattr(r, f'UC_ARM64_REG_Q{q}')) for q in range(32)]
                u.emu_start(0x100000, 0x108000, count=100)
                assert u.reg_read(r.UC_ARM64_REG_PC) == 0x108000
                selected = dock_delta if scope.startswith('dock_') else delta
                expected = stock
                selected_delta = selected - 100 if parameter == 0 else selected
                if scope not in ('disabled', 'foreign_constructor') and selected_delta != 0:
                    expected = (0 if parameter == 0 else selected_delta/100) if stock == 0 else stock*(1+selected_delta/100)
                    expected = min(maximum[parameter], max(minimum[parameter], expected))
                actual, = struct.unpack('<d',u.mem_read(0x300001+offset,8))
                assert math.isclose(actual,expected,abs_tol=1e-12), (parameter,stock,delta,scope,actual,expected)
                assert before_x == [u.reg_read(getattr(r, f'UC_ARM64_REG_X{x}')) for x in range(31)]
                assert before_q == [u.reg_read(getattr(r, f'UC_ARM64_REG_Q{q}')) for q in range(32)]
                assert u.reg_read(r.UC_ARM64_REG_SP) == 0x208000
                assert u.reg_read(r.UC_ARM64_REG_NZCV) == 0xa0000000
                cases += 1
print(f'{cases} ARM64 store executions passed: desktop/Dock scope, zero fallback, clamps, stock replay, all X/Q registers, SP, NZCV')
