from pathlib import Path
import subprocess, tempfile, textwrap

root = Path(__file__).resolve().parents[1]
matcher = root / 'app/src/main/java/com/lchuang/xiaozhimobile/AppNameMatcher.kt'
registry = root / 'app/src/main/java/com/lchuang/xiaozhimobile/InstalledAppRegistry.kt'
phone = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/PhoneController.kt').read_text('utf-8')
router = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt').read_text('utf-8')

if not matcher.exists(): raise SystemExit('AppNameMatcher.kt missing')
if not registry.exists(): raise SystemExit('InstalledAppRegistry.kt missing')
if 'InstalledAppRegistry' not in phone: raise SystemExit('PhoneController not using InstalledAppRegistry')
if 'looksLikeDeviceCommand' not in router: raise SystemExit('CommandRouter missing device-command classifier')

with tempfile.TemporaryDirectory() as td:
    td = Path(td)
    harness = td / 'MatcherHarness.kt'
    harness.write_text(textwrap.dedent('''
        import com.lchuang.xiaozhimobile.AppNameMatcher
        fun main() {
            check(AppNameMatcher.normalize(" WPS Office ") == "wps")
            check(AppNameMatcher.normalize("哔哩哔哩 APP") == "哔哩哔哩")
            val aliases = AppNameMatcher.parseAliases("B站=哔哩哔哩\\n小破站 = 哔哩哔哩\\n夸克浏览器=夸克")
            check(aliases["b站"] == "哔哩哔哩")
            check(aliases["小破站"] == "哔哩哔哩")
            check(AppNameMatcher.aliasTarget("打开B站", aliases) == "哔哩哔哩")
            check(AppNameMatcher.similarity("小红书", "小红书") == 1.0)
            check(AppNameMatcher.similarity("网易云", "网易云音乐") > 0.5)
            println("PASS: installed-app name matcher")
        }
    '''), encoding='utf-8')
    jar = td / 'matcher.jar'
    subprocess.run(['kotlinc', str(matcher), str(harness), '-include-runtime', '-d', str(jar)], check=True)
    subprocess.run(['java', '-jar', str(jar)], check=True)

print('PASS: installed app registry source integration')
