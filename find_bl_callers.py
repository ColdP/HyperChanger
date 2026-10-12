import bisect
import lzma
import struct
import sys

from list_mini_symbols import elf_sections


def symbol_index(blob):
    sections, _ = elf_sections(blob)
    packed = sections['.gnu_debugdata'][1]
    mini = lzma.decompress(blob[packed[4]:packed[4] + packed[5]])
    mini_sections, entries = elf_sections(mini)
    symtab = mini_sections['.symtab'][1]
    strings_entry = entries[symtab[6]]
    strings = mini[strings_entry[4]:strings_entry[4] + strings_entry[5]]
    result = []
    for offset in range(symtab[4], symtab[4] + symtab[5], symtab[9]):
        name_offset, _, _, _, address, size = struct.unpack_from('<IBBHQQ', mini, offset)
        if not size:
            continue
        end = strings.find(b'\0', name_offset)
        result.append((address, address + size, strings[name_offset:end].decode(errors='replace')))
    return sorted(result)


def main():
    blob = open(sys.argv[1], 'rb').read()
    target = int(sys.argv[2], 0)
    sections, _ = elf_sections(blob)
    code = sections['.text'][1]
    start, offset, size = code[3:6]
    symbols = symbol_index(blob)
    addresses = [symbol[0] for symbol in symbols]
    for address in range(start, start + size, 4):
        instruction = struct.unpack_from('<I', blob, offset + address - start)[0]
        if instruction >> 26 != 0x25:
            continue
        delta = instruction & 0x3ffffff
        if delta & 0x2000000:
            delta -= 0x4000000
        if address + delta * 4 != target:
            continue
        index = bisect.bisect_right(addresses, address) - 1
        owner = symbols[index] if index >= 0 else None
        name = owner[2] if owner and address < owner[1] else '<unknown>'
        print(f'{address:#x} {name}')


if __name__ == '__main__':
    main()
