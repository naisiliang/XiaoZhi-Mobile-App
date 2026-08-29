from pathlib import Path
import subprocess, tempfile, textwrap

root = Path(__file__).resolve().parents[1]
settings = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/SettingsStore.kt').read_text('utf-8')
session = root / 'app/src/main/java/com/lchuang/xiaozhimobile/SessionController.kt'

required = [
    'var wakeReply: String',
    '"我在"',
    'var timeoutReply: String',
    '"我先退下了，有问题再唤醒我"',
    'var sessionTimeoutSeconds: Int',
    '20',
    'var appAliases: String',
]
missing = [x for x in required if x not in settings]
if missing:
    raise SystemExit('missing settings: ' + ', '.join(missing))
if not session.exists():
    raise SystemExit('SessionController.kt missing')

with tempfile.TemporaryDirectory() as td:
    td = Path(td)
    harness = td / 'SessionHarness.kt'
    harness.write_text(textwrap.dedent('''
        import com.lchuang.xiaozhimobile.SessionController

        fun main() {
            var now = 1000L
            val s = SessionController { now }
            check(!s.isActive())
            s.start(20)
            check(s.isActive())
            check(s.remainingMs() == 20000L)
            now += 5000L
            check(s.remainingMs() == 15000L)
            check(!s.isExpired())
            s.touch(10)
            check(s.remainingMs() == 10000L)
            now += 10001L
            check(s.isExpired())
            s.stop()
            check(!s.isActive())
            check(s.remainingMs() == 0L)
            println("PASS: SessionController deadline behavior")
        }
    '''), encoding='utf-8')
    jar = td / 'session.jar'
    cmd = ['kotlinc', str(session), str(harness), '-include-runtime', '-d', str(jar)]
    subprocess.run(cmd, check=True)
    subprocess.run(['java', '-jar', str(jar)], check=True)

print('PASS: v0.5.0 session settings defaults')
