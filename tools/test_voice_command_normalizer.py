from pathlib import Path
import subprocess
import tempfile
import sys

ROOT = Path(__file__).resolve().parents[1]
src = ROOT / 'app/src/main/java/com/lchuang/xiaozhimobile/VoiceCommandNormalizer.kt'

cases = {
    '请 打开 微 信。': '打开微信',
    '打开 q q。': '打开qq',
    '打开扣扣': '打开qq',
    '来首歌': '播放音乐',
    '播放一下音乐': '播放音乐',
    '把音乐停掉': '停止音乐',
    '关掉音乐': '停止音乐',
    '停一下音乐': '暂停音乐',
    '打开网易云音乐': '打开网易云音乐',
}

if not src.exists():
    print(f'FAIL: missing {src.relative_to(ROOT)}')
    sys.exit(1)

with tempfile.TemporaryDirectory() as td:
    td = Path(td)
    main = td / 'Main.kt'
    lines = [
        'import com.lchuang.xiaozhimobile.VoiceCommandNormalizer',
        'fun main() {',
    ]
    for raw, expected in cases.items():
        r = raw.replace('\\', '\\\\').replace('"', '\\"')
        e = expected.replace('\\', '\\\\').replace('"', '\\"')
        lines.append(f'  check(VoiceCommandNormalizer.normalize("{r}") == "{e}") {{ "{r} -> ${{VoiceCommandNormalizer.normalize(\"{r}\")}} expected {e}" }}')
    lines += ['  println("PASS: VoiceCommandNormalizer executable cases")', '}']
    main.write_text('\n'.join(lines), encoding='utf-8')
    jar = td / 'test.jar'
    cp = subprocess.run([
        'kotlinc', str(src), str(main), '-include-runtime', '-d', str(jar)
    ], text=True, capture_output=True)
    if cp.returncode != 0:
        print(cp.stdout)
        print(cp.stderr)
        sys.exit(cp.returncode)
    rp = subprocess.run(['java', '-jar', str(jar)], text=True, capture_output=True)
    print(rp.stdout, end='')
    if rp.returncode != 0:
        print(rp.stderr)
        sys.exit(rp.returncode)
