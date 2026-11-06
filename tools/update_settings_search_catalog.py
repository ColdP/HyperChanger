"""Refresh the search-only catalog from the actual PageId dispatcher and UI text.

Run from any directory: python tools/update_settings_search_catalog.py
Use --check in verification to reject a stale catalog. This does not run app code.
"""
from pathlib import Path
import argparse
import json
import re

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / 'app/src/main/java/btm/m/os4/systemuihook'
OUTPUT = ROOT / 'app/src/main/assets/settings-search.json'
DECL = re.compile(r'^(?:(?:private|internal|public|inline|suspend|tailrec|open|override)\s+)*(?:fun\s+(?:<[^>]+>\s+)?(\w+)\s*\(|(?:data\s+|enum\s+|sealed\s+)?class\s+|object\s+|(?:const\s+)?val\s+)', re.M)
LITERAL = r'"((?:\\.|[^"\\])*)"'
TEXT = re.compile(r'\btr\(\s*' + LITERAL + r'\s*,\s*' + LITERAL)
TITLE_CONTEXT = re.compile(r'(?:\btitle\s*=|\b(?:SharedViewArrowPreference|ArrowPreference|SwitchPreference|EditableSliderPreference|BasicComponent)\( )\s*$', re.X)


def decode(value):
    # Kotlin's escaped dollar is not a JSON escape; interpolation is not indexed.
    value = json.loads('"' + value.replace('\\$', '$') + '"')
    return value if '$' not in value else None


def build_catalog():
    definitions = {}
    for source in sorted(SOURCES.rglob('*.kt')):
        content = source.read_text(encoding='utf-8')
        declarations = list(DECL.finditer(content))
        for index, declaration in enumerate(declarations):
            name = declaration.group(1)
            if name:
                end = declarations[index + 1].start() if index + 1 < len(declarations) else len(content)
                definitions.setdefault(name, []).append((source, content[declaration.start():end]))
    main = (SOURCES / 'MainActivity.kt').read_text(encoding='utf-8')
    dispatcher = main[main.index('private fun Detail('):main.index('private fun NotificationSimulationDialog(')]
    routes = re.findall(r'PageId\.([A-Z_]+)\s*->\s*(\w+)\(', dispatcher)
    catalog = {}
    for page, function in routes:
        if function == 'LegalDocument':
            continue  # Each legal document already has a distinct explicit search entry.
        owners = definitions.get(function, [])
        if len(owners) != 1:
            raise ValueError(f'{page}: expected one UI owner for {function}, found {len(owners)}')
        source, body = owners[0]
        # Include named preference helpers in this UI owner, not arbitrary call-graph matches.
        helpers = set(re.findall(r'\b(\w+(?:Preference|Preferences|Sliders|Controls))\(', body))
        bodies = [body]
        for helper in sorted(helpers):
            candidates = definitions.get(helper, [])
            if len(candidates) == 1 and candidates[0][0] == source:
                bodies.append(candidates[0][1])
        pairs = []
        for body in bodies:
            for match in TEXT.finditer(body):
                pair = [decode(match[1]), decode(match[2])]
                if not all(pair):
                    continue
                title = bool(TITLE_CONTEXT.search(body[max(0, match.start() - 100):match.start()]))
                existing = next((entry for entry in pairs if entry[:2] == pair), None)
                if existing is None:
                    pairs.append(pair + [title])
                elif title:
                    existing[2] = True
        if pairs:
            catalog[page] = pairs
    if ['伪装版本', '伪装版本', True] not in catalog.get('SYSTEM_UPDATE', []):
        raise ValueError('The version-spoof setting must be indexed under SYSTEM_UPDATE')
    return catalog


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    catalog = build_catalog()
    expected = json.dumps(catalog, ensure_ascii=False, indent=2) + '\n'
    if args.check:
        if not OUTPUT.exists() or OUTPUT.read_text(encoding='utf-8') != expected:
            raise SystemExit('Search catalog is stale. Run python tools/update_settings_search_catalog.py')
    else:
        OUTPUT.write_text(expected, encoding='utf-8')
    print(f'Search catalog: {len(catalog)} pages, {sum(map(len, catalog.values()))} UI text entries; ' + ('verified' if args.check else 'updated'))


if __name__ == '__main__':
    main()
