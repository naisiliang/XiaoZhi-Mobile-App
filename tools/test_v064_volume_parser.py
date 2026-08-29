from pathlib import Path
import subprocess, tempfile, textwrap
root=Path(__file__).resolve().parents[1]
parser=root/'app/src/main/java/com/lchuang/xiaozhimobile/VolumeCommandParser.kt'
with tempfile.TemporaryDirectory() as td:
    td=Path(td)
    h=td/'VolumeHarness.kt'
    h.write_text(textwrap.dedent('''
        import com.lchuang.xiaozhimobile.*
        fun main() {
            val p=VolumeCommandParser()
            check(p.parse("把手机音量调到最大") == VolumeAction.SetPercent(100))
            check(p.parse("声音开满") == VolumeAction.SetPercent(100))
            check(p.parse("音量调到最高") == VolumeAction.SetPercent(100))
            check(p.parse("音量调到百分之七十") == VolumeAction.SetPercent(70))
            check(p.parse("音量调整到70%") == VolumeAction.SetPercent(70))
            check(p.parse("音量调到70") == VolumeAction.SetPercent(70))
            check(p.parse("音量调到一半") == VolumeAction.SetPercent(50))
            check(p.parse("静音") == VolumeAction.SetPercent(0))
            check(p.parse("声音关掉") == VolumeAction.SetPercent(0))
            check(p.parse("音量大一点") == VolumeAction.StepUp)
            check(p.parse("音量小一点") == VolumeAction.StepDown)
            check(p.parse("打开微信") == VolumeAction.Unhandled)
            mapOf(
                "百分之零" to 0, "百分之十" to 10, "百分之二十五" to 25,
                "百分之五十" to 50, "百分之九十九" to 99, "百分之一百" to 100
            ).forEach { (phrase, expected) ->
                check(p.parse("音量调到$phrase") == VolumeAction.SetPercent(expected)) { phrase }
            }
            for (n in 0..100 step 10) {
                check(p.parse("音量调到${n}%") == VolumeAction.SetPercent(n)) { n.toString() }
            }
            check(p.parse("音量调到101") == VolumeAction.Unhandled)
            println("PASS: natural Chinese media volume parser")
        }
    '''),encoding='utf-8')
    jar=td/'volume.jar'
    subprocess.run(['kotlinc',str(parser),str(h),'-include-runtime','-d',str(jar)],check=True)
    subprocess.run(['java','-jar',str(jar)],check=True)
