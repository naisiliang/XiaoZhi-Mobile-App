from pathlib import Path

root = Path(__file__).resolve().parents[1]
map_file = root / 'app/src/main/java/com/lchuang/xiaozhimobile/MapController.kt'
loc_file = root / 'app/src/main/java/com/lchuang/xiaozhimobile/LocationProvider.kt'
router = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt').read_text('utf-8')
if not map_file.exists() or not loc_file.exists():
    raise SystemExit('map/location classes missing')
text = map_file.read_text('utf-8')
checks = [
    'androidamap://keywordNavi',
    'androidamap://poi',
    'com.autonavi.minimap',
    'baidumap://map/navi',
    'baidumap://map/place/nearby',
    'com.baidu.BaiduMap',
    'geo:0,0?q=',
    'searchNearby',
]
for value in checks:
    if value not in text:
        raise SystemExit('map feature missing: ' + value)
for phrase in ['附近', '高德导航', '百度地图']:
    if phrase not in router:
        raise SystemExit('router map phrase missing: ' + phrase)
loc = loc_file.read_text('utf-8')
if 'ACCESS_FINE_LOCATION' not in loc or 'ACCESS_BACKGROUND_LOCATION' in loc:
    raise SystemExit('location permission handling incorrect')
print('PASS: v0.6 map and foreground location source')
