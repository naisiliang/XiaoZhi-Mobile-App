from pathlib import Path
import subprocess, tempfile, textwrap

root = Path(__file__).resolve().parents[1]
state_file = root / 'app/src/main/java/com/lchuang/xiaozhimobile/ConversationState.kt'
with tempfile.TemporaryDirectory() as td:
    td = Path(td)
    harness = td / 'ConversationStateHarness.kt'
    harness.write_text(textwrap.dedent('''
        import com.lchuang.xiaozhimobile.*
        fun main() {
            check(ConversationState.IDLE_WAKE.statusText() == "等待唤醒…")
            check(ConversationState.LISTENING.statusText() == "正在听你说…")
            check(ConversationState.RECOGNIZING.statusText() == "正在识别…")
            check(ConversationState.EXECUTING.statusText() == "正在执行…")
            check(ConversationState.SPEAKING.statusText() == "正在回复…")
            check(ConversationState.READY_TO_LISTEN.statusText() == "准备继续监听…")
            check(ConversationState.EXITING.statusText() == "正在退出…")
            println("PASS: conversation state labels")
        }
    '''), encoding='utf-8')
    jar = td / 'state.jar'
    subprocess.run(['kotlinc', str(state_file), str(harness), '-include-runtime', '-d', str(jar)], check=True)
    subprocess.run(['java', '-jar', str(jar)], check=True)
