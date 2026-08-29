from pathlib import Path
root = Path(__file__).resolve().parents[1]
main = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt').read_text('utf-8')
for value in [
    '助手名字', '唤醒短语', '保存并应用',
    '声音', '试听', '语速', '音调',
    '默认地图', '位置权限', '查看已发现应用',
    'Base URL', 'API 模式', '测试 AI 接口',
    '最近一次 App 匹配', '当前 KWS 唤醒短语'
]:
    if value not in main:
        raise SystemExit('v0.6 UI feature missing: ' + value)
if 'ACCESS_FINE_LOCATION' not in main:
    raise SystemExit('foreground location permission UI missing')
print('PASS: v0.6 settings/diagnostic UI source')
