import io
import lzma
import struct
import sys


def elf_sections(blob):
    shoff = struct.unpack_from('<Q', blob, 40)[0]
    entry_size, count, names_index = struct.unpack_from('<HHH', blob, 58)
    entries = [struct.unpack_from('<IIQQQQIIQQ', blob, shoff + i * entry_size)
               for i in range(count)]
    names_entry = entries[names_index]
    names = blob[names_entry[4]:names_entry[4] + names_entry[5]]
    result = {}
    for index, entry in enumerate(entries):
        end = names.find(b'\0', entry[0])
        name = names[entry[0]:end].decode(errors='replace')
        result[name] = (index, entry)
    return result, entries


def main():
    blob = open(sys.argv[1], 'rb').read()
    sections, _ = elf_sections(blob)
    packed = sections['.gnu_debugdata'][1]
    mini = lzma.decompress(blob[packed[4]:packed[4] + packed[5]])
    mini_sections, entries = elf_sections(mini)
    print('mini sections:', ', '.join(mini_sections))
    symtab = mini_sections.get('.symtab')
    if symtab is None:
        return
    entry = symtab[1]
    strings_entry = entries[entry[6]]
    strings = mini[strings_entry[4]:strings_entry[4] + strings_entry[5]]
    query = sys.argv[2].lower() if len(sys.argv) > 2 else 'dock'
    for offset in range(entry[4], entry[4] + entry[5], entry[9]):
        name_offset, info, other, section_index, value, size = struct.unpack_from('<IBBHQQ', mini, offset)
        end = strings.find(b'\0', name_offset)
        name = strings[name_offset:end].decode(errors='replace')
        if query in name.lower():
            print(f'{value:#x} {size:#x} {name}')


if __name__ == '__main__':
    main()
