from pathlib import Path
from PIL import Image

root = Path(__file__).resolve().parents[1]
manifest = (root / 'app/src/main/AndroidManifest.xml').read_text('utf-8')
main = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt').read_text('utf-8')
manager = root / 'app/src/main/java/com/lchuang/xiaozhimobile/DesktopIconManager.kt'
checks = {
    'manifest default icon': 'android:icon="@mipmap/ic_launcher"' in manifest,
    'manifest round icon': 'android:roundIcon="@mipmap/ic_launcher_round"' in manifest,
    'desktop icon manager exists': manager.exists(),
    'image picker': 'ACTION_OPEN_DOCUMENT' in main,
    'apply custom icon': 'applyCustomIcon' in main,
    'restore default icon': 'restoreDefault' in main,
    'custom icon preview': 'iconPreview' in main,
}
failed=[k for k,v in checks.items() if not v]
if failed:
    raise SystemExit('missing icon feature: ' + ', '.join(failed))
expected={
    'mipmap-mdpi':48,
    'mipmap-hdpi':72,
    'mipmap-xhdpi':96,
    'mipmap-xxhdpi':144,
    'mipmap-xxxhdpi':192,
}
for folder,size in expected.items():
    for name in ('ic_launcher.png','ic_launcher_round.png'):
        p=root/'app/src/main/res'/folder/name
        if not p.exists(): raise SystemExit(f'missing {p.relative_to(root)}')
        with Image.open(p) as im:
            if im.size != (size,size): raise SystemExit(f'bad icon size {p}: {im.size}')
print('PASS: v0.5.0 default/custom desktop icon source and resources')
