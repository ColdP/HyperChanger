import argparse
import struct

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM


def sections(blob):
    shoff = struct.unpack_from('<Q', blob, 40)[0]
    shentsize, shnum, names_index = struct.unpack_from('<HHH', blob, 58)
    headers = [struct.unpack_from('<IIQQQQIIQQ', blob, shoff + i * shentsize)
               for i in range(shnum)]
    names = headers[names_index]
    strings = blob[names[4]:names[4] + names[5]]
    result = {}
    for header in headers:
        end = strings.find(b'\0', header[0])
        name = strings[header[0]:end].decode(errors='replace')
        result[name] = (header[3], header[4], header[5])
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('library')
    parser.add_argument('needle', nargs='+')
    args = parser.parse_args()
    blob = open(args.library, 'rb').read()
    section = sections(blob)
    code_va, code_offset, code_size = section['.text']
    decoder = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    targets = {}
    for needle in args.needle:
        encoded = needle.encode()
        position = 0
        while (position := blob.find(encoded, position)) >= 0:
            for name, (va, offset, size) in section.items():
                if offset <= position < offset + size:
                    targets[va + position - offset] = needle
                    break
            position += 1
    for address, needle in targets.items():
        print(f'{needle!r} string VA {address:#x}')
    if not targets:
        return
    recent = []
    for instruction in decoder.disasm(blob[code_offset:code_offset + code_size], code_va):
        if instruction.mnemonic == 'adrp':
            recent.append((instruction.address, instruction.op_str))
            recent = recent[-16:]
        elif instruction.mnemonic in ('add', 'ldr'):
            for address, operands in recent:
                if instruction.address - address > 64:
                    continue
                try:
                    register, page = operands.split(', #')
                    destination, source, displacement = instruction.op_str.split(', ')
                    if source != register or not displacement.startswith('#'):
                        continue
                    resolved = int(page, 0) + int(displacement[1:], 0)
                except (ValueError, IndexError):
                    continue
                if resolved in targets:
                    print(f'{targets[resolved]!r} xref {address:#x} -> {instruction.address:#x}')


if __name__ == '__main__':
    main()
