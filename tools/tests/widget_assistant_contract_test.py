"""Check reflected Assistant catalogue APIs directly in a local APK's DEX files."""
from pathlib import Path
import struct
import sys
import zipfile


def dex_classes(data):
    def u32(offset): return struct.unpack_from('<I', data, offset)[0]
    def uleb(offset):
        value = shift = 0
        while True:
            byte = data[offset]; offset += 1
            value |= (byte & 127) << shift
            if byte < 128: return value, offset
            shift += 7
    def indices(offset):
        return struct.unpack_from('<' + 'H' * u32(offset), data, offset + 4) if offset else ()
    strings = []
    for i in range(u32(56)):
        _, p = uleb(u32(u32(60) + i * 4))
        strings.append(data[p:data.index(0, p)].decode('utf-8', errors='replace'))
    types = [strings[u32(u32(68) + i * 4)] for i in range(u32(64))]
    protos = []
    for i in range(u32(72)):
        _, result, offset = struct.unpack_from('<III', data, u32(76) + i * 12)
        protos.append('(' + ''.join(types[x] for x in indices(offset)) + ')' + types[result])
    fields = [struct.unpack_from('<HHI', data, u32(84) + i * 8) for i in range(u32(80))]
    methods = [struct.unpack_from('<HHI', data, u32(92) + i * 8) for i in range(u32(88))]
    classes = {}
    for i in range(u32(96)):
        row = struct.unpack_from('<8I', data, u32(100) + i * 32)
        owner, access, parent, interfaces, _, _, offset, _ = row
        item = {'access': access, 'parent': types[parent] if parent != 0xffffffff else None,
                'interfaces': [types[x] for x in indices(interfaces)], 'fields': {}, 'methods': {}}
        classes[types[owner]] = item
        if not offset: continue
        counts = []
        for _ in range(4):
            value, offset = uleb(offset); counts.append(value)
        for count in counts[:2]:
            index = 0
            for _ in range(count):
                delta, offset = uleb(offset); index += delta
                flags, offset = uleb(offset)
                _, field_type, name = fields[index]
                item['fields'][strings[name]] = (types[field_type], flags)
        for count in counts[2:]:
            index = 0
            for _ in range(count):
                delta, offset = uleb(offset); index += delta
                flags, offset = uleb(offset)
                _, offset = uleb(offset)
                _, proto, name = methods[index]
                item['methods'][strings[name] + protos[proto]] = flags
    return classes


assert len(sys.argv) == 2, 'Provide the local PersonalAssistant APK'
classes = {}
with zipfile.ZipFile(Path(sys.argv[1])) as apk:
    for name in apk.namelist():
        if name.startswith('classes') and name.endswith('.dex'):
            classes.update(dex_classes(apk.read(name)))

ROOT = 'Lcom/miui/personalassistant/'
PICKER = ROOT + 'picker/'
DETAIL = PICKER + 'business/detail/bean/'
STR, OBJ, LIST = 'Ljava/lang/String;', 'Ljava/lang/Object;', 'Ljava/util/List;'
checked = 0


def method(owner, name, arguments, result=OBJ):
    global checked
    signature = name + '(' + ''.join(arguments) + ')' + result
    assert signature in classes[owner]['methods'], f'Missing {owner}->{signature}'
    checked += 1


def field(owner, name, kind):
    global checked
    assert classes[owner]['fields'][name][0] == kind, (owner, name, kind)
    checked += 1


