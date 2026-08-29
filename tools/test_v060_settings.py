from pathlib import Path

root = Path(__file__).resolve().parents[1]
settings = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/SettingsStore.kt').read_text('utf-8')
models = root / 'app/src/main/java/com/lchuang/xiaozhimobile/AiModels.kt'
manifest = (root / 'app/src/main/AndroidManifest.xml').read_text('utf-8')

required = [
    'var assistantName: String', '"小智"',
    'var wakePhrase: String', '"小智小智"',
    'var defaultMapApp: MapAppPreference',
    'var ttsVoiceName: String',
    'var ttsSpeechRate: Float',
    'var ttsPitch: Float',
    'var apiBaseUrl: String',
    'var apiMode: ApiMode',
    'migrateLegacyApiUrlIfNeeded',
    '/v1/chat/completions', '/v1/responses',
]
missing = [x for x in required if x not in settings]
if missing:
    raise SystemExit('missing v0.6 settings: ' + ', '.join(missing))
if not models.exists():
    raise SystemExit('AiModels.kt missing')
model_text = models.read_text('utf-8')
for value in ['AUTO', 'CHAT_COMPLETIONS', 'RESPONSES', 'MapAppPreference']:
    if value not in model_text:
        raise SystemExit('missing settings enum: ' + value)
for permission in ['android.permission.QUERY_ALL_PACKAGES', 'android.permission.ACCESS_COARSE_LOCATION', 'android.permission.ACCESS_FINE_LOCATION']:
    if permission not in manifest:
        raise SystemExit('missing permission: ' + permission)
if 'ACCESS_BACKGROUND_LOCATION' in manifest:
    raise SystemExit('background location must not be requested')
print('PASS: v0.6 settings and permissions')