repo = PICKER + 'business/detail/PickerDetailRepository;'
matches = [m for m in classes[repo]['methods'] if m.startswith('getPickerData(')]
assert len(matches) == 1
continuation = 'Lkotlin/coroutines/e;'  # This APK's declared continuation type.
method(repo, 'getPickerData', ['I', STR, 'I', 'I', STR, STR, STR, STR, 'I', continuation])
method(PICKER + 'business/list/viewmodel/PickerAppListRepository;', 'loadDataFromService', ['I', 'I', continuation])
method(PICKER + 'business/list/viewmodel/PickerAppListRepositoryKt;', 'traversalAppListDataSetLocalIcon', [LIST, continuation])
method(PICKER + 'business/list/viewmodel/PickerAppListRepositoryKt$traversalAppListDataSetLocalIcon$2;', 'invokeSuspend', [OBJ])
method(PICKER + 'business/home/viewmodel/PickerMainViewModel;', 'convertAppListToCards', [LIST], LIST)
field(PICKER + 'business/home/viewmodel/PickerMainViewModel$loadAppList$1;', '$requestPage', 'I')
field(ROOT + 'PAApplication;', 'f', ROOT + 'PAApplication;')
method('Lkotlinx/coroutines/flow/q;', '<init>', [OBJ], 'V')
method('Lkotlinx/coroutines/flow/q;', 'd', ['Lkotlinx/coroutines/flow/e;', continuation])
field('Lkotlinx/coroutines/flow/q;', 'a', OBJ)
job = classes['Lkotlinx/coroutines/JobCancellationException;']['fields']['job'][0]
method(job, 'isActive', [], 'Z')
assert classes[job]['access'] & 0x200  # interface
assert classes['Lkotlinx/coroutines/flow/q;']['fields']['a'][1] & 0x10  # final
method(PICKER + 'business/list/bean/PickerListAppData;', '<init>', [STR, STR, STR, 'I', STR, STR, 'I', STR, 'Landroid/graphics/drawable/Drawable;', 'I'], 'V')
method(DETAIL + 'PickerDetailResponseWidget;', '<init>', [STR]*5 + ['I']*3 + [LIST, 'I', 'Z', 'Landroid/os/Bundle;'], 'V')
tip = DETAIL + 'PickerDetailTipStr;'
method(tip, '<init>', [STR, STR], 'V')
method(DETAIL + 'PickerDetailResponse;', '<init>', [STR, STR] + ['I']*3 + [STR]*4 + ['I'] + [STR]*5 + [tip, DETAIL + 'PickerDetailSupperAppInfo;', DETAIL + 'PickerDetailResponseWidget;', DETAIL + 'PickerDetailResponseMaml;', 'Ljava/lang/Long;', 'Lmiui/drm/DrmManager$DrmResult;'], 'V')
method(DETAIL + 'PickerDetailResponseWrapper;', '<init>', [LIST] + [STR]*3 + ['I'] + [STR]*5 + [tip, 'Ljava/util/ArrayList;', 'Z', 'I', 'Landroid/graphics/drawable/Drawable;', 'Z', 'Ljava/lang/Long;', 'Z', 'Z'], 'V')
for name in ('setPay', 'setBought', 'setAuthorityPass'):
    method(DETAIL + 'PickerDetailResponse;', name, ['Z'], 'V')
for name, kind in [('pickerDetailResponses', LIST), ('responseCode', 'I')]:
    field(DETAIL + 'PickerDetailResponseWrapper;', name, kind)
field(DETAIL + 'PickerDetailResponse;', 'implUniqueCode', STR)
field(DETAIL + 'PickerDetailResponse;', 'appPackage', STR)
field(DETAIL + 'PickerDetailResponse;', 'widgetImplInfo', DETAIL + 'PickerDetailResponseWidget;')
field(DETAIL + 'PickerDetailResponseWidget;', 'widgetProviderName', STR)
item = ROOT + 'widget/iteminfo/AppWidgetItemInfo;'
method(item, 'parseWidgetMetaData', [], 'V')
field(item, 'provider', 'Landroid/content/ComponentName;')
field(item, 'providerInfo', 'Landroid/appwidget/AppWidgetProviderInfo;')
method(PICKER + 'business/detail/utils/PickerDetailUtil;', 'getAddTargetItemInfo', [DETAIL + 'PickerDetailResponse;'], ROOT + 'widget/entity/ItemInfo;')
print(f'PASS: {checked} reflected Assistant catalogue signatures/fields in {Path(sys.argv[1]).name}')
